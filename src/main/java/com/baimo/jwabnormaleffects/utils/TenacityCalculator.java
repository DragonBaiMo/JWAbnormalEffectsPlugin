package com.baimo.jwabnormaleffects.utils;

import org.bukkit.entity.LivingEntity;

import com.baimo.jwabnormaleffects.config.ConfigManager;
import com.baimo.jwabnormaleffects.config.EffectDefinition;

/**
 * 韧性减免计算器。
 */
public final class TenacityCalculator {

    private TenacityCalculator(){}

    public static double apply(LivingEntity target, EffectDefinition def, double baseValue, ConfigManager cfg){
        if(!def.usesTenacity()) return baseValue;

        // 根据目标实体类型选用对应韧性公式
        String formula = def.resolveTenacityFormula(target);
        if(formula == null || formula.isEmpty()){
            formula = cfg.getTenacityFormula();
        }
        double stat = getStat(target, def);
        java.util.Map<String,Double> vars = new java.util.HashMap<>();
        vars.put("tenacity", stat);
        double reductionPercent = VariableResolverUtil.resolveDouble(target, formula, vars);
        reductionPercent = Math.min(reductionPercent, cfg.getTenacityMaxReduction());
        if(reductionPercent < 0) reductionPercent = 0;
        return baseValue * (1.0 - reductionPercent);
    }

    private static double getStat(LivingEntity target, EffectDefinition def){
        try{
            io.lumine.mythic.core.mobs.ActiveMob mob = io.lumine.mythic.bukkit.MythicBukkit.inst().getMobManager().getMythicMobInstance(target);
            if(mob == null) return 0d;

            // 1) 变量容器（保持向后兼容）
            if(mob.getVariables().has(def.getTenacityStatName())){
                try{
                    return Double.parseDouble(mob.getVariables().get(def.getTenacityStatName()).toString());
                }catch(NumberFormatException ignored){}
            }

            // 2) MythicMobs StatRegistry API (5.x+)
            try{
                io.lumine.mythic.core.skills.stats.StatRegistry registry = mob.getStatRegistry();
                if(registry != null){
                    for(io.lumine.mythic.core.skills.stats.StatType t : registry.getApplicableStats()){
                        if(t.toString().equalsIgnoreCase(def.getTenacityStatName())){
                            return registry.get(t);
                        }
                    }
                    return 0d;
                }
            }catch(NoSuchMethodError | NoClassDefFoundError ignored){}
        }catch(Exception ignored){}
        return 0d;
    }
} 