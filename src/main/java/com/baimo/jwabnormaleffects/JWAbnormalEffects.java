package com.baimo.jwabnormaleffects;

import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import com.baimo.jwabnormaleffects.bridge.ISkillBridge;
import com.baimo.jwabnormaleffects.bridge.SkillBridge;
import com.baimo.jwabnormaleffects.commands.CommandHandler;
import com.baimo.jwabnormaleffects.config.ConfigManager;
import com.baimo.jwabnormaleffects.engine.EffectEngine;
import com.baimo.jwabnormaleffects.engine.IEffectEngine;
import com.baimo.jwabnormaleffects.listeners.EntityEventListener;
import com.baimo.jwabnormaleffects.persistence.DataStore;
import com.baimo.jwabnormaleffects.persistence.IDataStore;
import com.baimo.jwabnormaleffects.placeholders.AbnormalEffectsExpansion;
import com.baimo.jwabnormaleffects.registry.EffectRegistry;
import com.baimo.jwabnormaleffects.registry.IEffectRegistry;
import com.baimo.jwabnormaleffects.scheduler.CleanupTask;
import com.baimo.jwabnormaleffects.scheduler.DecayTask;
import com.baimo.jwabnormaleffects.utils.MessageUtils;

import io.lumine.mythic.bukkit.MythicBukkit;

public class JWAbnormalEffects extends JavaPlugin {
    private IEffectRegistry effectRegistry;
    private IDataStore dataStore;
    private IEffectEngine effectEngine;
    private ISkillBridge skillBridge;
    private ConfigManager configManager;

    // 调度器任务ID
    private int decayTaskId = -1;
    private int cleanupTaskId = -1;

    @Override
    public void onEnable() {
        long startTime = System.currentTimeMillis();
        
        // 初始化消息工具类
        MessageUtils.init(this, getLogger());
        
        // 初始化配置管理器
        effectRegistry = new EffectRegistry();
        configManager = new ConfigManager(this, effectRegistry);
        
        // 确保MessageUtils与ConfigManager的前缀同步
        MessageUtils.syncWithConfigManager(configManager.getMessagePrefix());
        
        // 检查MythicMobs插件
        if (!checkDependencies()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 初始化各核心模块
        dataStore = new DataStore(this);
        skillBridge = new SkillBridge(this);
        effectEngine = new EffectEngine(this, effectRegistry, dataStore, skillBridge, configManager);

        // 注册命令
        getCommand("aeffects").setExecutor(new CommandHandler(this, effectEngine, effectRegistry, configManager));

        // 注册事件监听器
        getServer().getPluginManager().registerEvents(new EntityEventListener(this, dataStore), this);
        MessageUtils.log(Level.INFO, "已注册实体事件监听器");

        // 注册PAPI扩展
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new AbnormalEffectsExpansion(this, effectEngine, dataStore, effectRegistry).register();
            MessageUtils.log(Level.INFO, "已注册 PlaceholderAPI 扩展");
        }

        // 启动效果衰减任务
        startScheduledTasks();

        MessageUtils.log(Level.INFO, "插件初始化完成，耗时 " + (System.currentTimeMillis() - startTime) + "ms");
    }

    private boolean checkDependencies() {
        if (!getServer().getPluginManager().isPluginEnabled("MythicMobs")) {
            MessageUtils.log(Level.SEVERE, "未找到 MythicMobs 插件，AbnormalEffects 需要 MythicMobs 5.0.0+ 才能正常工作。");
            return false;
        }
        
        String mmVersion = MythicBukkit.inst().getVersion();
        MessageUtils.log(Level.INFO, "已找到 MythicMobs " + mmVersion);
        
        return true;
    }
    
    private void startScheduledTasks() {
        // 启动效果衰减任务
        int decayCheckIntervalTicks = configManager.getDecayCheckIntervalTicks();
        boolean isAsyncDecay = configManager.isAsyncDecayProcessing();
        
        MessageUtils.log(Level.INFO, "启动效果衰减任务，检查间隔: " + decayCheckIntervalTicks + " ticks" + (isAsyncDecay ? " (异步模式)" : ""));
        
        DecayTask decayTask = new DecayTask(this, effectEngine, dataStore);
        if (isAsyncDecay) {
            decayTaskId = Bukkit.getScheduler().runTaskTimerAsynchronously(this, decayTask, decayCheckIntervalTicks, decayCheckIntervalTicks).getTaskId();
        } else {
            decayTaskId = Bukkit.getScheduler().runTaskTimer(this, decayTask, decayCheckIntervalTicks, decayCheckIntervalTicks).getTaskId();
        }
        
        // 启动数据清理任务（清理无效实体数据）
        MessageUtils.log(Level.INFO, "启动数据清理任务，清理无效实体数据");
        
        // 每5分钟执行一次清理任务 (20 ticks * 60 seconds * 5 minutes)
        long cleanupIntervalTicks = 20L * 60L * 5L;
        CleanupTask cleanupTask = new CleanupTask(this, dataStore);
        cleanupTaskId = Bukkit.getScheduler().runTaskTimerAsynchronously(this, cleanupTask, cleanupIntervalTicks, cleanupIntervalTicks).getTaskId();
    }

    @Override
    public void onDisable() {
        // 停止所有任务
        if (decayTaskId != -1) {
            Bukkit.getScheduler().cancelTask(decayTaskId);
        }
        
        if (cleanupTaskId != -1) {
            Bukkit.getScheduler().cancelTask(cleanupTaskId);
        }
        
        MessageUtils.log(Level.INFO, "插件已停用");
    }

    /**
     * 重新加载插件配置
     */
    public void reloadPlugin() {
        // 先重载配置管理器
        configManager.reloadConfigs();
        
        // 后重载消息工具，确保正确同步前缀信息
        MessageUtils.reloadLangConfig();
        
        // 重新同步一次前缀，以防单独调用MessageUtils.reloadLangConfig()
        MessageUtils.syncWithConfigManager(configManager.getMessagePrefix());
        
        MessageUtils.log(Level.INFO, "插件配置已重新加载");
    }

    public IEffectEngine getEffectEngine() {
        return effectEngine;
    }

    public IEffectRegistry getEffectRegistry() {
        return effectRegistry;
    }
    
    public ConfigManager getConfigManager() {
        return configManager;
    }
    
    public IDataStore getDataStore() {
        return dataStore;
    }
    
    public ISkillBridge getSkillBridge() {
        return skillBridge;
    }
}