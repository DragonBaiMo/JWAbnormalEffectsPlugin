package com.baimo.jwabnormaleffects.config;

import org.bukkit.configuration.ConfigurationSection;

public class DeliverScalingConfig {
    private boolean enabled = false;
    private String playerFormula = "";
    private String nonPlayerFormula = "";

    public DeliverScalingConfig(){}

    public DeliverScalingConfig(ConfigurationSection sec){
        if(sec == null) return;
        this.enabled = sec.getBoolean("enabled", false);
        this.playerFormula = sec.getString("player-formula", "");
        this.nonPlayerFormula = sec.getString("non-player-formula", sec.getString("mob-formula", ""));
    }

    public boolean isEnabled(){ return enabled; }
    public String getFormulaForEntity(org.bukkit.entity.LivingEntity entity){
        if(entity != null && entity instanceof org.bukkit.entity.Player){
            return playerFormula != null && !playerFormula.isEmpty() ? playerFormula : nonPlayerFormula;
        }else{
            return nonPlayerFormula;
        }
    }
} 