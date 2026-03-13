package com.baimo.jwabnormaleffects.listeners;

import java.util.logging.Level;

import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import com.baimo.jwabnormaleffects.JWAbnormalEffects;
import com.baimo.jwabnormaleffects.persistence.IDataStore;
import com.baimo.jwabnormaleffects.utils.MessageUtils;

/**
 * 实体事件监听器
 * 处理玩家离线和实体死亡事件，自动清理内存缓存数据
 */
public class EntityEventListener implements Listener {
    
    private final JWAbnormalEffects plugin;
    private final IDataStore dataStore;
    
    public EntityEventListener(JWAbnormalEffects plugin, IDataStore dataStore) {
        this.plugin = plugin;
        this.dataStore = dataStore;
    }
    
    /**
     * 玩家离线时清理其异常效果数据
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        // 清理玩家的所有异常效果数据
        dataStore.clearAllEntityData(event.getPlayer().getUniqueId());
        
        if (plugin.getConfigManager().isVerboseEffectLogs()) {
            MessageUtils.log(Level.FINE, "玩家 " + event.getPlayer().getName() + " 离线，已清理其异常效果数据");
        }
    }
    
    /**
     * 实体死亡时清理其异常效果数据
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        
        // 检查该实体是否有异常效果数据
        if (dataStore.getActiveEffectsForEntity(entity.getUniqueId()).isEmpty()) {
            return; // 没有数据，无需清理
        }
        
        // 清理死亡实体的所有异常效果数据
        dataStore.clearAllEntityData(entity.getUniqueId());
        
        if (plugin.getConfigManager().isVerboseEffectLogs()) {
            MessageUtils.log(Level.FINE, "实体 " + entity.getType() + " 死亡，已清理其异常效果数据");
        }
    }
} 