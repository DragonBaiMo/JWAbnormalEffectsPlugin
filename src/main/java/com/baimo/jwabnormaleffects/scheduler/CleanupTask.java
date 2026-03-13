package com.baimo.jwabnormaleffects.scheduler;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;

import com.baimo.jwabnormaleffects.JWAbnormalEffects;
import com.baimo.jwabnormaleffects.persistence.DataStore;
import com.baimo.jwabnormaleffects.persistence.IDataStore;
import com.baimo.jwabnormaleffects.utils.MessageUtils;

/**
 * 清理任务 - 线程安全版本
 * 清理无效实体的异常效果数据，释放内存
 * 修复了异步线程调用getEntities的问题
 */
public class CleanupTask implements Runnable {
    
    private final JWAbnormalEffects plugin;
    private final IDataStore dataStore;
    
    public CleanupTask(JWAbnormalEffects plugin, IDataStore dataStore) {
        this.plugin = plugin;
        this.dataStore = dataStore;
    }
    
    @Override
    public void run() {
        try {
            cleanupInvalidEntityData();
        } catch (Exception e) {
            MessageUtils.log(Level.SEVERE, "清理任务执行时发生错误: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 清理无效实体的数据
     * 使用两阶段处理：主线程收集有效实体，异步线程执行清理
     */
    private void cleanupInvalidEntityData() {
        // 获取所有有活跃效果的实体UUID
        Set<UUID> cachedEntityUUIDs = dataStore.getActiveEffectEntities();
        
        if (cachedEntityUUIDs.isEmpty()) {
            return; // 没有缓存数据，无需清理
        }
        
        // 阶段1：在主线程中收集有效的实体UUID
        CompletableFuture<Set<UUID>> validUUIDsFuture = collectValidEntityUUIDs();
        
        // 阶段2：执行清理（在异步线程中继续）
        validUUIDsFuture.thenAccept(validEntityUUIDs -> {
            performCleanup(cachedEntityUUIDs, validEntityUUIDs);
        }).exceptionally(throwable -> {
            MessageUtils.log(Level.SEVERE, "收集有效实体UUID时发生错误: " + throwable.getMessage());
            throwable.printStackTrace();
            return null;
        });
    }
    
    /**
     * 在主线程中收集当前有效的实体UUID
     * 返回 CompletableFuture 以支持异步处理
     */
    private CompletableFuture<Set<UUID>> collectValidEntityUUIDs() {
        CompletableFuture<Set<UUID>> future = new CompletableFuture<>();
        
        // 确保在主线程中执行
        if (Bukkit.isPrimaryThread()) {
            future.complete(collectValidUUIDsOnMainThread());
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    Set<UUID> validUUIDs = collectValidUUIDsOnMainThread();
                    future.complete(validUUIDs);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
        }
        
        return future;
    }
    
    /**
     * 在主线程中收集有效的实体UUID
     * 包括在线玩家和有效的生物实体
     * 只能在主线程中调用
     */
    private Set<UUID> collectValidUUIDsOnMainThread() {
        Set<UUID> validUUIDs = new HashSet<>();
        
        // 添加所有在线玩家
        Bukkit.getOnlinePlayers().forEach(player -> validUUIDs.add(player.getUniqueId()));
        
        // 添加所有有效的生物实体
        Bukkit.getWorlds().forEach(world -> {
            world.getEntities().forEach(entity -> {
                if (entity instanceof LivingEntity && entity.isValid() && !entity.isDead()) {
                    validUUIDs.add(entity.getUniqueId());
                }
            });
        });
        
        return validUUIDs;
    }
    
    /**
     * 执行数据清理
     * 可以在异步线程中执行
     */
    private void performCleanup(Set<UUID> cachedUUIDs, Set<UUID> validUUIDs) {
        // 使用 DataStore 的清理方法
        int cleanedCount = 0;
        if (dataStore instanceof DataStore) {
            DataStore concreteDataStore = (DataStore) dataStore;
            cleanedCount = concreteDataStore.cleanupInvalidEntities(validUUIDs);
        } else {
            // 如果不是具体的 DataStore 实现，手动清理
            cleanedCount = manualCleanupInvalidEntities(cachedUUIDs, validUUIDs);
        }
        
        // 记录清理统计信息
        if (cleanedCount > 0) {
            MessageUtils.log(Level.INFO, String.format("清理任务完成: 清理了 %d 个无效实体的数据", cleanedCount));
        } else if (plugin.getConfigManager().isVerboseEffectLogs()) {
            MessageUtils.log(Level.FINE, String.format("清理任务完成: 检查了 %d 个实体，无需清理", cachedUUIDs.size()));
        }
        
        // 记录内存使用情况（仅在详细日志模式下）
        if (plugin.getConfigManager().isVerboseEffectLogs()) {
            int entityCount = dataStore.getCachedEntityCount();
            int effectCount = dataStore.getCachedEffectCount();
            MessageUtils.log(Level.FINE, String.format("当前缓存状态: %d 个实体, %d 个效果", entityCount, effectCount));
        }
        
        // 额外清理已过期的非积累效果
        int expiredCount = dataStore.cleanupExpiredEffects();
        if (expiredCount > 0 && plugin.getConfigManager().isVerboseEffectLogs()) {
            MessageUtils.log(Level.FINE, String.format("清理任务额外移除 %d 个已过期的非积累效果", expiredCount));
        }
    }
    
    /**
     * 手动清理无效实体数据（备用方法）
     */
    private int manualCleanupInvalidEntities(Set<UUID> cachedUUIDs, Set<UUID> validUUIDs) {
        int cleanedCount = 0;
        
        for (UUID entityUUID : cachedUUIDs) {
            if (!validUUIDs.contains(entityUUID)) {
                dataStore.clearAllEntityData(entityUUID);
                cleanedCount++;
            }
        }
        
        return cleanedCount;
    }
} 