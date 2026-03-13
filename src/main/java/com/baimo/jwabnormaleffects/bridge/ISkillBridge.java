package com.baimo.jwabnormaleffects.bridge;

import java.util.Map;

import org.bukkit.entity.Entity;

public interface ISkillBridge {
    boolean castSkillWithVars(org.bukkit.entity.Entity caster, String skillName, org.bukkit.entity.Entity target, java.util.Map<String,Object> variables);
}