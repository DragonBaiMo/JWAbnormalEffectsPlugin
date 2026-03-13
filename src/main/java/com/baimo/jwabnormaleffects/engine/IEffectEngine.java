package com.baimo.jwabnormaleffects.engine;

import org.bukkit.entity.Entity;
import com.baimo.jwabnormaleffects.config.NonAccumulationReapplyPolicy;

public interface IEffectEngine {
    /**
     * 应用异常效果（使用配置文件中的再次应用策略）。
     */
    void applyEffect(Entity target, Entity caster, String effectId, double value);

    /**
     * 应用异常效果，并允许在调用时临时覆写非积累制效果的再次应用策略。
     * <p>
     * 当 {@code reapplyPolicyOverride} 为 {@code null} 时，仍按照配置文件中的策略执行。
     * </p>
     *
     * @param target                 目标实体
     * @param caster                 施法者，可为 {@code null}
     * @param effectId               效果 ID
     * @param value                  传递值（积累量或持续时间）
     * @param reapplyPolicyOverride  临时覆写的再次应用策略，若为 {@code null} 则使用默认策略
     */
    void applyEffect(Entity target, Entity caster, String effectId, double value, NonAccumulationReapplyPolicy reapplyPolicyOverride);
    void processDecayForEntity(Entity entity, String effectId);
}