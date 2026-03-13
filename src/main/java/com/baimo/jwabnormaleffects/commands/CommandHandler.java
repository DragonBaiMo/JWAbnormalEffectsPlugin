package com.baimo.jwabnormaleffects.commands;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import com.baimo.jwabnormaleffects.JWAbnormalEffects;
import com.baimo.jwabnormaleffects.config.ConfigManager;
import com.baimo.jwabnormaleffects.config.EffectDefinition;
import com.baimo.jwabnormaleffects.config.EffectType;
import com.baimo.jwabnormaleffects.config.NonAccumulationReapplyPolicy;
import com.baimo.jwabnormaleffects.engine.IEffectEngine;
import com.baimo.jwabnormaleffects.persistence.IDataStore;
import com.baimo.jwabnormaleffects.registry.IEffectRegistry;
import com.baimo.jwabnormaleffects.utils.MessageUtils;

/**
 * 插件命令处理器
 */
public class CommandHandler implements CommandExecutor, TabCompleter {
    private final JWAbnormalEffects plugin;
    private final IEffectEngine effectEngine;
    private final IEffectRegistry effectRegistry;
    private final ConfigManager configManager;
    private final IDataStore dataStore;
    
    private final String permAdmin;
    private final String permUse;
    
    // Tab补全相关缓存（影响很小）
    private volatile List<String> cachedEffectIds = null;
    private volatile long effectIdsCacheTime = 0;
    private static final long EFFECT_IDS_CACHE_DURATION = 60000; // 60秒缓存
    
    private volatile List<String> cachedPlayerNames = null;
    private volatile long playerNamesCacheTime = 0;
    private static final long PLAYER_NAMES_CACHE_DURATION = 5000; // 5秒缓存
    
    // 重用的集合
    private final List<String> reuseableList = new ArrayList<>(32);
    private final List<String> defaultValues = List.of("0", "1", "3", "5", "10", "15", "20", "30", "60", "100");
    
    // 预编译的正则表达式和常量（性能优化，无功能影响）
    private static final Pattern COLON_PATTERN = Pattern.compile(":", Pattern.LITERAL);
    private static final String PERCENT_SUFFIX = "%";
    private static final String DEFAULT_VALUE = "0.5";
    private static final double DEFAULT_VALUE_DOUBLE = 0.5;

    public CommandHandler(JWAbnormalEffects plugin, IEffectEngine effectEngine, IEffectRegistry effectRegistry, ConfigManager configManager) {
        this.plugin = plugin;
        this.effectEngine = effectEngine;
        this.effectRegistry = effectRegistry;
        this.configManager = configManager;
        this.dataStore = plugin.getDataStore();
        
        this.permAdmin = configManager.getAdminBasePermission();
        this.permUse = configManager.getUseBasePermission();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelpMessage(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "help":
                sendHelpMessage(sender);
                return true;
                
            case "reload":
                if (!hasPermission(sender, permAdmin + ".reload")) return true;
                plugin.reloadPlugin();
                MessageUtils.send(sender, "command.config-reloaded");
                return true;
                
            case "info":
                if (!hasPermission(sender, permAdmin + ".info")) return true;
                sendInfoMessage(sender);
                return true;
                
            case "list":
                if (!hasPermission(sender, permAdmin + ".list")) return true;
                sendEffectsList(sender);
                return true;
                
            case "apply":
                if (!hasPermission(sender, permAdmin + ".apply")) return true;
                return handleApplyCommand(sender, args);
                
            case "clear":
                if (!hasPermission(sender, permAdmin + ".clear")) return true;
                return handleClearCommand(sender, args);
                
            case "look":
                if (!hasPermission(sender, permUse + ".look")) return true;
                return handleLookCommand(sender, args);
                
            default:
                MessageUtils.send(sender, "command.unknown-command");
                return true;
        }
    }

    private boolean handleApplyCommand(CommandSender sender, String[] args) {
        if (args.length < 3) {
            MessageUtils.send(sender, "command.usage.apply");
            return true;
        }

        // 优化字符串解析
        final String rawEffectArg = args[1];
        final String[] effectParts = COLON_PATTERN.split(rawEffectArg, 2);
        final String effectId = effectParts[0];
        
        NonAccumulationReapplyPolicy overridePolicy = null;
        if (effectParts.length == 2) {
            try {
                overridePolicy = NonAccumulationReapplyPolicy.valueOf(effectParts[1].toUpperCase());
            } catch (IllegalArgumentException ex) {
                MessageUtils.send(sender, "command.apply.invalid-policy", "policy", effectParts[1]);
                return true;
            }
        }

        // 获取效果定义
        if (!effectRegistry.hasEffect(effectId)) {
            MessageUtils.send(sender, "command.apply.unknown-effect", "id", effectId);
            return true;
        }
        
        final EffectDefinition effectDef = effectRegistry.getEffectDefinition(effectId).orElse(null);
        if (effectDef == null) {
            MessageUtils.send(sender, "command.apply.unknown-effect", "id", effectId);
            return true;
        }

        // 若为积累制效果，忽略任何再次应用策略覆写
        final boolean isAccumulation = effectDef.getType() == EffectType.ACCUMULATION;
        if (isAccumulation) {
            overridePolicy = null;
        }

        double value;
        final LivingEntity target;
        LivingEntity caster = null;
        UUID explicitCasterUUID = null;

        if (isAccumulation) {
            // 积累性效果：apply <effectId> <value> <target> [caster]
            if (args.length < 4) {
                MessageUtils.send(sender, "command.usage.apply-accumulation");
                return true;
            }

            // 优化数值解析
            final String rawValueStr = args[2];
            final boolean isPercentInput = rawValueStr.endsWith(PERCENT_SUFFIX);
            final String numericPart = isPercentInput ? rawValueStr.substring(0, rawValueStr.length() - 1) : rawValueStr;
            
            double parsedNumber;
            try {
                parsedNumber = Double.parseDouble(numericPart);
            } catch (NumberFormatException e) {
                MessageUtils.send(sender, "command.apply.invalid-value");
                parsedNumber = DEFAULT_VALUE_DOUBLE;
                MessageUtils.send(sender, "command.apply.force-default-value", "value", DEFAULT_VALUE);
            }
            target = resolveLivingEntity(args[3]);
            if (target == null) {
                MessageUtils.send(sender, "command.apply.player-not-found", "player", args[3]);
                return true;
            }

            // 批量计算阈值和数值
            if (isPercentInput) {
                final double baseThreshold = effectDef.computeThreshold(target);
                final double currentThreshold = dataStore.getCurrentThreshold(target.getUniqueId(), effectId, baseThreshold);
                value = currentThreshold * (parsedNumber * 0.01); // 直接乘以0.01而非除以100
            } else {
                value = parsedNumber;
            }
            
            if (args.length >= 5) {
                caster = resolveLivingEntity(args[4]);
                if (caster == null) {
                    try {
                        explicitCasterUUID = UUID.fromString(args[4]);
                    } catch (IllegalArgumentException ex) {
                        MessageUtils.send(sender, "command.apply.caster-not-found", "player", args[4]);
                        return true;
                    }
                }
            }
        } else {
            // 非积累性效果：apply <effectId> <continue-time> <target> [caster]
            if (args.length < 4) {
                MessageUtils.send(sender, "command.usage.apply-direct");
                return true;
            }

            // 优化非积累效果数值解析
            final String directRawVal = args[2];
            final String numericValue = directRawVal.endsWith(PERCENT_SUFFIX) ? 
                directRawVal.substring(0, directRawVal.length() - 1) : directRawVal;
            
            try {
                value = Double.parseDouble(numericValue);
                if (value < 0) {
                    MessageUtils.send(sender, "command.apply.invalid-value");
                    value = DEFAULT_VALUE_DOUBLE;
                    MessageUtils.send(sender, "command.apply.force-default-value", "value", DEFAULT_VALUE);
                }
            } catch (NumberFormatException e) {
                MessageUtils.send(sender, "command.apply.invalid-value");
                value = DEFAULT_VALUE_DOUBLE;
                MessageUtils.send(sender, "command.apply.force-default-value", "value", DEFAULT_VALUE);
            }

            target = resolveLivingEntity(args[3]);
            if (target == null) {
                MessageUtils.send(sender, "command.apply.player-not-found", "player", args[3]);
                return true;
            }

            // 检查效果是否仍在持续
            if (dataStore.isDirectEffectActive(target.getUniqueId(), effectId)) {
                // 引入再次应用策略判断：仅在 IGNORE 策略下才阻止继续执行
                NonAccumulationReapplyPolicy policy = overridePolicy != null ? overridePolicy : effectDef.getReapplyPolicy();
                if (policy == NonAccumulationReapplyPolicy.IGNORE) {
                    MessageUtils.sendEffectMessage(sender, "command.apply.effect-active", effectId, 
                            "player", target.getName(), 
                            "id", effectId);
                    return true;
                }
                // 对于 STACK_DURATION 与 OVERRIDE 策略，继续执行，交由 EffectEngine 处理
            }

            if (args.length >= 5) {
                caster = resolveLivingEntity(args[4]);
                if (caster == null) {
                    try {
                        explicitCasterUUID = UUID.fromString(args[4]);
                    } catch (IllegalArgumentException ex) {
                        MessageUtils.send(sender, "command.apply.caster-not-found", "player", args[4]);
                        return true;
                    }
                }
            }
        }

        // 批量处理施法者UUID和效果应用
        final UUID casterUuidToStore = (caster != null) ? caster.getUniqueId() : explicitCasterUUID;
        if (casterUuidToStore != null) {
            dataStore.setLastCasterUUID(target.getUniqueId(), effectId, casterUuidToStore);
        }

        // 预计算最终数值
        final double finalValue = effectDef.applyDeliverScaling(target, value);
        
        effectEngine.applyEffect(target, caster, effectId, finalValue, overridePolicy);
        
        // 优化消息发送和数据设置
        if (isAccumulation) {
            MessageUtils.sendEffectMessage(sender, "command.apply.success-accumulation", effectId,
                    "id", effectId,
                    "value", String.valueOf(finalValue),
                    "player", target.getName());
        } else {
            // 批量设置过期时间和发送消息
            if (finalValue > 0) {
                final long expireAt = System.currentTimeMillis() + (long) (finalValue * 1000L);
                dataStore.setExpireTime(target.getUniqueId(), effectId, expireAt);
            }
            
            final String timeDisplay = finalValue > 0 ? String.valueOf(finalValue) : "即时";
            MessageUtils.sendEffectMessage(sender, "command.apply.success-direct", effectId,
                    "id", effectId,
                    "player", target.getName(),
                    "seconds", timeDisplay);
        }
        
        return true;
    }

    private boolean handleClearCommand(CommandSender sender, String[] args) {
        if (args.length < 3) {
            MessageUtils.send(sender, "command.usage.clear");
            return true;
        }

        final String effectId = args[1];
        final LivingEntity target = resolveLivingEntity(args[2]);
        
        if (target == null) {
            MessageUtils.send(sender, "command.apply.player-not-found", "player", args[2]);
            return true;
        }

        if ("all".equalsIgnoreCase(effectId)) {
            // 批量清除所有效果（尊重 persist 标志）
            final UUID uuid = target.getUniqueId();
            final Set<String> activeEffects = dataStore.getActiveEffectsForEntity(uuid);
            int removed = 0;
            
            for (final String eid : activeEffects) {
                final Optional<EffectDefinition> optDef = effectRegistry.getEffectDefinition(eid);
                if (optDef.isPresent() && optDef.get().isPersist()) {
                    continue;
                }
                dataStore.clearEntityEffectData(uuid, eid);
                removed++;
            }
            MessageUtils.send(sender, "command.clear.all-success", "player", target.getName(), "count", String.valueOf(removed));
        } else if (effectRegistry.hasEffect(effectId)) {
            // 清除特定效果
            dataStore.clearEntityEffectData(target.getUniqueId(), effectId);
            MessageUtils.sendEffectMessage(sender, "command.clear.specific-success", effectId,
                    "player", target.getName(), 
                    "id", effectId);
        } else {
            MessageUtils.send(sender, "command.clear.unknown-effect", "id", effectId);
        }
        
        return true;
    }

    private boolean handleLookCommand(CommandSender sender, String[] args) {
        final UUID targetUUID;
        final String targetName;

        if (args.length >= 2) {
            final String uuidStr = args[1];
            try {
                targetUUID = UUID.fromString(uuidStr);
                final Player player = Bukkit.getPlayer(targetUUID);
                if (player != null && player.isOnline()) {
                    targetName = player.getName();
                } else {
                    targetName = "实体(" + uuidStr.substring(0, 8) + "...)";
                }
            } catch (IllegalArgumentException e) {
                MessageUtils.send(sender, "command.look.invalid-uuid", "uuid", uuidStr);
                return true;
            }
        } else {
            if (!(sender instanceof Player)) {
                MessageUtils.send(sender, "command.look.console-need-uuid");
                return true;
            }
            final Player player = (Player) sender;
            targetUUID = player.getUniqueId();
            targetName = player.getName();
        }

        // 批量处理效果清理和获取
        dataStore.cleanupExpiredEffects();
        final Set<String> activeEffects = dataStore.getActiveEffectsForEntity(targetUUID);
        
        if (activeEffects.isEmpty()) {
            MessageUtils.send(sender, "command.look.no-effects", "target", targetName);
            return true;
        }

        // 批量显示效果信息
        MessageUtils.send(sender, "command.look.header", "target", targetName, "count", String.valueOf(activeEffects.size()));
        
        for (final String effectId : activeEffects) {
            final Optional<EffectDefinition> optDef = effectRegistry.getEffectDefinition(effectId);
            if (!optDef.isPresent()) {
                continue;
            }
            final EffectDefinition effectDef = optDef.get();
            
            final String displayName = effectDef.getDisplayName() != null ? effectDef.getDisplayName() : effectId;
            
            if (effectDef.getType() == EffectType.ACCUMULATION) {
                // 批量获取积累性效果数据
                final double currentValue = dataStore.getAccumulatedValue(targetUUID, effectId);
                final LivingEntity ctxEntity = Bukkit.getEntity(targetUUID) instanceof LivingEntity ? (LivingEntity) Bukkit.getEntity(targetUUID) : null;
                final double currentThreshold = dataStore.getCurrentThreshold(targetUUID, effectId, effectDef.computeThreshold(ctxEntity));
                final double percentage = currentThreshold > 0 ? (currentValue / currentThreshold) * 100.0 : 0.0;
                final int lastStage = dataStore.getLastTriggeredStage(targetUUID, effectId);
                
                MessageUtils.send(sender, "command.look.effect-accumulation",
                        "id", effectId,
                        "effect_name", displayName,
                        "value", String.format("%.2f", currentValue),
                        "threshold", String.format("%.2f", currentThreshold),
                        "percent", String.format("%.1f", percentage),
                        "stage", String.valueOf(lastStage));
            } else {
                // 批量处理非积累性效果显示
                final long expireTime = dataStore.getExpireTime(targetUUID, effectId);
                final String remainingTime;
                if (expireTime == 0L) {
                    remainingTime = "即时效果";
                } else {
                    final long remaining = expireTime - System.currentTimeMillis();
                    if (remaining <= 0L) {
                        remainingTime = "已过期";
                    } else {
                        remainingTime = String.format("%.1f秒", remaining / 1000.0);
                    }
                }
                
                MessageUtils.send(sender, "command.look.effect-direct",
                        "id", effectId,
                        "effect_name", displayName,
                        "base_value", String.format("%.2f", dataStore.getLastBaseValue(targetUUID, effectId)),
                        "remaining_time", remainingTime);
            }
        }
        
        MessageUtils.send(sender, "command.look.footer");
        return true;
    }
    
    private void sendHelpMessage(CommandSender sender) {
        MessageUtils.send(sender, "command.help.header");
        MessageUtils.send(sender, "command.help.help");
        
        if (sender.hasPermission(permAdmin + ".reload")) {
            MessageUtils.send(sender, "command.help.reload");
        }
        
        if (sender.hasPermission(permAdmin + ".info")) {
            MessageUtils.send(sender, "command.help.info");
        }
        
        if (sender.hasPermission(permAdmin + ".list")) {
            MessageUtils.send(sender, "command.help.list");
        }
        
        if (sender.hasPermission(permAdmin + ".apply")) {
            MessageUtils.send(sender, "command.help.apply");
            MessageUtils.send(sender, "command.help.apply-accumulation");
            MessageUtils.send(sender, "command.help.apply-direct");
        }
        
        if (sender.hasPermission(permAdmin + ".clear")) {
            MessageUtils.send(sender, "command.help.clear");
        }
        
        if (sender.hasPermission(permUse + ".look")) {
            MessageUtils.send(sender, "command.help.look");
        }
        
        MessageUtils.send(sender, "command.help.footer");
    }
    
    private void sendInfoMessage(CommandSender sender) {
        MessageUtils.send(sender, "command.info.header");
        MessageUtils.send(sender, "command.info.version", "version", plugin.getDescription().getVersion());
        MessageUtils.send(sender, "command.info.authors", "authors", String.join(", ", plugin.getDescription().getAuthors()));
        MessageUtils.send(sender, "command.info.effect-count", "count", String.valueOf(effectRegistry.getAllEffectDefinitions().size()));
        
        // MythicMobs 版本
        String mmVersion = "未安装";
        if (Bukkit.getPluginManager().isPluginEnabled("MythicMobs")) {
            try {
                mmVersion = Bukkit.getPluginManager().getPlugin("MythicMobs").getDescription().getVersion();
            } catch (Exception e) {
                mmVersion = "安装但无法获取版本";
            }
        }
        MessageUtils.send(sender, "command.info.mythicmobs-version", "version", mmVersion);
        
        // PlaceholderAPI 状态
        boolean papiEnabled = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
        MessageUtils.send(sender, "command.info.placeholderapi-status", 
                "status", papiEnabled ? "已启用" : "未启用");
        
        MessageUtils.send(sender, "command.info.footer");
    }
    
    private void sendEffectsList(CommandSender sender) {
        Collection<EffectDefinition> effects = effectRegistry.getAllEffectDefinitions();
        
        if (effects.isEmpty()) {
            MessageUtils.send(sender, "command.list.no-effects");
            return;
        }
        
        MessageUtils.send(sender, "command.list.header");
        for (EffectDefinition effect : effects) {
            String typeMark = effect.getType() == EffectType.ACCUMULATION ? "§a[积累]" : "§b[直接]";
            MessageUtils.send(sender, "command.list.effect-entry", 
                    "type_mark", typeMark, 
                    "id", effect.getId(), 
                    "effect_name", effect.getDisplayName());
        }
        MessageUtils.send(sender, "command.list.footer");
    }
    
    private boolean hasPermission(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        MessageUtils.send(sender, "command.no-permission");
        return false;
    }
    
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return getSubCommandsForSender(sender, args[0]);
        } else if (args.length >= 2) {
            String subCommand = args[0].toLowerCase();
            switch (subCommand) {
                case "apply":
                    if (!hasPermission(sender, permAdmin + ".apply")) return Collections.emptyList();
                    return handleApplyTabComplete(args);
                case "clear":
                    if (!hasPermission(sender, permAdmin + ".clear")) return Collections.emptyList();
                    return handleClearTabComplete(args);
                case "look":
                    if (!hasPermission(sender, permUse + ".look")) return Collections.emptyList();
                    return handleLookTabComplete(args);
                default:
                    return Collections.emptyList();
            }
        }
        return Collections.emptyList();
    }
    
    private List<String> getSubCommandsForSender(CommandSender sender, String prefix) {
        reuseableList.clear();
        if (hasPermission(sender, permAdmin + ".reload")) reuseableList.add("reload");
        if (hasPermission(sender, permAdmin + ".info")) reuseableList.add("info");
        if (hasPermission(sender, permAdmin + ".list")) reuseableList.add("list");
        if (hasPermission(sender, permAdmin + ".apply")) reuseableList.add("apply");
        if (hasPermission(sender, permAdmin + ".clear")) reuseableList.add("clear");
        if (hasPermission(sender, permUse + ".look")) reuseableList.add("look");
        reuseableList.add("help");
        return filterByStartOptimized(reuseableList, prefix);
    }
    
    private List<String> handleApplyTabComplete(String[] args) {
        if (args.length == 2) {
            return filterByStartOptimized(getCachedEffectIds(), args[1]);
        } else if (args.length == 3) {
            return defaultValues;
        } else if (args.length == 4 || args.length == 5) {
            return getCachedPlayerNames(args[args.length - 1]);
        }
        return Collections.emptyList();
    }
    
    private List<String> handleClearTabComplete(String[] args) {
        if (args.length == 2) {
            List<String> effectIds = getCachedEffectIds();
            if (!effectIds.contains("all")) {
                effectIds = new ArrayList<>(effectIds);
                effectIds.add("all");
            }
            return filterByStartOptimized(effectIds, args[1]);
        } else if (args.length == 3) {
            return getCachedPlayerNames(args[2]);
        }
        return Collections.emptyList();
    }
    
    private List<String> handleLookTabComplete(String[] args) {
        if (args.length == 2) {
            String prefix = args[1].toLowerCase();
            List<String> uuidList = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                String uuid = player.getUniqueId().toString();
                if (uuid.toLowerCase().startsWith(prefix)) {
                    uuidList.add(uuid);
                }
            }
            return uuidList;
        }
        return Collections.emptyList();
    }
    
    private List<String> filterByStartOptimized(List<String> list, String prefix) {
        if (prefix.isEmpty()) return list;
        
        String lowerPrefix = prefix.toLowerCase();
        List<String> result = new ArrayList<>();
        for (String item : list) {
            if (item.toLowerCase().startsWith(lowerPrefix)) {
                result.add(item);
            }
        }
        return result;
    }
    
    private List<String> getCachedPlayerNames(String prefix) {
        long currentTime = System.currentTimeMillis();
        
        // 检查缓存是否有效
        if (cachedPlayerNames == null || (currentTime - playerNamesCacheTime) > PLAYER_NAMES_CACHE_DURATION) {
            // 更新缓存
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                names.add(player.getName());
            }
            cachedPlayerNames = names;
            playerNamesCacheTime = currentTime;
        }
        
        return filterByStartOptimized(cachedPlayerNames, prefix);
    }
    
    private List<String> getCachedEffectIds() {
        long currentTime = System.currentTimeMillis();
        
        // 检查缓存是否有效
        if (cachedEffectIds == null || (currentTime - effectIdsCacheTime) > EFFECT_IDS_CACHE_DURATION) {
            // 更新缓存
            List<String> ids = new ArrayList<>();
            for (EffectDefinition def : effectRegistry.getAllEffectDefinitions()) {
                ids.add(def.getId());
            }
            cachedEffectIds = ids;
            effectIdsCacheTime = currentTime;
        }
        
        return cachedEffectIds;
    }

    private LivingEntity resolveLivingEntity(String identifier) {
        // 尝试按玩家名称解析
        Player player = Bukkit.getPlayer(identifier);
        if (player != null) {
            return player;
        }
        // 尝试解析为 UUID 并查找实体
        try {
            UUID uuid = UUID.fromString(identifier);
            // 在线玩家优先
            player = Bukkit.getPlayer(uuid);
            if (player != null) {
                return player;
            }
            // 直接使用 Bukkit.getEntity(UUID)（高版本）
            org.bukkit.entity.Entity ent = Bukkit.getEntity(uuid);
            if (ent instanceof LivingEntity) {
                return (LivingEntity) ent;
            }
            // 若仍未找到实体则返回 null，不再遍历世界实体，以免造成性能消耗
        } catch (IllegalArgumentException ignored) {
            // 既不是有效玩家，也不是 UUID
        }
        return null;
    }
} 