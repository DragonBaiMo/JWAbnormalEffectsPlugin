package com.baimo.jwabnormaleffects.persistence;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import com.baimo.jwabnormaleffects.JWAbnormalEffects;
import com.baimo.jwabnormaleffects.utils.MessageUtils;

/**
 * 数据存储实现 - 纯内存缓存版本
 * 所有数据只存储在内存中，性能优先
 * 玩家离线时立即清理数据
 */
public class DataStore implements IDataStore {

    private final JWAbnormalEffects plugin;
    
    // 内存缓存：Key = 实体UUID, Value = 该实体的所有效果数据
    private final Map<UUID, Map<String, EffectData>> entityEffectsData = new ConcurrentHashMap<>();

    public DataStore(JWAbnormalEffects plugin) {
        this.plugin = plugin;
        MessageUtils.log(Level.INFO, "数据存储初始化完成 - 使用纯内存缓存模式");
    }

    @Override
    public double getAccumulatedValue(UUID entityUUID, String effectId) {
        EffectData data = getEffectData(entityUUID, effectId);
        return data != null ? data.getAccumulatedValue() : 0.0;
    }

    @Override
    public void setAccumulatedValue(UUID entityUUID, String effectId, double value) {
        if (value <= 0) {
            // 如果值为0或负数，清除该效果
            clearEntityEffectData(entityUUID, effectId);
            return;
        }
        
        EffectData data = getOrCreateEffectData(entityUUID, effectId);
        data.setAccumulatedValue(value);
        
        if (plugin.getConfigManager().isVerboseEffectLogs()) {
            MessageUtils.log(Level.FINEST, "设置实体 " + entityUUID + " 效果 " + effectId + " 累积值: " + value);
        }
    }

    @Override
    public double getCurrentThreshold(UUID entityUUID, String effectId, double defaultThreshold) {
        EffectData data = getEffectData(entityUUID, effectId);
        if (data != null) {
            double threshold = data.getCurrentThreshold();
            return threshold > 0 ? threshold : defaultThreshold;
        }
        return defaultThreshold;
    }

    @Override
    public void setCurrentThreshold(UUID entityUUID, String effectId, double threshold) {
        EffectData data = getOrCreateEffectData(entityUUID, effectId);
        data.setCurrentThreshold(threshold);
    }

    @Override
    public int getLastTriggeredStage(UUID entityUUID, String effectId) {
        EffectData data = getEffectData(entityUUID, effectId);
        return data != null ? data.getLastTriggeredStage() : 0;
    }

    @Override
    public void setLastTriggeredStage(UUID entityUUID, String effectId, int stagePercent) {
        EffectData data = getOrCreateEffectData(entityUUID, effectId);
        data.setLastTriggeredStage(stagePercent);
    }

    @Override
    public long getLastDecayTick(UUID entityUUID, String effectId) {
        EffectData data = getEffectData(entityUUID, effectId);
        return data != null ? data.getLastDecayTick() : 0L;
    }

    @Override
    public void setLastDecayTick(UUID entityUUID, String effectId, long tick) {
        EffectData data = getOrCreateEffectData(entityUUID, effectId);
        data.setLastDecayTick(tick);
    }

    @Override
    public long getLastActivityTime(UUID entityUUID, String effectId) {
        EffectData data = getEffectData(entityUUID, effectId);
        return data != null ? data.getLastActivityTime() : 0L;
    }

    @Override
    public void setLastActivityTime(UUID entityUUID, String effectId, long time) {
        EffectData data = getOrCreateEffectData(entityUUID, effectId);
        data.setLastActivityTime(time);
    }

    @Override
    public void clearEntityEffectData(UUID entityUUID, String effectId) {
        Map<String, EffectData> entityEffects = entityEffectsData.get(entityUUID);
        if (entityEffects != null) {
            entityEffects.remove(effectId);
            // 如果该实体没有任何效果了，移除整个实体记录
            if (entityEffects.isEmpty()) {
                entityEffectsData.remove(entityUUID);
            }
            
            if (plugin.getConfigManager().isVerboseEffectLogs()) {
                MessageUtils.log(Level.FINE, "已清除实体 " + entityUUID + " 的效果 " + effectId + " 数据");
            }
        }
    }

    @Override
    public void clearAllEntityData(UUID entityUUID) {
        Map<String, EffectData> removed = entityEffectsData.remove(entityUUID);
        if (removed != null && !removed.isEmpty()) {
            MessageUtils.log(Level.FINE, "已清除实体 " + entityUUID + " 的所有效果数据 (" + removed.size() + " 个效果)");
        }
    }

    @Override
    public Set<UUID> getActiveEffectEntities() {
        return new HashSet<>(entityEffectsData.keySet());
    }

    @Override
    public Set<String> getActiveEffectsForEntity(UUID entityUUID) {
        Map<String, EffectData> entityEffects = entityEffectsData.get(entityUUID);
        if (entityEffects == null) {
            return new HashSet<>();
        }
        
        // 先清理过期的效果
        long now = System.currentTimeMillis();
        Set<String> toRemove = new HashSet<>();
        for (Map.Entry<String, EffectData> entry : entityEffects.entrySet()) {
            EffectData data = entry.getValue();
            if (data == null) continue;
            // 过期的非积累效果
            if (data.getExpireTime() > 0 && now >= data.getExpireTime()) {
                toRemove.add(entry.getKey());
            }
            // 累积值<=0 且无到期时间的空数据
            else if (data.isEmpty()) {
                toRemove.add(entry.getKey());
            }
        }
        
        // 移除过期的效果
        for (String effectId : toRemove) {
            entityEffects.remove(effectId);
        }
        
        // 如果该实体没有任何效果了，移除整个实体记录
        if (entityEffects.isEmpty()) {
            entityEffectsData.remove(entityUUID);
        }
        
        return new HashSet<>(entityEffects.keySet());
    }

    @Override
    public boolean hasEffect(UUID entityUUID, String effectId) {
        Map<String, EffectData> entityEffects = entityEffectsData.get(entityUUID);
        return entityEffects != null && entityEffects.containsKey(effectId);
    }

    @Override
    public EffectData getEffectData(UUID entityUUID, String effectId) {
        Map<String, EffectData> entityEffects = entityEffectsData.get(entityUUID);
        return entityEffects != null ? entityEffects.get(effectId) : null;
    }

    @Override
    public void setEffectData(UUID entityUUID, String effectId, EffectData effectData) {
        if (effectData == null || effectData.isEmpty()) {
            clearEntityEffectData(entityUUID, effectId);
            return;
        }
        
        entityEffectsData.computeIfAbsent(entityUUID, k -> new ConcurrentHashMap<>()).put(effectId, effectData);
    }

    @Override
    public int getCachedEntityCount() {
        return entityEffectsData.size();
    }

    @Override
    public int getCachedEffectCount() {
        return entityEffectsData.values().stream()
                .mapToInt(Map::size)
                .sum();
    }
    
    /**
     * 获取或创建效果数据
     */
    private EffectData getOrCreateEffectData(UUID entityUUID, String effectId) {
        return entityEffectsData
                .computeIfAbsent(entityUUID, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(effectId, k -> new EffectData());
    }
    
    /**
     * 清理无效实体的数据（用于定期清理任务）
     * @param validEntityUUIDs 当前有效的实体UUID集合
     * @return 清理的实体数量
     */
    public int cleanupInvalidEntities(Set<UUID> validEntityUUIDs) {
        Set<UUID> toRemove = new HashSet<>();
        
        for (UUID entityUUID : entityEffectsData.keySet()) {
            if (!validEntityUUIDs.contains(entityUUID)) {
                toRemove.add(entityUUID);
            }
        }
        
        for (UUID entityUUID : toRemove) {
            clearAllEntityData(entityUUID);
        }
        
        if (!toRemove.isEmpty()) {
            MessageUtils.log(Level.FINE, "清理了 " + toRemove.size() + " 个无效实体的数据");
        }
        
        return toRemove.size();
    }

    // ===== 非积累效果到期时间处理 =====
    @Override
    public long getExpireTime(UUID entityUUID, String effectId) {
        EffectData data = getEffectData(entityUUID, effectId);
        return data != null ? data.getExpireTime() : 0L;
    }

    @Override
    public void setExpireTime(UUID entityUUID, String effectId, long expireTimeMillis) {
        EffectData data = getOrCreateEffectData(entityUUID, effectId);
        data.setExpireTime(expireTimeMillis);
    }

    @Override
    public boolean isDirectEffectActive(UUID entityUUID, String effectId) {
        EffectData data = getEffectData(entityUUID, effectId);
        if (data == null) return false;
        long expire = data.getExpireTime();
        
        // 如果是即时效果（expire == 0），应该立即清理并返回false
        if (expire == 0) {
            clearEntityEffectData(entityUUID, effectId);
            return false;
        }
        
        // 检查是否过期
        if (System.currentTimeMillis() >= expire) {
            // 已过期，立即清理
            clearEntityEffectData(entityUUID, effectId);
            return false;
        }
        
        // 仍在持续中
        return true;
    }

    // ===== 清理已过期的非积累效果 =====
    @Override
    public int cleanupExpiredEffects() {
        long now = System.currentTimeMillis();
        int removed = 0;
        Set<UUID> entityKeys = new HashSet<>(entityEffectsData.keySet());
        for (UUID uuid : entityKeys) {
            Map<String, EffectData> effects = entityEffectsData.get(uuid);
            if (effects == null) continue;
            Set<String> effectKeys = new HashSet<>(effects.keySet());
            for (String effectId : effectKeys) {
                EffectData data = effects.get(effectId);
                if (data != null && data.getExpireTime() > 0 && now >= data.getExpireTime()) {
                    effects.remove(effectId);
                    removed++;
                }
            }
            if (effects.isEmpty()) {
                entityEffectsData.remove(uuid);
            }
        }
        if (removed > 0 && plugin.getConfigManager().isVerboseEffectLogs()) {
            MessageUtils.log(Level.FINE, "已清理 " + removed + " 个过期的非积累效果缓存");
        }
        return removed;
    }

    @Override
    public double getLastFinalValue(UUID entityUUID, String effectId) {
        EffectData d = getEffectData(entityUUID, effectId);
        return d != null ? d.getLastFinalValue() : 0.0;
    }

    @Override
    public void setLastFinalValue(UUID entityUUID, String effectId, double value) {
        EffectData d = getOrCreateEffectData(entityUUID, effectId);
        d.setLastFinalValue(value);
    }

    @Override
    public double getLastScaleFactor(UUID entityUUID, String effectId) {
        EffectData d = getEffectData(entityUUID, effectId);
        return d != null ? d.getLastScaleFactor() : 1.0;
    }

    @Override
    public void setLastScaleFactor(UUID entityUUID, String effectId, double value) {
        EffectData d = getOrCreateEffectData(entityUUID, effectId);
        d.setLastScaleFactor(value);
    }

    // === 新增 baseValue/instant ===
    @Override
    public double getLastBaseValue(UUID entityUUID, String effectId) {
        EffectData d = getEffectData(entityUUID, effectId);
        return d != null ? d.getLastBaseValue() : 0.0;
    }

    @Override
    public void setLastBaseValue(UUID entityUUID, String effectId, double value) {
        EffectData d = getOrCreateEffectData(entityUUID, effectId);
        d.setLastBaseValue(value);
    }

    @Override
    public boolean getLastInstant(UUID entityUUID, String effectId) {
        EffectData data = getEffectData(entityUUID, effectId);
        return data != null && data.isLastInstant();
    }

    @Override
    public void setLastInstant(UUID entityUUID, String effectId, boolean instant) {
        EffectData data = getOrCreateEffectData(entityUUID, effectId);
        data.setLastInstant(instant);
    }

    // ===== 阶段下降通知 =====
    @Override
    public int getLastDecreaseNotifiedStage(UUID entityUUID, String effectId) {
        EffectData data = getEffectData(entityUUID, effectId);
        return data != null ? data.getLastDecreaseNotifiedStage() : -1;
    }

    @Override
    public void setLastDecreaseNotifiedStage(UUID entityUUID, String effectId, int stagePercent) {
        EffectData data = getOrCreateEffectData(entityUUID, effectId);
        data.setLastDecreaseNotifiedStage(stagePercent);
    }

    // ====== 同名效果叠加 ======
    @Override
    public int getSameEffectStack(UUID entityUUID, String effectId) {
        EffectData data = getEffectData(entityUUID, effectId);
        if (data == null) return 0;
        int stack = data.getSameEffectStack();
        int window = data.getSameEffectWindowSeconds();
        if (stack == 0 || window <= 0) return stack;
        long now = System.currentTimeMillis();
        if (now - data.getSameEffectLastApply() > window * 1000L) {
            data.setSameEffectStack(0);
            return 0;
        }
        return stack;
    }

    @Override
    public void setSameEffectStack(UUID entityUUID, String effectId, int stack) {
        EffectData data = getOrCreateEffectData(entityUUID, effectId);
        data.setSameEffectStack(stack);
    }

    @Override
    public long getSameEffectLastApplyTime(UUID entityUUID, String effectId) {
        EffectData data = getEffectData(entityUUID, effectId);
        return data != null ? data.getSameEffectLastApply() : 0L;
    }

    @Override
    public void setSameEffectLastApplyTime(UUID entityUUID, String effectId, long timeMillis) {
        EffectData data = getOrCreateEffectData(entityUUID, effectId);
        data.setSameEffectLastApply(timeMillis);
    }

    @Override
    public int getSameEffectWindowSeconds(UUID entityUUID, String effectId) {
        EffectData data = getEffectData(entityUUID, effectId);
        return data != null ? data.getSameEffectWindowSeconds() : 0;
    }

    @Override
    public void setSameEffectWindowSeconds(UUID entityUUID, String effectId, int windowSeconds) {
        EffectData data = getOrCreateEffectData(entityUUID, effectId);
        data.setSameEffectWindowSeconds(windowSeconds);
    }

    @Override
    public java.util.UUID getLastCasterUUID(java.util.UUID entityUUID, String effectId) {
        EffectData data = getEffectData(entityUUID, effectId);
        // 调试日志移除，保留核心功能
        return data != null ? data.getLastCasterUUID() : null;
    }

    @Override
    public void setLastCasterUUID(java.util.UUID entityUUID, String effectId, java.util.UUID casterUUID) {
        EffectData data = getOrCreateEffectData(entityUUID, effectId);
        data.setLastCasterUUID(casterUUID);
        // 调试日志移除，保留核心功能
    }
}