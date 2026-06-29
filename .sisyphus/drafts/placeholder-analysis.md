# 占位符解析体系深度分析报告

## 执行摘要

经过对整个占位符解析体系的代码审查，发现了 **10 个潜在问题**，其中包括 **3 个高风险问题**、**4 个中风险问题** 和 **3 个低风险问题**。

---

## 一、解析链路概览

```
配置公式 → VariableResolverUtil.resolveDouble()
    ↓
1. PAPI 占位符解析 %...% (AbnormalEffectsExpansion.parseRecursive)
    ↓
2. Mythic 变量 {VAR:...}
    ↓
3. Mythic Stats {STAT:...}
    ↓
4. 实体属性 {ATB:...}
    ↓
5. 外部变量 {deliver}, {sameCount}, {activeCount}, {tenacity}
    ↓
6. 未解析变量替换为 0
    ↓
FormulaCalculatorUtil.calculate() → 数学计算
```

---

## 二、发现的问题清单

### 🔴 高风险问题

#### 1. PAPI 占位符解析上下文缺失问题

**位置**: `VariableResolverUtil.java:44-54`

**代码**:
```java
if(resolved.contains("%")){
    Player papiPlayer = (context instanceof Player) ? (Player) context : null;
    if(papiPlayer != null){
        resolved = com.baimo.jwabnormaleffects.placeholders.AbnormalEffectsExpansion.parseRecursive(papiPlayer, resolved);
    }
    // 若无玩家上下文则跳过占位符解析，避免 NPE
    // 将仍未解析的占位符统一替换为 0，防止公式计算时报未知变量错误
    if(resolved.contains("%")){
        resolved = resolved.replaceAll("%[^%]+%", "0");
    }
}
```

**问题描述**:
- 当 `context` 是非玩家实体（如怪物）时，所有 `%...%` 占位符会被直接替换为 `0`
- 配置文件中使用的 `%mmocore_stat_intelligence%` 等占位符在非玩家实体上无法解析
- 这导致 **非玩家实体的公式计算结果不准确**

**影响范围**:
- `EffectDefinition.computeThreshold()` - 阈值计算
- `EffectDefinition.computeResistanceStep()` - 抵抗步进计算
- `EffectDefinition.computeDecayPerInterval()` - 衰减值计算
- `TenacityCalculator.apply()` - 韧性计算
- `BenefitScalingCalculator.apply()` - 收益递减计算

**建议修复**:
```java
// 对于非玩家实体，尝试保留占位符让 MythicMobs 或其他系统处理
// 或者提供非玩家专用的回退公式
```

---

#### 2. 递归解析潜在的无限循环风险

**位置**: `AbnormalEffectsExpansion.java:339-347`

**代码**:
```java
public static String parseRecursive(Player player, String text) {
    if (text == null) return "";
    String last, cur = text;
    do {
        last = cur;
        cur = PlaceholderAPI.setPlaceholders(player, last);
    } while (!cur.equals(last) && (cur.contains("%") || cur.contains("{")));
    return cur;
}
```

**问题描述**:
- `PlaceholderAPI.setPlaceholders` 可能返回包含 `%` 或 `{` 的文本（如果某些占位符无法解析）
- 虽然 `!cur.equals(last)` 可以防止完全相同的无限循环，但如果 PlaceholderAPI 返回的文本在两次调用之间交替变化，可能导致循环
- 缺少最大迭代次数限制

**建议修复**:
```java
public static String parseRecursive(Player player, String text) {
    if (text == null) return "";
    String last, cur = text;
    int maxIterations = 10; // 添加最大迭代限制
    int iterations = 0;
    do {
        last = cur;
        cur = PlaceholderAPI.setPlaceholders(player, last);
        iterations++;
    } while (!cur.equals(last) && (cur.contains("%") || cur.contains("{")) && iterations < maxIterations);
    
    if (iterations >= maxIterations) {
        MessageUtils.log(Level.WARNING, "占位符递归解析达到最大迭代次数: " + text);
    }
    return cur;
}
```

---

#### 3. StatRegistry 属性查找逻辑复杂且可能不准确

**位置**: `VariableResolverUtil.java:255-293`

**问题描述**:
- 属性查找采用三级回退策略：原始键名 → 大写 → 小写 → 模糊匹配
- 模糊匹配使用 `normalizer.apply(t.toString())` 比较，但 `StatType.toString()` 的格式不确定
- 如果存在多个属性名规范化后相同，可能返回错误的属性值
- 每次查找都需要遍历所有 `ApplicableStats`，性能开销较大

**建议**:
- 添加缓存机制
- 明确属性名匹配规则并文档化
- 考虑使用精确匹配优先，模糊匹配作为可选回退

---

### 🟡 中风险问题

#### 4. ATB 随机坐标解析逻辑问题

**位置**: `VariableResolverUtil.java:227-246`

**代码**:
```java
if(key.startsWith("L_X_") || key.startsWith("L_Y_") || key.startsWith("L_Z_")){
    char axis = key.charAt(2); // X/Y/Z
    String numStr = key.substring(4); // after 'L_X_'
    // ...
}
```

**问题描述**:
- 文档说明格式为 `{ATB:L_X_N}`，但代码中 `key.substring(4)` 获取的是 `L_X_` 后面的内容
- 例如 `{ATB:L_X_3}` → `key = "L_X_3"` → `substring(4)` → `"3"` ✓ 正确
- 但如果格式是 `{ATB:L_X3}`（无下划线），解析会出错
- `key.charAt(2)` 对于 `L_X_3` 返回的是 `X` ✓ 正确

**潜在问题**:
- 如果变量名是 `{ATB:L_X10}`（两位数），解析逻辑是正确的
- 但如果用户错误地写成 `{ATB:LX_3}`，`charAt(2)` 返回的是 `_`，这会导致 switch 不匹配

**建议**: 在文档中明确格式要求，并添加格式验证

---

#### 5. TenacityCalculator 中变量名不一致

**位置**: `TenacityCalculator.java:24-26`

**代码**:
```java
java.util.Map<String,Double> vars = new java.util.HashMap<>();
vars.put("tenacity", stat);
double reductionPercent = VariableResolverUtil.resolveDouble(target, formula, vars);
```

**对比配置文档**:
- 配置文件中使用的变量是 `{VAR:tenacity}`（Mythic 变量）
- 但代码中注入的是普通变量 `{tenacity}`
- 在 `VariableResolverUtil` 中，`{tenacity}` 会被 `GENERIC_VAR_PATTERN` 匹配，从 `extVars` 中查找

**问题**:
- 如果用户在韧性公式中使用 `{VAR:tenacity}`，它会被 Mythic 变量解析器处理，可能从 MythicMobs 获取值
- 如果用户使用 `{tenacity}`，它从 `extVars` 获取值（由 TenacityCalculator 注入）
- 这可能导致混淆：两个不同的变量来源

---

#### 6. EffectDefinition 中阈值表达式解析可能使用错误的上下文

**位置**: `EffectDefinition.java:517-527`

**代码**:
```java
public double computeThreshold(LivingEntity context) {
    boolean isPlayer = context instanceof org.bukkit.entity.Player;
    String expr = isPlayer ? playerThresholdExpr : nonPlayerThresholdExpr;
    double base = isPlayer ? (playerThreshold != 0 ? playerThreshold : threshold)
                           : (nonPlayerThreshold != 0 ? nonPlayerThreshold : threshold);
    if (expr == null) {
        return base;
    }
    double val = VariableResolverUtil.resolveDouble(context, expr, null);
    return val <= 0 ? base : val;
}
```

**问题描述**:
- 阈值表达式解析使用 `target`（被施加效果的目标）作为上下文
- 但配置注释说明"解析的是施法者的变量"
- 这与实际实现不符

**配置注释** (frost.yml:23-26):
```yaml
# =========== 数值表达式 ==========
# 解析的是施法者的变量
# 造成的 Base 值，玩家与非玩家分别使用不同公式
player-value-expr: "3 + %mmocore_stat_intelligence% * 0.1 + %player_level% * 0.05"
```

**注意**: 这似乎是 `value-expr` 的注释，但阈值计算的上下文逻辑也存在类似混淆

---

#### 7. BenefitScaling 公式变量可能未正确注入

**位置**: `BenefitScalingCalculator.java:47-51`

**代码**:
```java
java.util.Map<String,Double> vars = new java.util.HashMap<>();
vars.put("activeCount", (double) store.getActiveEffectsForEntity(target.getUniqueId()).size());
vars.put("sameCount", (double) store.getSameEffectStack(target.getUniqueId(), effectId));

double result = VariableResolverUtil.resolveDouble(target, formula, vars);
```

**问题**:
- `sameCount` 和 `activeCount` 被注入为普通变量 `{sameCount}` 和 `{activeCount}`
- 但配置文档中未明确说明这些变量的格式
- 用户可能错误地使用 `{VAR:sameCount}` 或其他格式

---

### 🟢 低风险问题

#### 8. 未解析变量的警告日志可能过于频繁

**位置**: `VariableResolverUtil.java:71-74`

**代码**:
```java
// 5.1 将仍未解析的 {xxx} 变量统一替换为 0，防止日志警告
if(resolved.contains("{")){
    MessageUtils.log(java.util.logging.Level.WARNING, "VariableResolverUtil 未解析的变量: " + resolved);
    resolved = resolved.replaceAll("\\{[^}]+}", "0");
}
```

**问题**:
- 每次解析失败都会记录 WARNING 日志
- 在高频场景（如每秒多次计算）下，这可能产生大量日志
- 建议使用 FINE 或 FINER 级别，或添加限流机制

---

#### 9. FormulaCalculatorUtil 中变量替换可能保留变量名

**位置**: `FormulaCalculatorUtil.java:172-194`

**代码**:
```java
private static String replaceVariables(String formula, Map<String, Double> variables) {
    // ...
    if (value != null) {
        matcher.appendReplacement(result, value.toString());
    } else {
        // 保持原样，可能是exp4j的变量名
        matcher.appendReplacement(result, varName);
    }
    // ...
}
```

**问题**:
- 如果变量不存在，保留原变量名让 exp4j 处理
- 但在 `VariableResolverUtil` 中，所有 `{xxx}` 已经被替换为 0 或实际值
- 这可能导致混淆：某些变量被替换，某些保留

---

#### 10. AbnormalEffectsExpansion 中 UUID 参数解析歧义

**位置**: `AbnormalEffectsExpansion.java:62-74`

**代码**:
```java
@Override
public String onPlaceholderRequest(Player player, String params) {
    if (params == null || params.split("_").length < 2) {
        return null;
    }

    String[] parts = params.split("_");
    if (parts.length >= 3) {
        return handleWithUUID(parts, params);
    }
    
    return player == null ? null : processPlaceholder(player, params);
}
```

**问题**:
- 假设占位符格式为 `%ae_action_effectId_uuid%`
- `parts[0]` = action
- `parts[1]` = effectId
- `parts[2]` = uuid
- 但某些 action 可能不需要 effectId（如全局查询），导致解析混乱
- `handleWithUUID` 方法中 `parts[2]` 被直接当作 UUID，如果格式不正确会抛出异常

---

## 三、问题分布统计

| 风险等级 | 数量 | 问题编号 |
|---------|------|---------|
| 🔴 高风险 | 3 | 1, 2, 3 |
| 🟡 中风险 | 4 | 4, 5, 6, 7 |
| 🟢 低风险 | 3 | 8, 9, 10 |

---

## 四、修复优先级建议

### 立即修复（P0）
1. **问题 #2** - 递归解析无限循环风险：添加最大迭代限制

### 高优先级（P1）
2. **问题 #1** - PAPI 占位符上下文缺失：为非玩家实体提供回退机制
3. **问题 #3** - StatRegistry 查找逻辑优化：添加缓存和更清晰的匹配规则

### 中优先级（P2）
4. **问题 #6** - 阈值计算上下文混淆：明确文档和代码的一致性
5. **问题 #5** - Tenacity 变量名一致性：统一变量来源或使用更明确的命名

### 低优先级（P3）
6. **问题 #4, 7, 8, 9, 10** - 文档完善和日志优化

---

## 五、建议的改进措施

### 1. 增强日志和调试
- 在 `VariableResolverUtil` 中添加详细的 DEBUG 日志，显示每个阶段的解析结果
- 记录每个占位符的解析成功/失败状态

### 2. 统一变量命名规范
- 明确区分 Mythic 变量 `{VAR:xxx}`、外部变量 `{xxx}` 和 PAPI 占位符 `%xxx%`
- 在文档中提供清晰的变量使用指南

### 3. 添加配置验证
- 在加载效果配置时，预解析所有公式，检查是否存在无法解析的变量
- 提供配置校验命令 `/aeffects validate`

### 4. 性能优化
- 缓存 StatRegistry 的查找结果
- 使用编译后的正则表达式 Pattern

---

## 六、文档与实际代码的差异

| 文档说明 | 代码实现 | 差异 |
|---------|---------|------|
| 阈值计算"解析的是施法者的变量" | 使用 `target` 作为上下文 | ❌ 不一致 |
| `{ATB:L_X_N}` 随机坐标 | 代码实现正确 | ✓ 一致 |
| `{VAR:tenacity}` Mythic 变量 | 代码注入 `{tenacity}` 外部变量 | ⚠️ 容易混淆 |

---

## 七、总结

占位符解析体系整体设计合理，但在以下方面存在问题：

1. **上下文管理**：PAPI 占位符对非玩家实体的支持不完善
2. **递归安全**：缺少最大迭代限制
3. **性能**：StatRegistry 查找未优化
4. **文档一致性**：部分注释与实现不符
5. **变量命名**：多个来源的变量容易混淆

建议按照优先级逐步修复这些问题，并加强文档和配置的验证机制。

---

## 八、`has` 占位符专项复盘（Oracle 复核后）

### 最高概率问题（按优先级）

1. **参数路由误判（高概率）**
   - 现有规则：`params.split("_")` 后，只要 `parts.length >= 3` 就强制按 UUID 模式处理。
   - 风险：当 `effectId` 包含下划线时（如 `ice_stun`），`parts[2]` 会被错误当作 UUID，导致直接返回 `0`。
   - 证据：`AbnormalEffectsExpansion.java` `onPlaceholderRequest` / `handleWithUUID`。

2. **`has` 语义与“是否生效”不一致（高概率）**
   - 当前 `has` 仅判断 `Map.containsKey(effectId)`，不判断非积累效果是否已过期。
   - 结果：效果过期但尚未触发清理时，`has` 仍可能返回 `1`（假阳性）。
   - 证据：`DataStore.hasEffect` vs `DataStore.isDirectEffectActive`。

3. **无玩家上下文时当前玩家模式直接失效（中高概率）**
   - `%ae_has_<effectId>%` 在 `player == null` 时返回 `null`，外部常被渲染为空或后续替换为 `0`。
   - 在公式链路中，非玩家上下文 `%...%` 会被替换为 `0`。
   - 证据：`AbnormalEffectsExpansion.onPlaceholderRequest`、`VariableResolverUtil.resolveDouble`。

4. **生命周期清理导致离线/死亡后查询为 0（设计行为，但易被误判为 Bug）**
   - 玩家离线、实体死亡都会清理缓存。
   - 若外部继续按 UUID 查询该目标，返回 `0` 属设计预期。
   - 证据：`EntityEventListener` + 内存缓存设计。

5. **返回格式预期不一致（中概率）**
   - 布尔返回值被格式化为 `"1" / "0"`，不是 `"true" / "false"`。
   - 外部条件脚本若按字符串布尔判断，容易误判“解析错误”。
   - 证据：`AbnormalEffectsExpansion.convertToString`。

### 结论

`%ae_has_...%` 的核心问题不是单点，而是**三层叠加**：

- 解析层：下划线分段路由对 `effectId` 不稳健
- 语义层：`has`（存在）与 `active`（生效）语义混用
- 上下文层：无玩家上下文时默认空/0 的降级路径

若只修一处，优先修**解析路由**；若要从用户感知上“彻底稳定”，需同时明确并统一 **has vs active** 语义。
