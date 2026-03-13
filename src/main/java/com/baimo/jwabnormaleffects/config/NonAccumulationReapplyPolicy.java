package com.baimo.jwabnormaleffects.config;

/**
 * 非积累制效果的再次应用策略。
 */
public enum NonAccumulationReapplyPolicy {
    /**
     * 第一种情况：若效果仍在持续，则忽略本次应用；
     * 只有在效果过期后再次应用时才会重新触发技能并刷新持续时间。
     */
    IGNORE,

    /**
     * 第二种情况：若效果仍在持续，则叠加持续时间，但本次不触发技能；
     * 效果完全结束后再次应用才会重新触发技能。
     */
    STACK_DURATION,

    /**
     * 第三种情况：若效果仍在持续，则叠加持续时间并触发技能。
     */
    STACK_DURATION_TRIGGER,

    /**
     * 第四种情况：无论效果是否存在，都会覆盖剩余持续时间并立即触发技能。
     */
    OVERRIDE,

    /**
     * 第五种情况：若效果仍在持续，则覆盖剩余持续时间，但本次不触发技能。
     */
    OVERRIDE_SILENT
} 