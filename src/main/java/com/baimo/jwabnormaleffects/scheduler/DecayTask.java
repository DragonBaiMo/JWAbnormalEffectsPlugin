package com.baimo.jwabnormaleffects.scheduler;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

import com.baimo.jwabnormaleffects.JWAbnormalEffects;
import com.baimo.jwabnormaleffects.engine.IEffectEngine;
import com.baimo.jwabnormaleffects.persistence.IDataStore;
import com.baimo.jwabnormaleffects.utils.MessageUtils;

/**
 * 衰减任务 - 线程安全版本
 * 只处理有活跃异常效果的实体，大幅提升性能
 * 修复了异步线程调用getEntities的问题
 */
public class DecayTask implements Runnable {
    
    private final JWAbnormalEffects plugin;
    private final IEffectEngine effectEngine;
    private final IDataStore dataStore;
    
    public DecayTask(JWAbnormalEffects plugin, IEffectEngine effectEngine, IDataStore dataStore) {
        this.plugin = plugin;
        this.effectEngine = effectEngine;
        this.dataStore = dataStore;
    }
    
    @Override
    public void run() {
        try {
            processDecayForActiveEntities();
        } catch (Exception e) {
            MessageUtils.log(Level.SEVERE, "衰减任务执行时发生错误: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 处理所有有活跃效果的实体的衰减
     * 使用两阶段处理：主线程获取实体，异步线程处理衰减
     */
    private void processDecayForActiveEntities() {
        // 获取所有有活跃效果的实体UUID
        Set<UUID> activeEntityUUIDs = dataStore.getActiveEffectEntities();
        
        if (activeEntityUUIDs.isEmpty()) {
            return; // 没有活跃效果，无需处理
        }
        
        // 阶段1：在主线程中获取实体对象
        CompletableFuture<Map<UUID, Entity>> entityFuture = getEntitiesByUUIDs(activeEntityUUIDs);
        
        // 阶段2：处理衰减（在异步线程中继续）
        entityFuture.thenAccept(entityMap -> {
            processEntitiesDecay(entityMap);
        }).exceptionally(throwable -> {
            MessageUtils.log(Level.SEVERE, "处理实体衰减时发生错误: " + throwable.getMessage());
            throwable.printStackTrace();
            return null;
        });
    }
    
    /**
     * 在主线程中获取实体对象
     * 返回 CompletableFuture 以支持异步处理
     */
    private CompletableFuture<Map<UUID, Entity>> getEntitiesByUUIDs(Set<UUID> entityUUIDs) {
        CompletableFuture<Map<UUID, Entity>> future = new CompletableFuture<>();
        
        // 确保在主线程中执行
        if (Bukkit.isPrimaryThread()) {
            future.complete(collectEntitiesOnMainThread(entityUUIDs));
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    Map<UUID, Entity> entityMap = collectEntitiesOnMainThread(entityUUIDs);
                    future.complete(entityMap);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
        }
        
        return future;
    }
    
    /**
     * 在主线程中收集实体对象
     * 只能在主线程中调用
     */
    private Map<UUID, Entity> collectEntitiesOnMainThread(Set<UUID> entityUUIDs) {
        Map<UUID, Entity> entityMap = new HashMap<>();
        
        // 遍历所有世界查找实体
        Bukkit.getWorlds().forEach(world -> {
            world.getEntities().forEach(entity -> {
                if (entityUUIDs.contains(entity.getUniqueId()) && 
                    entity instanceof LivingEntity && 
                    entity.isValid() && 
                    !entity.isDead()) {
                    entityMap.put(entity.getUniqueId(), entity);
                }
            });
        });
        
        return entityMap;
    }
    
    /**
     * 处理实体的衰减效果
     * 可以在异步线程中执行，但实际效果处理会调度到主线程
     */
    private void processEntitiesDecay(Map<UUID, Entity> entityMap) {
        int processedCount = 0;
        int validEntityCount = entityMap.size();
        
        for (Map.Entry<UUID, Entity> entry : entityMap.entrySet()) {
            UUID entityUUID = entry.getKey();
            Entity entity = entry.getValue();
            
            // 获取该实体的所有活跃效果
            Set<String> activeEffects = dataStore.getActiveEffectsForEntity(entityUUID);
            
            if (activeEffects.isEmpty()) {
                continue; // 该实体没有活跃效果，跳过
            }
            
            // 处理该实体的所有活跃效果的衰减
            for (String effectId : activeEffects) {
                // 确保在主线程中处理衰减
                if (Bukkit.isPrimaryThread()) {
                    effectEngine.processDecayForEntity(entity, effectId);
                } else {
                    // 调度到主线程执行
                    final Entity finalEntity = entity;
                    final String finalEffectId = effectId;
                    Bukkit.getScheduler().runTask(plugin, () -> 
                        effectEngine.processDecayForEntity(finalEntity, finalEffectId));
                }
                processedCount++;
            }
        }
        
        // 记录处理统计信息（仅在详细日志模式下）
        if (plugin.getConfigManager().isVerboseEffectLogs() && processedCount > 0) {
            MessageUtils.log(Level.FINEST, String.format(
                "衰减任务完成: 处理了 %d 个实体的 %d 个效果", 
                validEntityCount, processedCount));
        }
    }
}