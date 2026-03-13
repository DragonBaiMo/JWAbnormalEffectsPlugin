package com.baimo.jwabnormaleffects.config;

/**
 * 描述在效果应用后延迟执行的任务
 */
public class TaskDefinition {
    private final String id;
    private final int ticks;
    private final String skill;

    public TaskDefinition(String id, int ticks, String skill) {
        this.id = id;
        this.ticks = ticks;
        this.skill = skill;
    }

    public String getId() {
        return id;
    }

    public int getTicks() {
        return ticks;
    }

    public String getSkill() {
        return skill;
    }
} 