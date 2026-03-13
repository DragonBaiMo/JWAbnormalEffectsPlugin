package com.baimo.jwabnormaleffects.config;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import com.baimo.jwabnormaleffects.utils.VariableResolverUtil;
import com.baimo.jwabnormaleffects.utils.FormulaCalculatorUtil;

public class EffectDefinition {
    private final String id;
    private final EffectType type;

    // 积累制特有字段
    private double threshold;            // 旧字段：默认阈值（仍用于兼容）
    // --- 新增：玩家/非玩家独立阈值 ---
    private double playerThreshold;
    private double nonPlayerThreshold;
    private String playerThresholdExpr = null;
    private String nonPlayerThresholdExpr = null;
    private String thresholdExpr = null;
    private double resistanceStep;       // 旧字段：默认抵抗步进（兼容）
    // --- 新增：玩家/非玩家独立抵抗步进 ---
    private double playerResistanceStep;
    private double nonPlayerResistanceStep;
    private String playerResistanceStepExpr = null;
    private String nonPlayerResistanceStepExpr = null;
    private Map<Integer, String> stages = new TreeMap<>(); // Key: 百分比, Value: 技能名称
    private String triggerSkill;
    private double decayPerInterval;
    private String decayPerIntervalExpr = null;
    private long decayIntervalTicks;
    private String resetValueAfterTriggerExpr;
    private int decayInactivitySeconds = 0; // 无活动后开始衰减的秒数

    // 非积累制特有字段
    private String effectSkill;
    private boolean usesTenacity = false;
    private NonAccumulationReapplyPolicy reapplyPolicy = NonAccumulationReapplyPolicy.IGNORE;
    private String tenacityStatName = "tenacity";
    /** 玩家专用韧性公式 */
    private String tenacityFormulaPlayer = "";
    /** 非玩家（怪物等）韧性公式 */
    private String tenacityFormulaNonPlayer = "";

    // 通用字段（未来使用）
    private String displayName;
    private String description;

    // 是否在 "ae clear all" 时保留
    private boolean persist = false;

    // 任务定义 - 一次效果应用后的延迟任务
    private List<TaskDefinition> tasks = new ArrayList<>();

    // 值表达式（支持玩家/非玩家区分）
    private String valueExpr;            // 通用基础值表达式
    private String valueExprPlayer;      // 玩家专用表达式
    private String valueExprNonPlayer;   // 非玩家专用表达式
    private String tenacityFormula;      // 韧性公式（优先使用，否则回退到旧枚举）
    private BenefitScalingConfig benefitScaling = new BenefitScalingConfig();
    private DeliverScalingConfig deliverScaling = new DeliverScalingConfig();

    // 同名效果叠加配置
    private int stackingWindowSeconds = 0; // 连续叠加的时间窗口，0 表示不启用
    private int stackingStartAt       = 1; // 重新计数时的起始值

    public EffectDefinition(String id, ConfigurationSection config) {
        this.id = id;
        String typeStr = config.getString("type", "ACCUMULATION").toUpperCase();
        try {
            this.type = EffectType.valueOf(typeStr);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("效果 '" + id + "' 的类型 '" + typeStr + "' 无效。");
        }

        // 初始化通用字段
        this.displayName = config.getString("display-name");
        this.description = config.getString("description");
        this.persist = config.getBoolean("persist", false);

        // 根据效果类型解析特定字段
        if (this.type == EffectType.ACCUMULATION) {
            parseAccumulationFields(config);
        } else {
            parseNonAccumulationFields(config);
        }

        // 解析通用配置
        parseTasks(config);
        parseValueExpressions(config);
        parseDeliverScaling(config);
        parseBenefitScaling(config);
        parseTenacityFormula(config);
    }

    // 解析积累制特有字段
    private void parseAccumulationFields(ConfigurationSection config) {
        // 解析旧格式（保持兼容）
        parseThreshold(config);
        this.resistanceStep = config.getDouble("resistance-step", 0.0);
        // 解析新玩家/非玩家阈值与抵抗步进
        parsePlayerNonPlayerThresholds(config);
        this.triggerSkill = config.getString("trigger");
        if (this.triggerSkill == null || this.triggerSkill.isEmpty()) {
            throw new IllegalArgumentException("积累制效果 '" + id + "' 必须定义 trigger 技能。");
        }

        parseStages(config);
        parseDecayPerInterval(config);
        this.decayIntervalTicks = config.getLong("decay-interval-ticks", 0);
        parseResetValueAfterTrigger(config);
        this.decayInactivitySeconds = config.getInt("decay-inactivity-seconds", 0);

        validateDecaySettings();
    }

    // 解析非积累制特有字段
    private void parseNonAccumulationFields(ConfigurationSection config) {
        this.effectSkill = config.getString("effect-skill");
        if (this.effectSkill == null || this.effectSkill.isEmpty()) {
            throw new IllegalArgumentException("非积累制效果 '" + id + "' 必须定义 effect-skill。");
        }

        parseReapplyPolicy(config);
        parseTenacitySettings(config);

        // 设置积累制字段的默认值
        this.threshold = 0;
        this.resistanceStep = 0;
        this.stages = Collections.emptyMap();
        this.triggerSkill = null;
        this.decayPerInterval = 0;
        this.decayIntervalTicks = 0;
        this.decayInactivitySeconds = 0;
        this.resetValueAfterTriggerExpr = "0";
    }

    // 解析玩家/非玩家阈值与抵抗步进（新格式）
    private void parsePlayerNonPlayerThresholds(ConfigurationSection config) {
        // 玩家
        ConfigurationSection pSec = config.getConfigurationSection("player-threshold");
        if (pSec != null) {
            parseThresholdGroup(pSec, true);
        }
        // 非玩家
        ConfigurationSection npSec = config.getConfigurationSection("non-player-threshold");
        if (npSec != null) {
            parseThresholdGroup(npSec, false);
        }

        // 若未配置新字段，则使用旧字段填充，确保不为 0
        if (playerThreshold == 0) playerThreshold = threshold;
        if (nonPlayerThreshold == 0) nonPlayerThreshold = threshold;
        if (playerResistanceStep == 0 && playerResistanceStepExpr == null) playerResistanceStep = resistanceStep;
        if (nonPlayerResistanceStep == 0 && nonPlayerResistanceStepExpr == null) nonPlayerResistanceStep = resistanceStep;
    }

    private void parseThresholdGroup(ConfigurationSection sec, boolean isPlayer) {
        // threshold-value
        String rawThr = sec.getString("threshold-value", String.valueOf(threshold)).trim();
        double thrNumeric = threshold;
        String thrExpr = null;
        if (rawThr.contains("%") || rawThr.contains("{") || FormulaCalculatorUtil.containsMathExpression(rawThr)) {
            thrExpr = rawThr;
            try {
                thrNumeric = FormulaCalculatorUtil.calculate(rawThr.replaceAll("%[^%]+%", "0"));
            } catch (Exception ignored) {}
        } else {
            try { thrNumeric = Double.parseDouble(rawThr); } catch (NumberFormatException ignored) {}
        }

        // resistance-step
        String rawRes = sec.getString("resistance-step", String.valueOf(resistanceStep)).trim();
        double resNumeric = resistanceStep;
        String resExpr = null;
        if (rawRes.contains("%") || rawRes.contains("{") || FormulaCalculatorUtil.containsMathExpression(rawRes)) {
            resExpr = rawRes;
            try {
                resNumeric = FormulaCalculatorUtil.calculate(rawRes.replaceAll("%[^%]+%", "0"));
            } catch (Exception ignored) {}
        } else {
            try { resNumeric = Double.parseDouble(rawRes);} catch (NumberFormatException ignored) {}
        }

        if (isPlayer) {
            playerThreshold = thrNumeric;
            playerThresholdExpr = thrExpr;
            playerResistanceStep = resNumeric;
            playerResistanceStepExpr = resExpr;
        } else {
            nonPlayerThreshold = thrNumeric;
            nonPlayerThresholdExpr = thrExpr;
            nonPlayerResistanceStep = resNumeric;
            nonPlayerResistanceStepExpr = resExpr;
        }
    }

    // 解析阈值
    private void parseThreshold(ConfigurationSection config) {
        String raw = config.getString("threshold", "100.0").trim();
        if (raw.contains("%") || raw.contains("{") || FormulaCalculatorUtil.containsMathExpression(raw)) {
            this.thresholdExpr = raw;
            try {
                this.threshold = FormulaCalculatorUtil.calculate(raw.replaceAll("%[^%]+%", "0"));
            } catch (Exception e) {
                this.threshold = 100.0;
            }
        } else {
            try {
                this.threshold = Double.parseDouble(raw);
            } catch (NumberFormatException e) {
                this.threshold = 100.0;
            }
        }
    }

    // 解析阶段
    private void parseStages(ConfigurationSection config) {
        ConfigurationSection stagesSection = config.getConfigurationSection("stages");
        if (stagesSection != null) {
            for (String key : stagesSection.getKeys(false)) {
                try {
                    int percentage = Integer.parseInt(key);
                    if (percentage <= 0 || percentage >= 100) {
                        throw new IllegalArgumentException("效果 '" + id + "' 的阶段百分比 '" + key + "' 必须在 (0, 100) 之间。");
                    }
                    String stageSkill = stagesSection.getString(key);
                    if (stageSkill == null || stageSkill.isEmpty()) {
                        throw new IllegalArgumentException("效果 '" + id + "' 的阶段 " + key + "% 未定义技能。");
                    }
                    this.stages.put(percentage, stageSkill);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("效果 '" + id + "' 的阶段键 '" + key + "' 不是有效的百分比数值。");
                }
            }
        }
    }

    // 解析每次衰减值
    private void parseDecayPerInterval(ConfigurationSection config) {
        String raw = config.getString("decay-per-interval", "0").trim();
        // 统一保存原始表达式，便于后续动态解析
        this.decayPerIntervalExpr = raw;

        // 若配置以百分号结尾，则视为百分比，不在此阶段计算实际数值
        if (raw.endsWith("%")) {
            String numberPart = raw.substring(0, raw.length() - 1).trim();
            try {
                double percent = Double.parseDouble(numberPart);
                // 使用当前已解析的阈值进行一个近似预计算，用于快速验证和默认值
                this.decayPerInterval = this.threshold * percent / 100.0;
            } catch (NumberFormatException e) {
                this.decayPerInterval = 0;
            }
            return;
        }

        // 若包含占位符 / 变量 或 数学表达式，则保存表达式并尝试静态计算一遍
        if (raw.contains("%") || raw.contains("{") || FormulaCalculatorUtil.containsMathExpression(raw)) {
            try {
                this.decayPerInterval = FormulaCalculatorUtil.calculate(raw.replaceAll("%[^%]+%", "0"));
            } catch (Exception e) {
                this.decayPerInterval = 0;
            }
        } else {
            try {
                this.decayPerInterval = Double.parseDouble(raw);
            } catch (NumberFormatException e) {
                this.decayPerInterval = 0;
            }
        }
    }

    // 解析触发后重置值
    private void parseResetValueAfterTrigger(ConfigurationSection config) {
        String resetStr = config.getString("reset-value-after-trigger", "0");
        if (resetStr == null || resetStr.trim().isEmpty()) {
            resetStr = "0";
        }
        this.resetValueAfterTriggerExpr = resetStr.trim();
    }

    // 验证衰减设置
    private void validateDecaySettings() {
        if (this.decayPerInterval < 0) {
            throw new IllegalArgumentException("效果 '" + id + "' 的 decay-per-interval 不能为负。");
        }
        if (this.decayIntervalTicks < 0) {
            throw new IllegalArgumentException("效果 '" + id + "' 的 decay-interval-ticks 不能为负。");
        }
        if (this.decayInactivitySeconds < 0) {
            throw new IllegalArgumentException("效果 '" + id + "' 的 decay-inactivity-seconds 不能为负。");
        }
        if (this.decayPerInterval == 0 && this.decayIntervalTicks > 0) {
            throw new IllegalArgumentException("效果 '" + id + "' 仅配置了 decay-interval-ticks，但缺少 decay-per-interval。");
        }
    }

    // 解析再次应用策略
    private void parseReapplyPolicy(ConfigurationSection config) {
        String policyStr = config.getString("reapply-policy", "IGNORE").toUpperCase();
        try {
            this.reapplyPolicy = NonAccumulationReapplyPolicy.valueOf(policyStr);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("效果 '" + id + "' 的 reapply-policy 值 '" + policyStr + "' 无效，可选值: IGNORE, STACK_DURATION, OVERRIDE");
        }
    }

    // 解析韧性设置
    private void parseTenacitySettings(ConfigurationSection config) {
        this.usesTenacity = config.getBoolean("tenacity.enabled", false);
        this.tenacityStatName = config.getString("tenacity.stat", "tenacity");
    }

    // 解析任务列表
    private void parseTasks(ConfigurationSection config) {
        if (config.isList("Task")) {
            List<?> taskList = config.getList("Task");
            if (taskList != null) {
                for (Object obj : taskList) {
                    if (obj instanceof Map) {
                        Map<?, ?> map = (Map<?, ?>) obj;
                        String taskId = map.get("id") != null ? map.get("id").toString() : "";
                        int ticks = 0;
                        Object ticksObj = map.get("ticks");
                        if (ticksObj instanceof Number) {
                            ticks = ((Number) ticksObj).intValue();
                        } else if (ticksObj != null) {
                            try {
                                ticks = Integer.parseInt(ticksObj.toString());
                            } catch (NumberFormatException e) {
                                // 忽略，稍后验证
                            }
                        }
                        String skill = map.get("skill") != null ? map.get("skill").toString() : null;

                        if (taskId.isEmpty()) {
                            throw new IllegalArgumentException("效果 '" + id + "' 的 Task 缺少 id 字段。");
                        }
                        if (ticks <= 0) {
                            throw new IllegalArgumentException("效果 '" + id + "' 的 Task '" + taskId + "' 的 ticks 必须为正整数。");
                        }
                        if (skill == null || skill.isEmpty()) {
                            throw new IllegalArgumentException("效果 '" + id + "' 的 Task '" + taskId + "' 未定义 skill 字段。");
                        }

                        tasks.add(new TaskDefinition(taskId, ticks, skill));
                    }
                }
            }
        }
    }

    // 解析值表达式
    private void parseValueExpressions(ConfigurationSection config) {
        this.valueExpr = config.getString("value-expr", "");
        this.valueExprPlayer = config.getString("player-value-expr", this.valueExpr);
        this.valueExprNonPlayer = config.getString("non-player-value-expr", config.getString("mob-value-expr", this.valueExpr));
    }

    // 解析交付缩放配置
    private void parseDeliverScaling(ConfigurationSection config) {
        ConfigurationSection deliverSec = config.getConfigurationSection("deliver-scaling");
        if (deliverSec != null) {
            this.deliverScaling = new DeliverScalingConfig(deliverSec);
        }
    }

    // 解析收益缩放配置
    private void parseBenefitScaling(ConfigurationSection config) {
        ConfigurationSection bsSec = config.getConfigurationSection("benefit-scaling");
        if (bsSec != null) {
            this.benefitScaling = new BenefitScalingConfig(bsSec);
            ConfigurationSection sameSec = bsSec.getConfigurationSection("same-count");
            if (sameSec != null) {
                this.stackingWindowSeconds = Math.max(0, sameSec.getInt("window-seconds", 0));
                this.stackingStartAt = Math.max(1, sameSec.getInt("start-at", 1));
            }
        }
    }

    // 解析韧性公式
    private void parseTenacityFormula(ConfigurationSection config) {
        ConfigurationSection tenacitySec = config.getConfigurationSection("tenacity");
        if (tenacitySec != null) {
            this.usesTenacity = tenacitySec.getBoolean("enabled", false);
            this.tenacityFormula = tenacitySec.getString("formula", "");
            this.tenacityFormulaPlayer = tenacitySec.getString("player-formula", this.tenacityFormula);
            this.tenacityFormulaNonPlayer = tenacitySec.getString("non-player-formula", tenacitySec.getString("mob-formula", this.tenacityFormula));
        } else {
            this.usesTenacity = false;
        }
    }

    // Getters
    public String getId() { return id; }
    public EffectType getType() { return type; }
    public double getThreshold() { return threshold; }
    public double getResistanceStep() { return resistanceStep; }
    public Map<Integer, String> getStages() { return Collections.unmodifiableMap(stages); }
    public String getTriggerSkill() { return triggerSkill; }
    public double getDecayPerInterval() { return decayPerInterval; }
    public long getDecayIntervalTicks() { return decayIntervalTicks; }
    public int getDecayInactivitySeconds() { return decayInactivitySeconds; }
    public String getEffectSkill() { return effectSkill; }
    public boolean usesTenacity() { return usesTenacity; }
    public NonAccumulationReapplyPolicy getReapplyPolicy() { return reapplyPolicy; }
    /**
     * 获取效果的显示名称
     * 该名称用于用户界面提示，支持颜色代码
     * @return 效果显示名称，可能为null
     */
    public String getDisplayName() { return displayName; }
    /**
     * 获取效果的描述信息
     * 该描述用于用户界面提示，支持颜色代码
     * @return 效果描述信息，可能为null
     */
    public String getDescription() { return description; }
    public boolean isDecayEnabled() { return this.type == EffectType.ACCUMULATION && this.decayPerInterval > 0; }
    public String getDecayPerIntervalExpr() { return decayPerIntervalExpr; }

    // Setters
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public void setDescription(String description) { this.description = description; }
    public void setUsesTenacity(boolean usesTenacity) { this.usesTenacity = usesTenacity; }
    public void setThreshold(double threshold) { this.threshold = threshold; }
    public void setResistanceStep(double resistanceStep) { this.resistanceStep = resistanceStep; }
    public void setTriggerSkill(String triggerSkill) { this.triggerSkill = triggerSkill; }
    public void setStages(Map<Integer, String> stages) {
        this.stages.clear();
        if (stages != null) {
            this.stages.putAll(stages);
        }
    }
    public void setDecayPerInterval(double decayPerInterval) { this.decayPerInterval = decayPerInterval; }
    public void setDecayIntervalTicks(long decayIntervalTicks) { this.decayIntervalTicks = decayIntervalTicks; }
    public void setDecayInactivitySeconds(int decayInactivitySeconds) { this.decayInactivitySeconds = decayInactivitySeconds; }
    public String getResetValueAfterTriggerExpr() { return resetValueAfterTriggerExpr; }
    public void setResetValueAfterTriggerExpr(String resetValue) { this.resetValueAfterTriggerExpr = resetValue; }
    /**
     * 计算爆发后的剩余累积值
     * 支持数学表达式、百分比和变量 {threshold}
     * @param currentThreshold 当前实体新的阈值（已包含 resistance-step 影响）
     * @return 解析后的剩余累积值，若出现异常则返回 0
     */
    public double computeResetValueAfterTrigger(double currentThreshold) {
        if (resetValueAfterTriggerExpr == null || resetValueAfterTriggerExpr.trim().isEmpty()) {
            return 0.0;
        }
        String expr = resetValueAfterTriggerExpr.trim();
        boolean isPercent = expr.endsWith("%");
        if (isPercent) {
            expr = expr.substring(0, expr.length() - 1);
        }

        double value;
        try {
            Map<String, Double> vars = new HashMap<>();
            vars.put("threshold", currentThreshold);
            value = FormulaCalculatorUtil.calculate(expr, vars);
        } catch (Exception e) {
            try {
                value = Double.parseDouble(expr);
            } catch (NumberFormatException ex) {
                value = 0.0;
            }
        }

        if (isPercent) {
            value = currentThreshold * (value / 100.0);
        }
        return Math.max(0.0, value);
    }
    public void setEffectSkill(String effectSkill) { this.effectSkill = effectSkill; }
    public List<TaskDefinition> getTasks() { return Collections.unmodifiableList(tasks); }
    public double computeBaseValue(LivingEntity ctx) { return computeBaseValue(ctx, null); }
    public double computeBaseValue(LivingEntity ctx, Map<String, Double> extVars) {
        String expr = getValueExpressionForEntity(ctx);
        if (expr == null || expr.isEmpty()) {
            return 0;
        }
        return VariableResolverUtil.resolveDouble(ctx, expr, extVars);
    }
    private String getValueExpressionForEntity(LivingEntity ctx) {
        if (ctx != null && ctx instanceof Player) {
            return valueExprPlayer != null && !valueExprPlayer.isEmpty() ? valueExprPlayer : valueExpr;
        } else if (ctx != null) {
            return valueExprNonPlayer != null && !valueExprNonPlayer.isEmpty() ? valueExprNonPlayer : valueExpr;
        } else {
            return valueExpr;
        }
    }
    public String getTenacityFormula() { return tenacityFormula; }
    public BenefitScalingConfig getBenefitScaling() { return benefitScaling; }
    public String getBaseValueExpr() { return valueExpr; }
    public String getTenacityStatName() { return tenacityStatName; }
    public String resolveTenacityFormula(LivingEntity entity) {
        if (entity != null && entity instanceof Player) {
            return tenacityFormulaPlayer != null && !tenacityFormulaPlayer.isEmpty() ? tenacityFormulaPlayer : tenacityFormula;
        } else {
            return tenacityFormulaNonPlayer != null && !tenacityFormulaNonPlayer.isEmpty() ? tenacityFormulaNonPlayer : tenacityFormula;
        }
    }
    public int getStackingWindowSeconds() { return stackingWindowSeconds; }
    public int getStackingStartAt() { return stackingStartAt; }
    public boolean isPersist() { return persist; }
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

    /**
     * 计算抵抗步进，若无新配置则回退旧字段。
     */
    public double computeResistanceStep(LivingEntity context) {
        boolean isPlayer = context instanceof org.bukkit.entity.Player;
        String expr = isPlayer ? playerResistanceStepExpr : nonPlayerResistanceStepExpr;
        double base = isPlayer ? (playerResistanceStep != 0 ? playerResistanceStep : resistanceStep)
                               : (nonPlayerResistanceStep != 0 ? nonPlayerResistanceStep : resistanceStep);
        if (expr == null) {
            return base;
        }
        double val = VariableResolverUtil.resolveDouble(context, expr, null);
        return val < 0 ? base : val;
    }


    public double computeDecayPerInterval(LivingEntity context) {
        if (decayPerIntervalExpr == null) {
            return decayPerInterval;
        }

        String expr = decayPerIntervalExpr.trim();
        boolean isPercent = expr.endsWith("%");
        if (isPercent) {
            expr = expr.substring(0, expr.length() - 1).trim();
        }

        double value;
        try {
            value = VariableResolverUtil.resolveDouble(context, expr, null);
        } catch (Exception e) {
            try {
                value = Double.parseDouble(expr);
            } catch (NumberFormatException ex) {
                value = 0;
            }
        }

        if (isPercent) {
            double currentThreshold = computeThreshold(context);
            value = currentThreshold * (value / 100.0);
        }
        return Math.max(0, value);
    }
    public DeliverScalingConfig getDeliverScaling() { return deliverScaling; }
    public double applyDeliverScaling(LivingEntity entity, double original) {
        if (deliverScaling == null || !deliverScaling.isEnabled()) return original;
        String formula = deliverScaling.getFormulaForEntity(entity);
        if (formula == null || formula.isEmpty()) return original;
        Map<String, Double> vars = new HashMap<>();
        vars.put("deliver", original);
        double factor;
        try {
            factor = VariableResolverUtil.resolveDouble(entity, formula, vars);
        } catch (Exception e) {
            factor = 1.0;
        }
        if (factor <= 0) factor = 0;
        return original * factor;
    }
    @Override
    public String toString() {
        return "EffectDef{id='" + id + "', type=" + type + ", threshold=" + threshold +
                ", resistanceStep=" + resistanceStep + ", stages=" + stages.size() +
                ", decayEnabled=" + isDecayEnabled() + "}";
    }
}