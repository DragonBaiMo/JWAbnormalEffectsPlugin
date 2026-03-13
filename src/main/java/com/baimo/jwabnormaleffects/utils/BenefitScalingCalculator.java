package com.baimo.jwabnormaleffects.utils;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.entity.LivingEntity;

import com.baimo.jwabnormaleffects.config.BenefitScalingConfig;
import com.baimo.jwabnormaleffects.persistence.IDataStore;
import com.baimo.jwabnormaleffects.config.ConfigManager;

/**
 * 收益递减计算器。
 */
public final class BenefitScalingCalculator {

    private BenefitScalingCalculator(){}

    /**
     * @return 缩放系数 (0~1]，若未启用返回 1
     */
    public static double apply(LivingEntity target, String effectId, BenefitScalingConfig localCfg, IDataStore store, com.baimo.jwabnormaleffects.config.ConfigManager globalCfg){
        boolean enabled;
        BenefitScalingConfig.TargetEffect targetEffect;
        String formula;

        if(localCfg != null && localCfg.isEnabled()){
            enabled = true;
            targetEffect = localCfg.getEffect();
            formula = localCfg.getFormula();
        }else if(globalCfg!=null && globalCfg.isBenefitScalingEnabled()){
            enabled = true;
            targetEffect = globalCfg.getBenefitScalingTarget();
            formula = globalCfg.getBenefitScalingFormula();
        }else{
            return 1d;
        }

        // 对象过滤
        switch(targetEffect){
            case MOB: if(target instanceof org.bukkit.entity.Player) return 1d; break;
            case PLAYER: if(!(target instanceof org.bukkit.entity.Player)) return 1d; break;
            default: break;
        }

        java.util.Map<String,Double> vars = new java.util.HashMap<>();
        vars.put("activeCount", (double) store.getActiveEffectsForEntity(target.getUniqueId()).size());
        vars.put("sameCount", (double) store.getSameEffectStack(target.getUniqueId(), effectId));

        double result = VariableResolverUtil.resolveDouble(target, formula, vars);
        if(result<=0 || Double.isNaN(result)) return 1d;
        return result;
    }
} 