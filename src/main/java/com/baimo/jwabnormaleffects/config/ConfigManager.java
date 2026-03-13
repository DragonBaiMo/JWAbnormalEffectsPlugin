package com.baimo.jwabnormaleffects.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.stream.Stream;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import com.baimo.jwabnormaleffects.JWAbnormalEffects;
import com.baimo.jwabnormaleffects.registry.IEffectRegistry;
import com.baimo.jwabnormaleffects.utils.ColorUtil;
import com.baimo.jwabnormaleffects.utils.MessageUtils;

public class ConfigManager {
    private final JWAbnormalEffects plugin;
    private final IEffectRegistry effectRegistry;
    private final File effectsFolder;
    private final Map<String, EffectDefinition> effectDefinitions = new HashMap<>();
    
    // 主配置相关字段
    private FileConfiguration mainConfig;
    private String logLevel = "INFO";
    private boolean verboseEffectLogs = false;
    
    // 衰减设置
    private int decayCheckIntervalTicks = 20;
    private boolean asyncDecayProcessing = true;
    
    // 韧性机制设置
    private String tenacityStatName = "TENACITY";
    private double tenacityReductionPerPoint = 0.01;
    private double tenacityMaxReduction = 0.8;
    private String tenacityFormula = "linear";
    
    // UI 设置
    private boolean showEffectMessages = true;
    private boolean showStageTransitions = true;
    private String messagePrefix = "&7[&b异常效果&7] ";
    
    // 权限设置
    private String adminBasePermission = "jwabnormaleffects.admin";
    private String useBasePermission = "jwabnormaleffects.use";
    
    // 内存缓存设置
    private int cacheCleanupIntervalMinutes = 5;
    private boolean showCacheStats = false;

    // 全局收益递减
    private boolean benefitScalingEnabled = false;
    private String benefitScalingFormula = "1";
    private BenefitScalingConfig.TargetEffect benefitScalingTarget = BenefitScalingConfig.TargetEffect.BOTH;

    public ConfigManager(JWAbnormalEffects plugin, IEffectRegistry effectRegistry) {
        this.plugin = plugin;
        this.effectRegistry = effectRegistry;
        this.effectsFolder = new File(plugin.getDataFolder(), "effects");
        loadCoreConfig();
        loadEffectDefinitions();
    }

    public void loadCoreConfig() {
        plugin.saveDefaultConfig();
        mainConfig = plugin.getConfig();
        
        // 加载调试设置
        logLevel = mainConfig.getString("debug.log-level", "INFO");
        verboseEffectLogs = mainConfig.getBoolean("debug.verbose-effect-logs", false);
        
        // 加载衰减设置
        decayCheckIntervalTicks = mainConfig.getInt("decay.check-interval-ticks", 20);
        asyncDecayProcessing = mainConfig.getBoolean("decay.async-processing", true);
        
        // 加载韧性设置
        tenacityStatName = mainConfig.getString("tenacity.stat-name", "TENACITY");
        tenacityReductionPerPoint = mainConfig.getDouble("tenacity.reduction-per-point", 0.01);
        tenacityMaxReduction = mainConfig.getDouble("tenacity.max-reduction", 0.8);
        tenacityFormula = mainConfig.getString("tenacity.formula", "linear");
        
        // 加载全局收益递减
        benefitScalingEnabled = mainConfig.getBoolean("benefit-scaling.enabled", false);
        benefitScalingFormula = mainConfig.getString("benefit-scaling.formula", "1");
        String tgt = mainConfig.getString("benefit-scaling.effect", "BOTH");
        benefitScalingTarget = BenefitScalingConfig.TargetEffect.fromString(tgt);
        
        // 加载UI设置
        showEffectMessages = mainConfig.getBoolean("display.show-effect-messages", true);
        showStageTransitions = mainConfig.getBoolean("display.show-stage-transitions", true);
        messagePrefix = mainConfig.getString("display.message-prefix", "&7[&b异常效果&7] ");
        
        // 将配置的消息前缀同步到MessageUtils
        MessageUtils.syncWithConfigManager(messagePrefix);
        
        // 加载权限设置
        adminBasePermission = mainConfig.getString("permissions.admin-base", "jwabnormaleffects.admin");
        useBasePermission = mainConfig.getString("permissions.use-base", "jwabnormaleffects.use");
        
        // 加载内存缓存设置
        cacheCleanupIntervalMinutes = mainConfig.getInt("cache.cleanup-interval-minutes", 5);
        showCacheStats = mainConfig.getBoolean("cache.show-cache-stats", false);
        
        MessageUtils.log(Level.INFO, "主配置文件加载完成");
    }

    public void loadEffectDefinitions() {
        effectRegistry.clearEffects();
        effectDefinitions.clear(); // 同时清理ConfigManager的本地缓存 

        if (!effectsFolder.exists() || !effectsFolder.isDirectory()) {
            MessageUtils.log(Level.INFO, "效果定义文件夹 'effects' 不存在，正在从插件 JAR 包中复制默认配置...");
            
            // 创建 effects 文件夹
            if (!effectsFolder.mkdirs()) {
                MessageUtils.log(Level.SEVERE, "无法创建 'effects' 文件夹。");
                return;
            }
            
            // 从 JAR 包中复制默认的 effects 文件
            copyDefaultEffectsFromJar();
        }

        // 递归遍历 effects 文件夹及其所有子文件夹，收集所有 .yml 文件
        List<File> effectFiles = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(effectsFolder.toPath())) {
            paths.filter(Files::isRegularFile)
                 .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".yml"))
                 .forEach(path -> effectFiles.add(path.toFile()));
        } catch (IOException e) {
            MessageUtils.log(Level.SEVERE, "遍历效果定义文件时发生错误: " + e.getMessage());
            e.printStackTrace();
            return;
        }

        if (effectFiles.isEmpty()) {
            MessageUtils.log(Level.INFO, "在 'effects' 文件夹及其子文件夹中没有找到任何效果定义文件 (.yml)。");
            return;
        }

        int loadedCount = 0;
        for (File effectFile : effectFiles) {
            String effectId = effectFile.getName().substring(0, effectFile.getName().length() - 4); //移除 .yml
            try {
                FileConfiguration effectConfig = YamlConfiguration.loadConfiguration(effectFile);
                // 验证 effectId 是否与文件名（不含.yml）匹配 (在 EffectDefinition 内部的 id 字段)
                String idInFile = effectConfig.getString("id");
                if (idInFile == null || !idInFile.equals(effectId)) {
                     MessageUtils.log(Level.WARNING, "文件 '" + effectFile.getName() + "' 中的 'id' ("+idInFile+") 与文件名不匹配。将使用文件名 '" + effectId + "' 作为效果ID。");
                     // 强制使用文件名作为ID，或者抛出错误，这里选择前者并警告
                     effectConfig.set("id", effectId); // 确保定义对象中的ID是正确的
                }

                EffectDefinition definition = new EffectDefinition(effectId, effectConfig);
                effectRegistry.registerEffect(definition);
                effectDefinitions.put(effectId, definition); // 添加到ConfigManager的本地缓存
                loadedCount++;
            } catch (IllegalArgumentException e) {
                MessageUtils.log(Level.SEVERE, "加载效果 '" + effectId + "' 失败 (文件: " + effectFile.getName() + "): " + e.getMessage());
            } catch (Exception e) {
                MessageUtils.log(Level.SEVERE, "加载效果文件 '" + effectFile.getName() + "' 时发生未知错误: " + e.getMessage());
                e.printStackTrace(); // 打印堆栈以供调试
            }
        }
        MessageUtils.log(Level.INFO, "成功加载了 " + loadedCount + " 个效果定义。");
    }

    /**
     * 从插件 JAR 包中复制默认的 effects 文件到插件配置目录
     */
    private void copyDefaultEffectsFromJar() {
        // 默认效果文件列表
        String[] defaultEffectFiles = {"frost.yml", "stun.yml"};
        
        int copiedCount = 0;
        for (String fileName : defaultEffectFiles) {
            try (InputStream inputStream = plugin.getResource("effects/" + fileName)) {
                if (inputStream != null) {
                    File targetFile = new File(effectsFolder, fileName);
                    Files.copy(inputStream, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    copiedCount++;
                    MessageUtils.log(Level.INFO, "已复制默认效果文件: " + fileName);
                } else {
                    MessageUtils.log(Level.WARNING, "在 JAR 包中未找到默认效果文件: " + fileName);
                }
            } catch (IOException e) {
                MessageUtils.log(Level.SEVERE, "复制默认效果文件 '" + fileName + "' 时发生错误: " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        if (copiedCount > 0) {
            MessageUtils.log(Level.INFO, "成功从 JAR 包复制了 " + copiedCount + " 个默认效果文件。");
        } else {
            MessageUtils.log(Level.WARNING, "未能从 JAR 包复制任何默认效果文件。");
        }
    }

    public void reloadConfigs() {
        MessageUtils.log(Level.INFO, "开始重载 AbnormalEffects 配置...");
        plugin.reloadConfig();
        loadCoreConfig();
        loadEffectDefinitions();
        MessageUtils.log(Level.INFO, "AbnormalEffects 配置重载完毕。");
    }


    public Collection<EffectDefinition> getAllEffectDefinitions() {
        return Collections.unmodifiableCollection(effectDefinitions.values());
    }


    public Optional<EffectDefinition> getEffectDefinition(String effectId) {
        return Optional.ofNullable(effectDefinitions.get(effectId));
    }

    // 主配置的 Getter 方法
    public String getLogLevel() {
        return logLevel;
    }
    
    public boolean isVerboseEffectLogs() {
        return verboseEffectLogs;
    }
    
    public int getDecayCheckIntervalTicks() {
        return decayCheckIntervalTicks;
    }
    
    public int getCacheCleanupIntervalMinutes() {
        return cacheCleanupIntervalMinutes;
    }
    
    public boolean isAsyncDecayProcessing() {
        return asyncDecayProcessing;
    }
    
    public String getTenacityStatName() {
        return tenacityStatName;
    }
    
    public double getTenacityReductionPerPoint() {
        return tenacityReductionPerPoint;
    }
    
    public double getTenacityMaxReduction() {
        return tenacityMaxReduction;
    }
    
    public String getTenacityFormula() {
        return tenacityFormula;
    }
    
    public boolean isShowEffectMessages() {
        return showEffectMessages;
    }
    
    public boolean isShowStageTransitions() {
        return showStageTransitions;
    }
    
    public String getMessagePrefix() {
        return messagePrefix;
    }
    
    public String getAdminBasePermission() {
        return adminBasePermission;
    }
    
    public String getUseBasePermission() {
        return useBasePermission;
    }
    
    public boolean isShowCacheStats() {
        return showCacheStats;
    }

    // ================================
    // 效果用户提示辅助方法
    // ================================

    /**
     * 获取格式化的效果显示名称
     * 优先级：效果配置中的display-name > effectId
     * 
     * @param effectId 效果ID
     * @return 格式化后的显示名称（已转换颜色代码）
     */
    public String getFormattedEffectDisplayName(String effectId) {
        if (effectId == null || effectId.isEmpty()) {
            return "未知效果";
        }

        // 从效果定义获取display-name
        Optional<EffectDefinition> optDef = getEffectDefinition(effectId);
        if (optDef.isPresent()) {
            EffectDefinition def = optDef.get();
            String displayName = def.getDisplayName();
            if (displayName != null && !displayName.isEmpty()) {
                return ColorUtil.translateColors(displayName);
            }
        }

        // 回退到effectId
        return effectId;
    }

    /**
     * 获取格式化的效果描述
     * 优先级：效果配置中的description > 空字符串
     * 
     * @param effectId 效果ID
     * @return 格式化后的描述（已转换颜色代码），如果没有配置则返回空字符串
     */
    public String getFormattedEffectDescription(String effectId) {
        if (effectId == null || effectId.isEmpty()) {
            return "";
        }

        // 从效果定义获取description
        Optional<EffectDefinition> optDef = getEffectDefinition(effectId);
        if (optDef.isPresent()) {
            EffectDefinition def = optDef.get();
            String description = def.getDescription();
            if (description != null && !description.isEmpty()) {
                return ColorUtil.translateColors(description);
            }
        }

        // 如果没有配置则返回空字符串
        return "";
    }

    /**
     * 获取效果的完整信息（显示名称 + 描述）
     * 
     * @param effectId 效果ID
     * @return 格式化的完整效果信息
     */
    public String getFormattedEffectInfo(String effectId) {
        String displayName = getFormattedEffectDisplayName(effectId);
        String description = getFormattedEffectDescription(effectId);
        
        if (description == null || description.isEmpty()) {
            return displayName;
        } else {
            return displayName + " &7- " + description;
        }
    }

    public boolean isBenefitScalingEnabled(){ return benefitScalingEnabled; }
    public String getBenefitScalingFormula(){ return benefitScalingFormula; }
    public BenefitScalingConfig.TargetEffect getBenefitScalingTarget(){ return benefitScalingTarget; }
}