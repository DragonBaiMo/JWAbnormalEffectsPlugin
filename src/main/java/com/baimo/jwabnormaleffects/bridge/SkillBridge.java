package com.baimo.jwabnormaleffects.bridge;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;

import org.bukkit.entity.Entity;

import com.baimo.jwabnormaleffects.JWAbnormalEffects;
import com.baimo.jwabnormaleffects.utils.MessageUtils;

import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.core.skills.SkillExecutor;
import io.lumine.mythic.core.mobs.ActiveMob;
import io.lumine.mythic.core.skills.variables.Variable;
import io.lumine.mythic.core.skills.variables.VariableType;

public class SkillBridge implements ISkillBridge {
    private final JWAbnormalEffects plugin;
    private final SkillExecutor mythicSkillManager; // MythicMobs 5.x+

    public SkillBridge(JWAbnormalEffects plugin) {
        this.plugin = plugin;
        // MythicMobs 5.x API for SkillManager
        this.mythicSkillManager = MythicBukkit.inst().getSkillManager();
        if (this.mythicSkillManager == null) {
             MessageUtils.log(Level.SEVERE, "无法获取 MythicMobs SkillManager 实例！技能调用将失败。");
        }
    }

    private boolean castSkill(Entity caster, String skillName, Entity target, Map<String, String> variables) {
        if (mythicSkillManager == null) {
            MessageUtils.log(Level.WARNING, "MythicMobs SkillManager 未初始化，无法释放技能 '" + skillName + "'。");
            return false;
        }
        if (skillName == null || skillName.isEmpty()) {
            MessageUtils.log(Level.WARNING, "尝试释放一个空的技能名。施法者: " + (caster != null ? caster.getName() : "未知"));
            return false;
        }
        // 若 caster 为空，则直接跳过技能释放 —— 根据业务需求，缺失施法者时不触发技能
        if (caster == null) {
            MessageUtils.log(Level.FINE, "跳过释放技能 '" + skillName + "'，因为施法者为空");
            return false;
        }

        final Entity actualCaster = caster;

        try {
            // 在 MythicMobs 5.4.0+ 中，我们使用 BukkitAPIHelper 直接释放技能
            if (target != null) {
                // 对于带有目标的技能释放，目标应当同时作为 @Trigger 与 @Target
                Runnable task = () -> MythicBukkit.inst().getAPIHelper().castSkill(
                        actualCaster,
                        skillName,
                        target,                    // 触发者 -> 可被 @Trigger 捕捉
                        actualCaster.getLocation(),      // 技能原点
                        java.util.Collections.singletonList(target), // 实体目标 -> 可被 @Target 捕捉
                        java.util.Collections.emptyList(),           // 位置目标，此处无需
                        1.0f                                           // 技能强度
                );

                if (!plugin.getServer().isPrimaryThread()) {
                    plugin.getServer().getScheduler().runTask(plugin, task);
                } else {
                    task.run();
                }
            } else {
                // 对于没有目标的技能释放，使用施法者自身位置
                if (!plugin.getServer().isPrimaryThread()) {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        MythicBukkit.inst().getAPIHelper().castSkill(
                            actualCaster, 
                            skillName, 
                            1.0f
                        );
                    });
                } else {
                    MythicBukkit.inst().getAPIHelper().castSkill(
                        actualCaster,
                        skillName, 
                        1.0f
                    );
                }
            }
            
            // 如果有变量需要传递，我们可以通过全局变量或者其他方式处理
            // 由于 MythicMobs 5.4.0+ 可能没有直接在技能调用时传递变量的方法
            // 这里我们将跳过变量处理，如果确实需要，可能需要其他方案
            
            return true;
        } catch (Exception e) {
            MessageUtils.log(Level.SEVERE, "释放 MythicMobs 技能 '" + skillName + "' 时发生错误: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public boolean castSkillWithVars(Entity caster, String skillName, Entity target, java.util.Map<String,Object> variables) {
        try {
            // ===== 自施法技能 ID 后缀处理 =====
            boolean selfCast = caster != null && target != null && caster.equals(target);
            String actualSkillName = skillName;

            if (selfCast && skillName != null && !skillName.isEmpty() && !skillName.endsWith("_自己")) {
                String selfSkill = skillName + "_自己";
                try {
                    if (mythicSkillManager != null && mythicSkillManager.getSkill(selfSkill).isPresent()) {
                        actualSkillName = selfSkill;
                    }
                } catch (Exception ignored) {
                    // 若查询过程中出现异常，则直接使用原技能名
                }
            }

            if(variables != null && !variables.isEmpty()){
                injectVarsIntoEntity(caster, variables);
                injectVarsIntoEntity(target, variables);
            }
            // 继续正常释放技能
            return castSkill(caster, actualSkillName, target, java.util.Collections.emptyMap());
        } catch(Exception ex){
            MessageUtils.log(Level.SEVERE, "castSkillWithVars 调用失败: "+ex.getMessage());
            return false;
        }
    }

    /**
     * 尝试把变量注入到指定实体对应的 MythicMob 变量容器。
     * 若实体并非 MythicMob，则忽略。该方法线程安全，可在任意线程调用。
     */
    private void injectVarsIntoEntity(Entity entity, java.util.Map<String,Object> vars){
        if(entity == null || vars == null || vars.isEmpty()) return;
        try{
            ActiveMob am = MythicBukkit.inst().getMobManager().getMythicMobInstance(entity);
            if(am != null){
                java.util.Set<String> addedKeys = new java.util.HashSet<>();
                vars.forEach((k,v)->{
                    VariableType type;
                    if(v instanceof Integer){ type=VariableType.INTEGER; }
                    else if(v instanceof Number){ type=VariableType.FLOAT; }
                    else { type=VariableType.STRING; }
                    am.getVariables().put(k, Variable.ofType(type, v));
                    addedKeys.add(k);
                });

                // 一帧后移除，避免变量长期残留
                plugin.getServer().getScheduler().runTaskLater(plugin, ()->{
                    addedKeys.forEach(am.getVariables()::remove);
                }, 1L);
            }
        }catch(Exception ex){
            MessageUtils.log(Level.WARNING, "注入变量到实体时出错: "+ex.getMessage());
        }
    }
}