package com.baimo.jwabnormaleffects.utils;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.core.mobs.ActiveMob;
import me.clip.placeholderapi.PlaceholderAPI;

/**
 * 统一解析包含 PlaceholderAPI 占位符和 MythicMobs 变量的表达式，
 * 并调用 {@link FormulaCalculatorUtil} 计算数值结果。
 * <p>格式规则：
 * 1. PAPI 占位符写成 %xxx%
 * 2. Mythic 变量写成 {VAR:someVar}
 * 3. 运行时注入的额外变量可通过 extVars 传入，写法 {someExt}
 */
public final class VariableResolverUtil {

    private static final Pattern MYTHIC_VAR_PATTERN = Pattern.compile("\\{VAR:([^}]+)}");
    private static final Pattern MYTHIC_STAT_PATTERN = Pattern.compile("\\{STAT:([^}]+)}");
    private static final Pattern GENERIC_VAR_PATTERN = Pattern.compile("\\{([^}]+)}");
    private static final Pattern ATB_VAR_PATTERN = Pattern.compile("\\{ATB:([^}]+)}");

    private VariableResolverUtil() {}

    /**
     * 解析并求值。
     * @param context 实体上下文，可为 null
     * @param formula 包含占位符/变量/数学表达式的字符串
     * @param extVars 额外变量映射，可为 null
     * @return 计算结果，若解析失败返回 0
     */
    public static double resolveDouble(@Nullable LivingEntity context, String formula, @Nullable Map<String, Double> extVars){
        if(formula == null || formula.trim().isEmpty()) return 0d;

        // 1. PAPI 占位符解析（仅当有玩家上下文）
        String resolved = formula;
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

        // 2. Mythic 变量解析 {VAR:name}
        resolved = replaceMythicVars(context, resolved);

        // 3. Mythic Stats 解析 {STAT:name}
        resolved = replaceStatVars(context, resolved);

        // 4. 属性变量解析 {ATB:xxx}
        resolved = replaceAttributeVars(context, resolved);

        // 5. 额外变量 {foo} 替换为 extVars 中的值
        if(extVars != null && !extVars.isEmpty()){
            resolved = replaceGenericVars(resolved, extVars);
        }

        // 5.1 将仍未解析的 {xxx} 变量统一替换为 0，防止日志警告
        if(resolved.contains("{")){
            MessageUtils.log(java.util.logging.Level.WARNING, "VariableResolverUtil 未解析的变量: " + resolved);
            resolved = resolved.replaceAll("\\{[^}]+}", "0");
        }

        // 6. 调用公式计算器
        try{
            return FormulaCalculatorUtil.calculate(resolved);
        }catch(Exception e){
            MessageUtils.log(java.util.logging.Level.WARNING, "VariableResolverUtil 无法计算公式 " + formula + ": " + e.getMessage());
            return 0d;
        }
    }


    private static String replaceMythicVars(@Nullable LivingEntity ctx, String text){
        if(!text.contains("{VAR:")) return text;
        Matcher m = MYTHIC_VAR_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while(m.find()){
            String varName = m.group(1);
            String replacement = "0";
            if(ctx != null){
                ActiveMob am = MythicBukkit.inst().getMobManager().getMythicMobInstance(ctx);
                if(am != null && am.getVariables().has(varName)){
                    replacement = am.getVariables().get(varName).toString();
                }
            }
            m.appendReplacement(sb, replacement);
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String replaceStatVars(@Nullable LivingEntity ctx, String text){
        if(!text.contains("{STAT:")) return text;
        Matcher m = MYTHIC_STAT_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while(m.find()){
            String statName = m.group(1);
            String replacement = "0";
            if(ctx != null){
                try{
                    ActiveMob mob = MythicBukkit.inst().getMobManager().getMythicMobInstance(ctx);
                    if(mob != null){
                        io.lumine.mythic.core.skills.stats.StatRegistry reg = null;
                        try{
                            reg = mob.getStatRegistry();
                        }catch(NoSuchMethodError | NoClassDefFoundError ignored){}
                        if(reg != null){
                            // 优先通过名称解析 StatType
                            replacement = resolveStatFromRegistry(reg, statName);
                        } else {
                            MessageUtils.log(java.util.logging.Level.FINE, "StatRegistry 为空，可能是旧版本 MythicMobs 或实体未配置属性");
                        }
                    }
                }catch(Exception e){
                    MessageUtils.log(java.util.logging.Level.FINE, "解析 STAT:" + statName + " 时发生异常: " + e.getMessage());
                }
            }
            m.appendReplacement(sb, replacement);
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String replaceGenericVars(String text, Map<String, Double> vars){
        Matcher m = GENERIC_VAR_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while(m.find()){
            String key = m.group(1);
            if(vars.containsKey(key)){
                m.appendReplacement(sb, vars.get(key).toString());
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String replaceAttributeVars(@Nullable LivingEntity ctx, String text){
        if(!text.contains("{ATB:")) return text;
        Matcher m = ATB_VAR_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        java.util.Random rand = new java.util.Random();
        while(m.find()){
            String raw = m.group(1);
            String key = raw.toUpperCase(java.util.Locale.ROOT);
            String replacement = "0";
            if(ctx != null){
                switch(key){
                    // ==== 基础属性 ====
                    case "DAMAGE":
                    case "ATTACK_DAMAGE": // 向后兼容
                        {
                            org.bukkit.attribute.AttributeInstance inst = ctx.getAttribute(org.bukkit.attribute.Attribute.GENERIC_ATTACK_DAMAGE);
                            if(inst != null){
                                replacement = String.valueOf(inst.getValue());
                            }
                        }
                        break;
                    case "LEVEL":
                        if(ctx instanceof org.bukkit.entity.Player){
                            replacement = String.valueOf(((org.bukkit.entity.Player) ctx).getLevel());
                        } else {
                            replacement = "0";
                        }
                        break;
                    case "HP":
                        replacement = String.valueOf((int) ctx.getHealth());
                        break;
                    case "THP":
                        replacement = String.valueOf(ctx.getHealth());
                        break;
                    case "MHP":
                        {
                            org.bukkit.attribute.AttributeInstance maxHp = ctx.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
                            if(maxHp != null){
                                replacement = String.valueOf((int) maxHp.getValue());
                            }
                        }
                        break;
                    case "PHP":
                        {
                            org.bukkit.attribute.AttributeInstance maxHp = ctx.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
                            double max = maxHp != null ? maxHp.getValue() : 0;
                            double pct = max > 0 ? ctx.getHealth() / max : 0;
                            replacement = String.valueOf(pct);
                        }
                        break;
                    // ==== 坐标 ====
                    case "L_X":
                        replacement = String.valueOf(ctx.getLocation().getBlockX());
                        break;
                    case "L_Y":
                        replacement = String.valueOf(ctx.getLocation().getBlockY());
                        break;
                    case "L_Z":
                        replacement = String.valueOf(ctx.getLocation().getBlockZ());
                        break;
                    case "L_X_DOUBLE":
                        replacement = String.valueOf(ctx.getLocation().getX());
                        break;
                    case "L_Y_DOUBLE":
                        replacement = String.valueOf(ctx.getLocation().getY());
                        break;
                    case "L_Z_DOUBLE":
                        replacement = String.valueOf(ctx.getLocation().getZ());
                        break;
                    case "L_YAW":
                        replacement = String.valueOf(Math.round(ctx.getLocation().getYaw()));
                        break;
                    case "L_PITCH":
                        replacement = String.valueOf(Math.round(ctx.getLocation().getPitch()));
                        break;
                    default:
                        // 处理随机偏差坐标 L_X_# / L_Y_# / L_Z_#
                        if(key.startsWith("L_X_") || key.startsWith("L_Y_") || key.startsWith("L_Z_")){
                            char axis = key.charAt(2); // X/Y/Z
                            String numStr = key.substring(4); // after 'L_X_'
                            try{
                                int range = Integer.parseInt(numStr);
                                int offset = rand.nextInt(range * 2 + 1) - range; // [-range, range]
                                switch(axis){
                                    case 'X':
                                        replacement = String.valueOf(ctx.getLocation().getBlockX() + offset);
                                        break;
                                    case 'Y':
                                        replacement = String.valueOf(ctx.getLocation().getBlockY() + offset);
                                        break;
                                    case 'Z':
                                        replacement = String.valueOf(ctx.getLocation().getBlockZ() + offset);
                                        break;
                                }
                            }catch(NumberFormatException ignored){}
                        }
                        break;
                }
            }
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String resolveStatFromRegistry(io.lumine.mythic.core.skills.stats.StatRegistry reg, String statName) {
        if(statName == null || statName.isEmpty()) return "0";

        // 1) 优先在 BaseStats Map 中直接查找（O(1) 效率最高）
        try{
            java.util.Map<String, Double> base = reg.getBaseStats();
            if(base != null){
                // 尝试原始键名
                Double v = base.get(statName);
                if(v == null){
                    // 尝试大写变体
                    v = base.get(statName.toUpperCase(java.util.Locale.ROOT));
                }
                if(v == null){
                    // 尝试小写变体
                    v = base.get(statName.toLowerCase(java.util.Locale.ROOT));
                }
                if(v != null){
                    MessageUtils.log(java.util.logging.Level.FINE, "在基础属性Map中找到: " + statName + " = " + v);
                    return v.toString();
                }
            }
        }catch(Exception ignored){}

        // 2) 回退到已应用属性遍历查找（O(n)，用于模糊匹配）
        java.util.function.Function<String,String> normalizer = s -> s.replace("_", "").toLowerCase(java.util.Locale.ROOT);
        String normalizedTarget = normalizer.apply(statName);

        for(io.lumine.mythic.core.skills.stats.StatType t : reg.getApplicableStats()){
            if(normalizer.apply(t.toString()).equals(normalizedTarget)){
                double value = reg.get(t);
                MessageUtils.log(java.util.logging.Level.FINE, "通过模糊匹配找到属性: " + t.toString() + " = " + value);
                return String.valueOf(value);
            }
        }

        MessageUtils.log(java.util.logging.Level.FINE, "属性 {STAT:" + statName + "} 未找到，返回默认值 0");
        return "0";
    }
} 