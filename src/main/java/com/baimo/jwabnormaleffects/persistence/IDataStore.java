package com.baimo.jwabnormaleffects.persistence;

import java.util.Set;
import java.util.UUID;

/**
 * 数据存储接口 - 纯内存缓存版本
 * 所有数据只存储在内存中，服务器重启后丢失
 * 玩家离线时立即清理数据以提升性能
 */
public interface IDataStore {
    
    // 基础数据操作方法（使用 UUID 而不是 Entity）
    double getAccumulatedValue(UUID entityUUID, String effectId);
    void setAccumulatedValue(UUID entityUUID, String effectId, double value);
    
    double getCurrentThreshold(UUID entityUUID, String effectId, double defaultThreshold);
    void setCurrentThreshold(UUID entityUUID, String effectId, double threshold);
    
    int getLastTriggeredStage(UUID entityUUID, String effectId);
    void setLastTriggeredStage(UUID entityUUID, String effectId, int stagePercent);
    
    long getLastDecayTick(UUID entityUUID, String effectId);
    void setLastDecayTick(UUID entityUUID, String effectId, long tick);
    
    long getLastActivityTime(UUID entityUUID, String effectId);
    void setLastActivityTime(UUID entityUUID, String effectId, long time);
    
    // 数据清理方法
    void clearEntityEffectData(UUID entityUUID, String effectId);
    void clearAllEntityData(UUID entityUUID);
    
    // 内存缓存管理方法
    /**
     * 获取所有有活跃异常效果的实体UUID
     * @return 有活跃效果的实体UUID集合
     */
    Set<UUID> getActiveEffectEntities();
    
    /**
     * 获取指定实体的所有活跃效果ID
     * @param entityUUID 实体UUID
     * @return 该实体的活跃效果ID集合
     */
    Set<String> getActiveEffectsForEntity(UUID entityUUID);
    
    /**
     * 检查实体是否有指定效果
     * @param entityUUID 实体UUID
     * @param effectId 效果ID
     * @return 是否有该效果
     */
    boolean hasEffect(UUID entityUUID, String effectId);
    
    /**
     * 获取实体的效果数据
     * @param entityUUID 实体UUID
     * @param effectId 效果ID
     * @return 效果数据，如果不存在返回 null
     */
    EffectData getEffectData(UUID entityUUID, String effectId);
    
    /**
     * 设置实体的效果数据
     * @param entityUUID 实体UUID
     * @param effectId 效果ID
     * @param effectData 效果数据
     */
    void setEffectData(UUID entityUUID, String effectId, EffectData effectData);
    
    /**
     * 获取缓存中的实体总数（用于调试和监控）
     * @return 缓存中的实体数量
     */
    int getCachedEntityCount();
    
    /**
     * 获取缓存中的效果总数（用于调试和监控）
     * @return 缓存中的效果数量
     */
    int getCachedEffectCount();
    
    // -------- 非积累效果专用 --------
    /**
     * 获取非积累效果的到期时间戳。
     * @param entityUUID 实体UUID
     * @param effectId 效果ID
     * @return 到期时间（毫秒），0 表示未设置
     */
    long getExpireTime(UUID entityUUID, String effectId);
    
    /**
     * 设置非积累效果的到期时间戳。
     * @param entityUUID 实体UUID
     * @param effectId 效果ID
     * @param expireTimeMillis 到期时间（毫秒）
     */
    void setExpireTime(UUID entityUUID, String effectId, long expireTimeMillis);
    
    /**
     * 判断非积累效果是否仍处于持续状态（未过期）。
     * @param entityUUID 实体UUID
     * @param effectId 效果ID
     * @return true 表示仍在持续，false 表示未激活或已过期
     */
    boolean isDirectEffectActive(UUID entityUUID, String effectId);
    
    /**
     * 清理已过期的非积累效果数据。
     * @return 被清理的效果数量
     */
    int cleanupExpiredEffects();
    
    // -------- 新增：记录最后一次数值 --------
    double getLastFinalValue(UUID entityUUID, String effectId);
    void setLastFinalValue(UUID entityUUID, String effectId, double value);
    double getLastScaleFactor(UUID entityUUID, String effectId);
    void setLastScaleFactor(UUID entityUUID, String effectId, double value);
    double getLastBaseValue(UUID entityUUID, String effectId);
    void setLastBaseValue(UUID entityUUID, String effectId, double value);
    boolean getLastInstant(UUID entityUUID, String effectId);
    void setLastInstant(UUID entityUUID, String effectId, boolean instant);

    // --- 阶段下降通知 ---
    int getLastDecreaseNotifiedStage(UUID entityUUID, String effectId);
    void setLastDecreaseNotifiedStage(UUID entityUUID, String effectId, int stagePercent);

    // -------- 同名效果叠加 --------
    int  getSameEffectStack(UUID entityUUID, String effectId);
    void setSameEffectStack(UUID entityUUID, String effectId, int stack);
    long getSameEffectLastApplyTime(UUID entityUUID, String effectId);
    void setSameEffectLastApplyTime(UUID entityUUID, String effectId, long timeMillis);
    int  getSameEffectWindowSeconds(UUID entityUUID, String effectId);
    void setSameEffectWindowSeconds(UUID entityUUID, String effectId, int windowSeconds);

    // -------- 最近施法者 --------
    java.util.UUID getLastCasterUUID(java.util.UUID entityUUID, String effectId);
    void setLastCasterUUID(java.util.UUID entityUUID, String effectId, java.util.UUID casterUUID);
}