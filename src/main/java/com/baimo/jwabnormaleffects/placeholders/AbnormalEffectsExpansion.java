package com.baimo.jwabnormaleffects.placeholders;

import java.util.Optional;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import com.baimo.jwabnormaleffects.JWAbnormalEffects;
import com.baimo.jwabnormaleffects.config.EffectDefinition;
import com.baimo.jwabnormaleffects.engine.IEffectEngine;
import com.baimo.jwabnormaleffects.persistence.IDataStore;
import com.baimo.jwabnormaleffects.registry.IEffectRegistry;

import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;

/**
 * PlaceholderAPI 扩展 - 纯内存缓存版本
 * 提供异常效果相关的占位符
 * 
 * 支持的占位符格式：
 * - %abnormaleffects_<action>_<effectid>% - 查询当前玩家
 * - %abnormaleffects_<action>_<effectid>_<uuid>% - 查询指定UUID的实体
 */
public class AbnormalEffectsExpansion extends PlaceholderExpansion {
    
    private final JWAbnormalEffects plugin;
    private final IEffectEngine effectEngine;
    private final IDataStore dataStore;
    private final IEffectRegistry effectRegistry;
    
    public AbnormalEffectsExpansion(JWAbnormalEffects plugin, IEffectEngine effectEngine,
                                    IDataStore dataStore, IEffectRegistry effectRegistry) {
        this.plugin = plugin;
        this.effectEngine = effectEngine;
        this.dataStore = dataStore;
        this.effectRegistry = effectRegistry;
    }
    
    @Override
    public String getIdentifier() {
        return "ae";
    }
    
    @Override
    public String getAuthor() {
        return plugin.getDescription().getAuthors().toString();
    }
    
    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }
    
    @Override
    public boolean persist() {
        return true;
    }
    
    @Override
    public String onPlaceholderRequest(Player player, String params) {
        if (params == null || params.split("_").length < 2) {
            return null;
        }

        String[] parts = params.split("_");
        if (parts.length >= 3) {
            return handleWithUUID(parts, params);
        }
        
        return player == null ? null : processPlaceholder(player, params);
    }

    /**
     * 通过UUID处理占位符查询
     * 
     * @param parts 参数数组
     * @param params 完整占位符参数
     * @return 占位符结果
     */
    private String handleWithUUID(String[] parts, String params) {
        String uuidStr = parts[2];
        try {
            UUID targetUUID = UUID.fromString(uuidStr);
            
            // 先尝试直接使用UUID执行查询（性能优化）
            Object result = processPlaceholderValueByUUID(targetUUID, params);
            if (result != null) {
                return convertToString(result);
            }
            
            // 如果直接查询失败，尝试查找实体
            LivingEntity foundEntity = findEntityByUUID(targetUUID);
            if (foundEntity != null) {
                Object entityResult = processPlaceholderValue(foundEntity, params);
                return entityResult != null ? convertToString(entityResult) : "0";
            } else {
                // 找不到指定UUID的实体，记录错误并返回0
                plugin.getLogger().warning("占位符查询失败：找不到UUID为 " + uuidStr + " 的实体");
                return "0";
            }
        } catch (IllegalArgumentException e) {
            // UUID格式错误，记录错误并返回0
            plugin.getLogger().warning("占位符查询失败：UUID格式错误 - " + uuidStr);
            return "0";
        }
    }

    /**
     * 通过UUID查找LivingEntity
     * 
     * @param uuid 实体的UUID
     * @return 找到的LivingEntity，如果未找到则返回null
     */
    private LivingEntity findEntityByUUID(UUID uuid) {
        // 在线玩家优先
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            return player;
        }
        // 直接使用 Bukkit.getEntity(UUID)（高版本）
        org.bukkit.entity.Entity ent = Bukkit.getEntity(uuid);
        if (ent instanceof LivingEntity) {
            return (LivingEntity) ent;
        }

        return null;
    }
    
    /**
     * 直接通过UUID处理占位符（性能优化版本）
     * 
     * @param entityUUID 实体UUID
     * @param params 占位符参数
     * @return 处理结果的原始数据类型，如果无法处理则返回null
     */
    private Object processPlaceholderValueByUUID(UUID entityUUID, String params) {
        String[] parts = params.split("_");
        if (parts.length < 2) {
            return null;
        }
        
        String action = parts[0].toLowerCase();
        String effectId = parts[1];
        
        // 验证效果ID是否存在
        Optional<EffectDefinition> optDef = effectRegistry.getEffectDefinition(effectId);
        if (!optDef.isPresent()) {
            return (Integer) 0; // 效果不存在，返回默认值
        }
        
        EffectDefinition effectDef = optDef.get();

        switch (action) {
            // 通用占位符
            case "applyeffect":
                // 参数格式: applyeffect_<effectId>_<uuid>_<value>
                // 在此处, parts[3] 为值, 若缺省则默认为 1
                double applyValue = 1.0;
                if (parts.length >= 4) {
                    try {
                        applyValue = Double.parseDouble(parts[3]);
                    } catch (NumberFormatException ignored) {
                        // 使用默认值
                    }
                }

                // 尝试找到实体并施加效果
                LivingEntity targetEntity = findEntityByUUID(entityUUID);
                if (targetEntity != null) {
                    effectEngine.applyEffect(targetEntity, targetEntity, effectId, applyValue);
                    return (Boolean) true; // 成功返回1
                } else {
                    return (Boolean) false; // 失败返回0
                }
                
            case "has":
                return (Boolean) dataStore.hasEffect(entityUUID, effectId);
                // %ae_has_<effectId>_<uuid>%
            case "display":
                return (String) effectDef.getDisplayName();
                
            case "type":
                return (String) effectDef.getType().name().toLowerCase();
                
            case "tenacity":
                return (Boolean) effectDef.usesTenacity();
                
            case "tenacityreduction":
                // 计算当前韧性减免百分比 (0~1)。若实体离线则返回0
                LivingEntity ent = findEntityByUUID(entityUUID);
                if(ent != null && effectDef.usesTenacity()){
                    double after = com.baimo.jwabnormaleffects.utils.TenacityCalculator.apply(ent, effectDef, 1.0, plugin.getConfigManager());
                    return (Double)(1.0 - after);
                }
                return (Double)0.0;
                
            case "tenacitymax":
                return (Double) plugin.getConfigManager().getTenacityMaxReduction();
                
            // 累积效果占位符
            case "value":
                return (Double) dataStore.getAccumulatedValue(entityUUID, effectId);
                
            // 叠加后的触发阈值
            case "currentthreshold":
                LivingEntity ent2 = findEntityByUUID(entityUUID);
                return (Double) dataStore.getCurrentThreshold(entityUUID, effectId, ent2 != null ? effectDef.computeThreshold(ent2) : effectDef.computeThreshold(null));
                
            // 默认的触发阈值
            case "basethreshold":
                LivingEntity ent3 = findEntityByUUID(entityUUID);
                return (Double) effectDef.computeThreshold(ent3);
                
            case "percent":
                double accValue = dataStore.getAccumulatedValue(entityUUID, effectId);
                LivingEntity ent4 = findEntityByUUID(entityUUID);
                double thresholdValue = dataStore.getCurrentThreshold(entityUUID, effectId, ent4 != null ? effectDef.computeThreshold(ent4) : effectDef.computeThreshold(null));
                return (Double) (thresholdValue > 0 ? (accValue / thresholdValue) * 100 : 0.0);
                
            case "stage":
                return (Integer) dataStore.getLastTriggeredStage(entityUUID, effectId);
                
            case "resistance":
                LivingEntity ent5 = findEntityByUUID(entityUUID);
                return (Double) effectDef.computeResistanceStep(ent5);
                
            case "decay":
                return (Boolean) effectDef.isDecayEnabled();
                
            case "decayrate":
                return (Double) (effectDef.isDecayEnabled() ? effectDef.getDecayPerInterval() : 0.0);
                
            case "lastactivity":
                return (Long) dataStore.getLastActivityTime(entityUUID, effectId);
                
            case "timesince":
                long lastActivityTime = dataStore.getLastActivityTime(entityUUID, effectId);
                return lastActivityTime > 0 ? (Double) ((System.currentTimeMillis() - lastActivityTime) / 1000.0) : (Double) 0.0;
                
            // 非积累效果占位符
            case "basevalue":
                // 返回最近一次施加的基础值；若无记录则回退计算表达式
                double lastBase = dataStore.getLastBaseValue(entityUUID, effectId);
                if(lastBase != 0) return (Double) lastBase;
                return (Double) effectDef.computeBaseValue(null);
                
            case "instant":
                return (Boolean) dataStore.getLastInstant(entityUUID, effectId);
                
            case "timeleft":
                // 非积累效果剩余时间（秒）
                long expireTime = dataStore.getExpireTime(entityUUID, effectId);
                if (expireTime == 0) {
                    return (Double) 0.0; // 即时效果或未设置
                } else {
                    long remaining = expireTime - System.currentTimeMillis();
                    if (remaining <= 0) {
                        return (Double) 0.0; // 已过期
                    } else {
                        return (Double) (remaining / 1000.0); // 转换为秒
                    }
                }
                
            case "active":
                // 非积累效果是否仍在持续
                return (Boolean) dataStore.isDirectEffectActive(entityUUID, effectId);
                
            case "finalvalue":
                return (Double) dataStore.getLastFinalValue(entityUUID, effectId);
            case "scalefactor":
                return (Double) dataStore.getLastScaleFactor(entityUUID, effectId);
                
            default:
                return null;
        }
    }

    /**
     * 处理玩家相关的占位符
     */
    private String processPlaceholder(LivingEntity entity, String params) {
        Object result = processPlaceholderValue(entity, params);
        return result != null ? convertToString(result) : null;
    }

    /**
     * 处理实体相关的占位符，返回原始数据类型
     * 
     * @param entity 目标实体
     * @param params 占位符参数
     * @return 原始数据类型的结果
     */
    private Object processPlaceholderValue(LivingEntity entity, String params) {
        return processPlaceholderValueByUUID(entity.getUniqueId(), params);
    }

    /**
     * 将对象转换为字符串格式
     * 
     * @param value 要转换的值
     * @return 格式化后的字符串
     */
    private String convertToString(Object value) {
        if (value == null) {
            return null;
        }
        
        if (value instanceof Boolean) {
            return ((Boolean) value) ? "1" : "0";
        } else if (value instanceof Double) {
            double d = (Double) value;
            // 如果数值大于等于 60，则不带小数；否则保留两位小数
            if (d >= 60) {
                return String.valueOf((long) d);
            }
            return d == (long) d ? String.valueOf((long) d) : String.format("%.2f", d);
        } else if (value instanceof Float) {
            float f = (Float) value;
            // 如果数值大于等于 60，则不带小数；否则保留两位小数
            if (f >= 60f) {
                return String.valueOf((long) f);
            }
            return f == (long) f ? String.valueOf((long) f) : String.format("%.2f", f);
        } else if (value instanceof Integer || value instanceof Long) {
            return String.valueOf(value);
        } else {
            return value.toString();
        }
    }
    /**
     * 递归解析占位符，直到文本不再变化。
     * @param player 玩家，可为 null 表示控制台
     * @param text   原始包含 %...% 的文本
     * @return 最终解析结果
     */
    public static String parseRecursive(Player player, String text) {
        if (text == null) return "";
        String last, cur = text;
        do {
            last = cur;
            cur = PlaceholderAPI.setPlaceholders(player, last);
        } while (!cur.equals(last) && (cur.contains("%") || cur.contains("{")));
        return cur;
    }
}