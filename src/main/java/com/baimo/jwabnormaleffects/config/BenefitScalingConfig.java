package com.baimo.jwabnormaleffects.config;

import org.bukkit.configuration.ConfigurationSection;

/**
 * 收益递减配置 DTO。
 */
public class BenefitScalingConfig {

    public enum TargetEffect {
        MOB, PLAYER, BOTH;
        public static TargetEffect fromString(String s){
            try{
                return TargetEffect.valueOf(s.toUpperCase());
            }catch(Exception e){
                return BOTH;
            }
        }
    }

    private boolean enabled;
    private TargetEffect effect = TargetEffect.BOTH;
    private String formula;

    public BenefitScalingConfig(){
        this.enabled = false;
        this.formula = "1"; // 默认不缩放
    }

    public BenefitScalingConfig(ConfigurationSection sec){
        this.enabled = sec.getBoolean("enabled", false);
        this.effect  = TargetEffect.fromString(sec.getString("effect", "BOTH"));
        this.formula = sec.getString("formula", "1");
    }

    // getters
    public boolean isEnabled(){ return enabled; }
    public TargetEffect getEffect(){ return effect; }
    public String getFormula(){ return formula; }
} 