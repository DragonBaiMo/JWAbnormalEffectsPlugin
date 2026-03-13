# MythicMobs 通用变量注入实现方法

## 概述

从 JWAbnormalEffects 项目中提取的通用 MythicMobs 变量注入机制，支持 caster/target scope 变量传递，可用于任何基于 MythicMobs 5.x+ 的插件开发。

---

## 1. 核心依赖

```xml
<dependency>
    <groupId>io.lumine</groupId>
    <artifactId>Mythic-Dist</artifactId>
    <version>5.6.1</version>
    <scope>provided</scope>
</dependency>
```

必要导入：
```java
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.core.mobs.ActiveMob;
import io.lumine.mythic.core.skills.variables.Variable;
import io.lumine.mythic.core.skills.variables.VariableType;
```

---

## 2. 通用变量注入工具类

```java
public class MythicVariableInjector {
    
    /**
     * 向指定实体注入变量到其 MythicMob 变量容器
     * @param entity 目标实体（必须是 MythicMob）
     * @param variables 要注入的变量映射
     * @param autoCleanup 是否自动清理（建议 true）
     * @param plugin 插件实例（用于调度清理任务）
     * @return 注入成功的变量键集合
     */
    public static Set<String> injectVariables(Entity entity, Map<String, Object> variables, 
                                            boolean autoCleanup, JavaPlugin plugin) {
        if (entity == null || variables == null || variables.isEmpty()) {
            return Collections.emptySet();
        }
        
        try {
            ActiveMob activeMob = MythicBukkit.inst().getMobManager().getMythicMobInstance(entity);
            if (activeMob == null) {
                return Collections.emptySet();
            }
            
            Set<String> injectedKeys = new HashSet<>();
            
            // 遍历并注入变量
            variables.forEach((key, value) -> {
                VariableType type = determineVariableType(value);
                activeMob.getVariables().put(key, Variable.ofType(type, value));
                injectedKeys.add(key);
            });
            
            // 自动清理（推荐）
            if (autoCleanup && plugin != null) {
                scheduleVariableCleanup(activeMob, injectedKeys, plugin);
            }
            
            return injectedKeys;
            
        } catch (Exception e) {
            // 记录错误但不抛出异常
            plugin.getLogger().warning("变量注入失败: " + e.getMessage());
            return Collections.emptySet();
        }
    }
    
    /**
     * 确定变量类型
     */
    private static VariableType determineVariableType(Object value) {
        if (value instanceof Integer) {
            return VariableType.INTEGER;
        } else if (value instanceof Number) {
            return VariableType.FLOAT;
        } else {
            return VariableType.STRING;
        }
    }
    
    /**
     * 调度变量清理任务（1 tick 后）
     */
    private static void scheduleVariableCleanup(ActiveMob activeMob, Set<String> keys, JavaPlugin plugin) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            keys.forEach(key -> activeMob.getVariables().remove(key));
        }, 1L);
    }
}
```

---

## 3. Caster/Target Scope 双向注入

```java
public class ScopedVariableInjector {
    
    /**
     * 同时向 caster 和 target 注入相同变量
     * 这是最常见的使用模式
     */
    public static void injectToScopes(Entity caster, Entity target, Map<String, Object> variables, JavaPlugin plugin) {
        // 注入到施法者 scope
        if (caster != null) {
            MythicVariableInjector.injectVariables(caster, variables, true, plugin);
        }
        
        // 注入到目标 scope
        if (target != null) {
            MythicVariableInjector.injectVariables(target, variables, true, plugin);
        }
    }
    
    /**
     * 根据不同 scope 注入不同变量
     */
    public static void injectScopedVariables(Entity caster, Entity target, 
                                           Map<String, Object> casterVars,
                                           Map<String, Object> targetVars, 
                                           JavaPlugin plugin) {
        // 施法者专用变量
        if (caster != null && casterVars != null && !casterVars.isEmpty()) {
            MythicVariableInjector.injectVariables(caster, casterVars, true, plugin);
        }
        
        // 目标专用变量
        if (target != null && targetVars != null && !targetVars.isEmpty()) {
            MythicVariableInjector.injectVariables(target, targetVars, true, plugin);
        }
    }
}
```

---

## 4. 通用技能释放器

```java
public class MythicSkillCaster {
    
    /**
     * 释放技能并注入变量
     * @param caster 施法者
     * @param skillName 技能名称
     * @param target 目标（可选）
     * @param variables 要注入的变量
     * @param plugin 插件实例
     * @return 是否成功
     */
    public static boolean castSkillWithVariables(Entity caster, String skillName, Entity target, 
                                               Map<String, Object> variables, JavaPlugin plugin) {
        if (caster == null || skillName == null || skillName.isEmpty()) {
            return false;
        }
        
        try {
            // 1. 变量注入
            if (variables != null && !variables.isEmpty()) {
                ScopedVariableInjector.injectToScopes(caster, target, variables, plugin);
            }
            
            // 2. 释放技能（确保在主线程）
            Runnable skillTask = () -> {
                if (target != null) {
                    // 有目标的技能释放
                    MythicBukkit.inst().getAPIHelper().castSkill(
                        caster,
                        skillName,
                        target,
                        caster.getLocation(),
                        Collections.singletonList(target),
                        Collections.emptyList(),
                        1.0f
                    );
                } else {
                    // 无目标技能释放
                    MythicBukkit.inst().getAPIHelper().castSkill(caster, skillName, 1.0f);
                }
            };
            
            if (Bukkit.isPrimaryThread()) {
                skillTask.run();
            } else {
                Bukkit.getScheduler().runTask(plugin, skillTask);
            }
            
            return true;
            
        } catch (Exception e) {
            plugin.getLogger().severe("技能释放失败: " + skillName + ", 错误: " + e.getMessage());
            return false;
        }
    }
}
```

---

## 5. 通用变量读取器

```java
public class MythicVariableReader {
    
    /**
     * 从 MythicMob 读取变量值
     */
    public static Optional<Object> readVariable(Entity entity, String variableName) {
        try {
            ActiveMob activeMob = MythicBukkit.inst().getMobManager().getMythicMobInstance(entity);
            if (activeMob != null && activeMob.getVariables().has(variableName)) {
                return Optional.of(activeMob.getVariables().get(variableName).getValue());
            }
        } catch (Exception e) {
            // 静默处理错误
        }
        return Optional.empty();
    }
    
    /**
     * 读取变量并转换为指定类型
     */
    public static <T> Optional<T> readVariable(Entity entity, String variableName, Class<T> type) {
        Optional<Object> value = readVariable(entity, variableName);
        if (value.isPresent()) {
            try {
                return Optional.of(type.cast(value.get()));
            } catch (ClassCastException e) {
                // 类型转换失败
            }
        }
        return Optional.empty();
    }
    
    /**
     * 读取变量为 double，失败返回默认值
     */
    public static double readDoubleVariable(Entity entity, String variableName, double defaultValue) {
        Optional<Object> value = readVariable(entity, variableName);
        if (value.isPresent()) {
            try {
                return Double.parseDouble(value.get().toString());
            } catch (NumberFormatException e) {
                // 解析失败
            }
        }
        return defaultValue;
    }
}
```

---

## 6. MythicMobs 属性读取器

```java
public class MythicStatReader {
    
    /**
     * 从 MythicMob 读取属性值
     */
    public static double readStat(Entity entity, String statName) {
        try {
            ActiveMob mob = MythicBukkit.inst().getMobManager().getMythicMobInstance(entity);
            if (mob == null) return 0.0;
            
            // 1. 尝试从变量容器读取（向后兼容）
            if (mob.getVariables().has(statName)) {
                try {
                    return Double.parseDouble(mob.getVariables().get(statName).toString());
                } catch (NumberFormatException ignored) {}
            }
            
            // 2. 从 StatRegistry 读取（MythicMobs 5.x+）
            try {
                StatRegistry registry = mob.getStatRegistry();
                if (registry != null) {
                    // 遍历查找匹配的属性
                    for (StatType statType : registry.getApplicableStats()) {
                        if (statType.toString().equalsIgnoreCase(statName)) {
                            return registry.get(statType);
                        }
                    }
                }
            } catch (Exception ignored) {}
            
        } catch (Exception e) {
            // 静默处理错误
        }
        
        return 0.0;
    }
}
```

---

## 7. 使用示例

### 7.1 基础变量注入

```java
// 准备变量
Map<String, Object> vars = new HashMap<>();
vars.put("damage", 100.5);
vars.put("type", "fire");
vars.put("level", 5);

// 注入到 caster 和 target
ScopedVariableInjector.injectToScopes(caster, target, vars, plugin);
```

### 7.2 技能释放 + 变量注入

```java
// 一次性完成变量注入和技能释放
Map<String, Object> skillVars = new HashMap<>();
skillVars.put("finalDamage", 250.0);
skillVars.put("effectLevel", 3);

boolean success = MythicSkillCaster.castSkillWithVariables(
    caster, "FireBlast", target, skillVars, plugin
);
```

### 7.3 变量读取

```java
// 读取变量
Optional<Object> value = MythicVariableReader.readVariable(entity, "myVariable");

// 读取为特定类型
Optional<Integer> level = MythicVariableReader.readVariable(entity, "level", Integer.class);

// 读取为 double，带默认值
double damage = MythicVariableReader.readDoubleVariable(entity, "damage", 0.0);
```

### 7.4 属性读取

```java
// 读取 MythicMobs 属性
double attackDamage = MythicStatReader.readStat(entity, "ATTACK_DAMAGE");
double customStat = MythicStatReader.readStat(entity, "MyCustomStat");
```

---

## 8. 最佳实践

### 8.1 变量命名约定

```java
// 推荐的变量命名
vars.put("finalDamage", damage);     // 驼峰命名
vars.put("effect_level", level);     // 下划线分隔
vars.put("isInstant", instant);      // 布尔值用 is 前缀
```

### 8.2 线程安全

```java
// 总是检查线程安全
if (!Bukkit.isPrimaryThread()) {
    Bukkit.getScheduler().runTask(plugin, () -> {
        // 在主线程执行 MythicMobs 操作
        MythicSkillCaster.castSkillWithVariables(caster, skill, target, vars, plugin);
    });
} else {
    // 直接执行
    MythicSkillCaster.castSkillWithVariables(caster, skill, target, vars, plugin);
}
```

### 8.3 错误处理

```java
// 使用 Optional 处理可能的空值
Optional<Object> result = MythicVariableReader.readVariable(entity, "damage");
if (result.isPresent()) {
    // 处理结果
    double damage = Double.parseDouble(result.get().toString());
} else {
    // 使用默认值或跳过
    double damage = 0.0;
}
```

### 8.4 变量清理

```java
// 手动清理变量（不推荐，建议使用自动清理）
Set<String> keys = MythicVariableInjector.injectVariables(entity, vars, false, plugin);
// ... 使用变量
// 稍后清理
keys.forEach(key -> {
    ActiveMob mob = MythicBukkit.inst().getMobManager().getMythicMobInstance(entity);
    if (mob != null) {
        mob.getVariables().remove(key);
    }
});
```

---

## 9. 核心模式总结

1. **获取 ActiveMob**: `MythicBukkit.inst().getMobManager().getMythicMobInstance(entity)`
2. **类型确定**: `Integer -> INTEGER`, `Number -> FLOAT`, `其他 -> STRING`
3. **变量注入**: `activeMob.getVariables().put(key, Variable.ofType(type, value))`
4. **自动清理**: 1 tick 后移除变量，避免污染
5. **双向注入**: 同时向 caster 和 target 注入
6. **线程安全**: 技能释放必须在主线程
7. **错误处理**: 静默处理异常，提供降级方案

这套通用实现可以直接复制到任何需要 MythicMobs 变量注入功能的项目中使用。