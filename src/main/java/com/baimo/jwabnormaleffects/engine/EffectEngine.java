package com.baimo.jwabnormaleffects.engine;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import com.baimo.jwabnormaleffects.JWAbnormalEffects;
import com.baimo.jwabnormaleffects.bridge.ISkillBridge;
import com.baimo.jwabnormaleffects.config.ConfigManager;
import com.baimo.jwabnormaleffects.config.EffectDefinition;
import com.baimo.jwabnormaleffects.config.EffectType;
import com.baimo.jwabnormaleffects.persistence.IDataStore;
import com.baimo.jwabnormaleffects.registry.IEffectRegistry;
import com.baimo.jwabnormaleffects.utils.BenefitScalingCalculator;
import com.baimo.jwabnormaleffects.utils.MessageUtils;
import com.baimo.jwabnormaleffects.utils.TenacityCalculator;
import com.baimo.jwabnormaleffects.utils.VariableResolverUtil;
import com.baimo.jwabnormaleffects.config.NonAccumulationReapplyPolicy;

import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.core.mobs.ActiveMob;

/**
 * 效果处理引擎 - 纯内存缓存版本
 * 处理积累制和非积累制异常效果的核心逻辑
 */
public class EffectEngine implements IEffectEngine {
    private final JWAbnormalEffects plugin;
    private final IEffectRegistry effectRegistry;
    private final IDataStore dataStore;
    private final ISkillBridge skillBridge;
    private final ConfigManager configManager;
    // 记录已调度任务的效果，避免重复
    private final Map<UUID, Set<String>> scheduledTasks = new java.util.concurrent.ConcurrentHashMap<>();

    public EffectEngine(JWAbnormalEffects plugin, IEffectRegistry effectRegistry, IDataStore dataStore, ISkillBridge skillBridge, ConfigManager configManager) {
        this.plugin = plugin;
        this.effectRegistry = effectRegistry;
        this.dataStore = dataStore;
        this.skillBridge = skillBridge;
        this.configManager = configManager;
    }

    @Override
    public void applyEffect(Entity target, Entity caster, String effectId, double value) {
        // 兼容旧调用，使用默认策略
        applyEffect(target, caster, effectId, value, null);
    }

    @Override
    public void applyEffect(Entity target, Entity caster, String effectId, double value, NonAccumulationReapplyPolicy overridePolicy) {
        if (!(target instanceof LivingEntity)) {
            MessageUtils.log(Level.WARNING, "尝试对非生物实体应用效果: " + target.getType());
            return;
        }

        LivingEntity livingTarget = (LivingEntity) target;
        Optional<EffectDefinition> optDef = effectRegistry.getEffectDefinition(effectId);
        if (!optDef.isPresent()) {
            MessageUtils.log(Level.WARNING, "未找到效果定义: " + effectId);
            return;
        }

        EffectDefinition effectDef = optDef.get();
        
        // 更新最后活动时间
        dataStore.setLastActivityTime(livingTarget.getUniqueId(), effectId, System.currentTimeMillis());
        // 记录最后施法者
        if (caster != null) {
            dataStore.setLastCasterUUID(livingTarget.getUniqueId(), effectId, caster.getUniqueId());
        }

        // 确保在主线程操作实体数据和技能释放
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> doApplyEffect(livingTarget, caster, effectDef, value, overridePolicy));
        } else {
            doApplyEffect(livingTarget, caster, effectDef, value, overridePolicy);
        }
    }

    private void doApplyEffect(LivingEntity target, Entity caster, EffectDefinition effectDef, double value, NonAccumulationReapplyPolicy overridePolicy) {
        // ===== deliver-scaling 处理 =====
        double scaledValue = effectDef.applyDeliverScaling(target, value);
        Map<String, Double> deliverVars = new HashMap<>();
        deliverVars.put("deliver", scaledValue);

        // 在真正应用效果前，先更新同名叠加计数（sameCount）
        updateSameEffectStack(target, effectDef);

        if (effectDef.getType() == EffectType.ACCUMULATION) {
            processAccumulativeEffect(target, caster, effectDef, scaledValue, deliverVars);
        } else {
            processNonAccumulativeEffect(target, caster, effectDef, scaledValue, deliverVars, overridePolicy);
        }

        // 应用效果后，调度延迟任务（若配置）
        scheduleEffectTasks(target, caster, effectDef);
    }

    private void processAccumulativeEffect(LivingEntity target, Entity caster, EffectDefinition effectDef, double incomingValue, Map<String, Double> deliverVars) {
        UUID targetUUID = target.getUniqueId();
        String effectId = effectDef.getId();
        
        double currentAccumulated = dataStore.getAccumulatedValue(targetUUID, effectId);
        double baseThreshold = effectDef.computeThreshold(target);
        double currentEntityThreshold = dataStore.getCurrentThreshold(targetUUID, effectId, baseThreshold);

        // 计算新的累积值
        double newAccumulated = currentAccumulated + incomingValue;

        if (configManager.isVerboseEffectLogs()) {
            MessageUtils.log(Level.FINEST, String.format("累积: 实体=%s, 效果=%s, 新值=%.2f", 
                    target.getName(), effectId, newAccumulated));
        }

        // 先更新累积值到数据库，确保占位符能读取到新值
        dataStore.setAccumulatedValue(targetUUID, effectId, newAccumulated);
        
        // 处理阶段效果（此时占位符能读取到正确的新值）
        processStageEffects(target, caster, effectDef, newAccumulated, currentEntityThreshold);

        // 检查是否达到阈值
        if (newAccumulated >= currentEntityThreshold) {
            // 计算爆发技能用数值
            double finalVal = calculateFinalValue(target, caster, effectDef, deliverVars);

            // 构建变量并释放技能
            Map<String, Object> vars = buildSkillVars(finalVal, effectDef, targetUUID);
            skillBridge.castSkillWithVars(caster, effectDef.getTriggerSkill(), target, vars);

            // 更新阈值与重置累积
            double resistanceStep = effectDef.computeResistanceStep(target);
            double newThreshold = currentEntityThreshold * (1.0 + resistanceStep);
            dataStore.setCurrentThreshold(targetUUID, effectId, newThreshold);

            if (configManager.isVerboseEffectLogs()) {
                MessageUtils.log(Level.INFO, String.format("实体 %s 效果 %s 爆发！新阈值: %.2f (base=%.2f, final=%.2f, scale=%.2f)",
                        target.getName(), effectId, newThreshold, dataStore.getLastBaseValue(targetUUID, effectId), finalVal, dataStore.getLastScaleFactor(targetUUID, effectId)));
            }

            // 重置累积值
            double resetValue = effectDef.computeResetValueAfterTrigger(newThreshold);
            dataStore.setAccumulatedValue(targetUUID, effectId, resetValue);
            dataStore.setLastTriggeredStage(targetUUID, effectId, 0);
        }
    }

    /**
     * 计算最终值，包括韧性减免和收益递减
     */
    private double calculateFinalValue(LivingEntity target, Entity caster, EffectDefinition effectDef, Map<String, Double> deliverVars) {
        LivingEntity baseCtx = (caster instanceof LivingEntity) ? (LivingEntity) caster : target;
        double baseVal = effectDef.computeBaseValue(baseCtx, deliverVars);
        double afterTenacity = TenacityCalculator.apply(target, effectDef, baseVal, configManager);
        double scaleFactor = BenefitScalingCalculator.apply(target, effectDef.getId(), effectDef.getBenefitScaling(), dataStore, configManager);
        double finalVal = afterTenacity * scaleFactor;
        
        UUID targetUUID = target.getUniqueId();
        dataStore.setLastFinalValue(targetUUID, effectDef.getId(), finalVal);
        dataStore.setLastScaleFactor(targetUUID, effectDef.getId(), scaleFactor);
        dataStore.setLastBaseValue(targetUUID, effectDef.getId(), baseVal);
        dataStore.setLastInstant(targetUUID, effectDef.getId(), baseVal == 0.0);
        return finalVal;
    }

    /**
     * 构建技能变量
     */
    private Map<String, Object> buildSkillVars(double finalVal, EffectDefinition effectDef, UUID targetUUID) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("aeFinal", finalVal);
        vars.put("aeBase", dataStore.getLastBaseValue(targetUUID, effectDef.getId()));
        vars.put("scale", dataStore.getLastScaleFactor(targetUUID, effectDef.getId()));
        vars.put("instant", dataStore.getLastInstant(targetUUID, effectDef.getId()));
        return vars;
    }

    private void processStageEffects(LivingEntity target, Entity caster, EffectDefinition effectDef, double currentValue, double threshold) {
        UUID targetUUID = target.getUniqueId();
        String effectId = effectDef.getId();
        
        int lastTriggeredStagePercent = dataStore.getLastTriggeredStage(targetUUID, effectId);
        double currentPercent = (currentValue / threshold) * 100.0;

        int maxTriggeredStage = lastTriggeredStagePercent;
        for (Map.Entry<Integer, String> stageEntry : effectDef.getStages().entrySet()) {
            int stagePercent = stageEntry.getKey();
            if (stagePercent > lastTriggeredStagePercent && currentPercent >= stagePercent) {
                String stageSkill = stageEntry.getValue();
                if (stageSkill != null) {
                    skillBridge.castSkillWithVars(caster, stageSkill, target, new HashMap<>());
                    if (configManager.isVerboseEffectLogs()) {
                        MessageUtils.log(Level.FINE, String.format("实体 %s 效果 %s 达到 %d%% 阶段", 
                                target.getName(), effectId, stagePercent));
                    }
                }
                maxTriggeredStage = Math.max(maxTriggeredStage, stagePercent);
            }
        }
        if (maxTriggeredStage > lastTriggeredStagePercent) {
            dataStore.setLastTriggeredStage(targetUUID, effectId, maxTriggeredStage);
        }
    }

    private void processNonAccumulativeEffect(LivingEntity target, Entity caster, EffectDefinition effectDef, double deliveredValue, Map<String, Double> deliverVars, NonAccumulationReapplyPolicy overridePolicy) {
        UUID targetUUID = target.getUniqueId();
        String effectId = effectDef.getId();

        deliverVars.putIfAbsent("deliver", deliveredValue);
        double valueexpr = effectDef.computeBaseValue(target, deliverVars);
        boolean isInstantEffect = valueexpr == 0.0;

        double finalValue = calculateFinalValue(target, caster, effectDef, deliverVars);

        long now = System.currentTimeMillis();
        long durationMillis = isInstantEffect ? 0L : (long) (finalValue * 1000L);
        long newExpireTime = isInstantEffect ? 0L : now + durationMillis;

        boolean alreadyActive = dataStore.isDirectEffectActive(targetUUID, effectId);
        NonAccumulationReapplyPolicy policy = overridePolicy != null ? overridePolicy : effectDef.getReapplyPolicy();
        switch (policy) {
            case IGNORE:
                if (alreadyActive) return;
                break;
            case STACK_DURATION:
                if (alreadyActive) {
                    long oldExpire = dataStore.getExpireTime(targetUUID, effectId);
                    dataStore.setExpireTime(targetUUID, effectId, oldExpire + durationMillis);
                    return;
                }
                break;
            case STACK_DURATION_TRIGGER:
                if (alreadyActive) {
                    long oldExpire = dataStore.getExpireTime(targetUUID, effectId);
                    newExpireTime = oldExpire + durationMillis;
                }
                break;
            case OVERRIDE_SILENT:
                if (alreadyActive) {
                    dataStore.setExpireTime(targetUUID, effectId, newExpireTime);
                    return;
                }
                break;
            case OVERRIDE:
            default:
                break;
        }

        Map<String, Object> vars = buildSkillVars(finalValue, effectDef, targetUUID);
        skillBridge.castSkillWithVars(caster, effectDef.getEffectSkill(), target, vars);

        if (!isInstantEffect) {
            dataStore.setExpireTime(targetUUID, effectId, newExpireTime);
        } else {
            dataStore.clearEntityEffectData(targetUUID, effectId);
        }

        if (configManager.isVerboseEffectLogs()) {
            MessageUtils.log(Level.FINE, String.format("非积累效果 %s 应用: base=%.2f, final=%.2f, scale=%.2f", 
                    effectId, valueexpr, finalValue, dataStore.getLastScaleFactor(targetUUID, effectId)));
        }
    }

    @Override
    public void processDecayForEntity(Entity entity, String effectId) {
        if (!(entity instanceof LivingEntity)) return;
        LivingEntity livingEntity = (LivingEntity) entity;
        UUID entityUUID = livingEntity.getUniqueId();

        Optional<EffectDefinition> optDef = effectRegistry.getEffectDefinition(effectId);
        if (!optDef.isPresent() || !optDef.get().isDecayEnabled()) {
            return;
        }
        EffectDefinition effectDef = optDef.get();

        double currentAccumulated = dataStore.getAccumulatedValue(entityUUID, effectId);
        if (currentAccumulated <= 0) {
            dataStore.clearEntityEffectData(entityUUID, effectId);
            return;
        }

        long currentTime = System.currentTimeMillis();
        long currentServerTick = currentTime / 50;

        int inactivitySeconds = effectDef.getDecayInactivitySeconds();
        if (inactivitySeconds > 0) {
            long lastActivityTime = dataStore.getLastActivityTime(entityUUID, effectId);
            if (lastActivityTime > 0 && (currentTime - lastActivityTime) / 1000 < inactivitySeconds) {
                return;
            }
        }

        long lastDecayServerTick = dataStore.getLastDecayTick(entityUUID, effectId);
        if (lastDecayServerTick == 0) {
            dataStore.setLastDecayTick(entityUUID, effectId, currentServerTick);
            return;
        }

        long intervalTicks = effectDef.getDecayIntervalTicks() > 0 ? effectDef.getDecayIntervalTicks() : configManager.getDecayCheckIntervalTicks();

        if (currentServerTick >= lastDecayServerTick + intervalTicks) {
            double decayAmount;
            String decayExpr = effectDef.getDecayPerIntervalExpr();
            if (decayExpr != null && !decayExpr.trim().isEmpty() && decayExpr.trim().endsWith("%")) {
                String percentExpr = decayExpr.trim().substring(0, decayExpr.trim().length() - 1).trim();
                double percent;
                try {
                    percent = VariableResolverUtil.resolveDouble(livingEntity, percentExpr, null);
                } catch (Exception e) {
                    try {
                        percent = Double.parseDouble(percentExpr);
                    } catch (NumberFormatException ex) {
                        percent = 0;
                    }
                }
                if (percent < 0) percent = 0;
                decayAmount = currentAccumulated * (percent / 100.0);
            } else {
                decayAmount = effectDef.computeDecayPerInterval(livingEntity);
            }

            double newValue = currentAccumulated - decayAmount;
            newValue = Math.max(0, newValue);

            long previousActivityTime = dataStore.getLastActivityTime(entityUUID, effectId);
            dataStore.setAccumulatedValue(entityUUID, effectId, newValue);
            dataStore.setLastActivityTime(entityUUID, effectId, previousActivityTime);
            dataStore.setLastDecayTick(entityUUID, effectId, currentServerTick);

            if (configManager.isVerboseEffectLogs()) {
                MessageUtils.log(Level.FINEST, String.format("衰减: 实体=%s, 效果=%s, 新值=%.2f", 
                        livingEntity.getName(), effectId, newValue));
            }

            if (newValue <= 0) {
                dataStore.clearEntityEffectData(entityUUID, effectId);
                if (configManager.isShowEffectMessages() && livingEntity instanceof Player) {
                    Player player = (Player) livingEntity;
                    MessageUtils.send(player, "effects.dissipated", 
                            "effect_name", effectDef.getDisplayName());
                }
            } else {
                processDecayStageCheck(livingEntity, effectDef, newValue);
            }
        }
    }

    private void processDecayStageCheck(LivingEntity entity, EffectDefinition effectDef, double newValue) {
        UUID entityUUID = entity.getUniqueId();
        String effectId = effectDef.getId();
        
        int lastStage = dataStore.getLastTriggeredStage(entityUUID, effectId);
        double currentThreshold = dataStore.getCurrentThreshold(entityUUID, effectId, effectDef.computeThreshold(entity));
        double currentPercent = (newValue / currentThreshold) * 100.0;
        
        int currentStage = 0;
        for (int stage : effectDef.getStages().keySet()) {
            if (currentPercent >= stage) {
                currentStage = Math.max(currentStage, stage);
            }
        }
        
        // 当积累值降低时，只提示阶段下降，但不重置 lastTriggeredStage，
        // 以避免在同一轮积累中重复触发已达到的阶段性技能。
        if (currentStage < lastStage) {
            // 只有首次降到该阶段时才提示，避免重复刷屏
            int lastNotified = dataStore.getLastDecreaseNotifiedStage(entityUUID, effectId);
            if (currentStage != lastNotified) {
                if (configManager.isShowStageTransitions() && entity instanceof Player) {
                    Player player = (Player) entity;
                    MessageUtils.send(player, "effects.stage-decrease",
                            "effect_name", effectDef.getDisplayName(),
                            "percent", String.valueOf(currentStage));
                }
                dataStore.setLastDecreaseNotifiedStage(entityUUID, effectId, currentStage);
            }
            // 注意：不重置 lastTriggeredStage，防止阶段技能重复触发
        }
    }

    /**
     * 根据效果定义中的 Task 列表，调度延迟执行的技能。
     * 无论效果是否累计制，都会在每次 applyEffect 调用后执行。
     */
    private void scheduleEffectTasks(LivingEntity target, Entity caster, EffectDefinition effectDef) {
        java.util.List<com.baimo.jwabnormaleffects.config.TaskDefinition> tasks = effectDef.getTasks();
        if (tasks == null || tasks.isEmpty()) return;

        Set<String> entityTaskEffects = scheduledTasks.computeIfAbsent(target.getUniqueId(), k -> java.util.concurrent.ConcurrentHashMap.newKeySet());
        if (!entityTaskEffects.add(effectDef.getId())) {
            return;
        }

        UUID initialCasterUUID = (caster != null) ? caster.getUniqueId() : dataStore.getLastCasterUUID(target.getUniqueId(), effectDef.getId());

        for (com.baimo.jwabnormaleffects.config.TaskDefinition task : tasks) {
            int interval = task.getTicks();
            String skillName = task.getSkill();
            if (interval <= 0 || skillName == null || skillName.isEmpty()) continue;

            new BukkitRunnable() {
                @Override
                public void run() {
                    if (!target.isValid() || target.isDead()) {
                        this.cancel();
                        Set<String> s = scheduledTasks.get(target.getUniqueId());
                        if (s != null) s.remove(effectDef.getId());
                        return;
                    }

                    boolean effectPresent = dataStore.hasEffect(target.getUniqueId(), effectDef.getId());
                    boolean effectActive = effectDef.getType() == EffectType.ACCUMULATION 
                            ? effectPresent 
                            : dataStore.isDirectEffectActive(target.getUniqueId(), effectDef.getId());

                    if (!effectActive) {
                        this.cancel();
                        Set<String> s = scheduledTasks.get(target.getUniqueId());
                        if (s != null) s.remove(effectDef.getId());
                        return;
                    }

                    UUID casterIdLatest = dataStore.getLastCasterUUID(target.getUniqueId(), effectDef.getId());
                    if (casterIdLatest == null) casterIdLatest = initialCasterUUID;

                    Entity currentCaster = null;
                    if (casterIdLatest != null) {
                        currentCaster = Bukkit.getEntity(casterIdLatest);
                    }

                    if (currentCaster == null || !currentCaster.isValid()) {
                        return;
                    }

                    if (effectDef.getType() == EffectType.NON_ACCUMULATION) {
                        long expireAt = dataStore.getExpireTime(target.getUniqueId(), effectDef.getId());
                        if (expireAt == 0 || expireAt - System.currentTimeMillis() <= 100) {
                            this.cancel();
                            Set<String> s = scheduledTasks.get(target.getUniqueId());
                            if (s != null) s.remove(effectDef.getId());
                            return;
                        }
                    }

                    try {
                        skillBridge.castSkillWithVars(currentCaster, skillName, target, new HashMap<>());
                        if (configManager.isVerboseEffectLogs()) {
                            MessageUtils.log(Level.FINEST, String.format("效果 %s 的 Task %s 周期执行，目标=%s", 
                                    effectDef.getId(), task.getId(), target.getName()));
                        }
                    } catch (Exception ex) {
                        MessageUtils.log(Level.WARNING, "执行 Task 技能时发生错误: " + ex.getMessage());
                    }
                }
            }.runTaskTimer(plugin, interval, interval);
        }
    }

    /**
     * 根据效果定义的 stacking 配置更新同名效果叠加次数。
     */
    private void updateSameEffectStack(LivingEntity target, EffectDefinition effectDef) {
        int windowSeconds = effectDef.getStackingWindowSeconds();
        if (windowSeconds <= 0) return;

        UUID uuid = target.getUniqueId();
        String effectId = effectDef.getId();

        long now = System.currentTimeMillis();
        long last = dataStore.getSameEffectLastApplyTime(uuid, effectId);
        int stack = dataStore.getSameEffectStack(uuid, effectId);

        if (last > 0 && (now - last) <= windowSeconds * 1000L) {
            stack += 1;
        } else {
            stack = effectDef.getStackingStartAt();
        }

        dataStore.setSameEffectStack(uuid, effectId, stack);
        dataStore.setSameEffectLastApplyTime(uuid, effectId, now);
        dataStore.setSameEffectWindowSeconds(uuid, effectId, windowSeconds);
    }
}