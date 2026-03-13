package com.baimo.jwabnormaleffects.utils;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.objecthunter.exp4j.Expression;
import net.objecthunter.exp4j.ExpressionBuilder;
import net.objecthunter.exp4j.function.Function;

/**
 * 公式计算工具类
 * <p>使用exp4j库实现复杂的数学表达式计算，支持各种数学函数和运算符。</p>
 * 
 * @author BaiMo_
 * @since D07
 * @version 1.0
 */
public final class FormulaCalculatorUtil {

    // 预定义的数学函数
    private static final Map<String, Function> CUSTOM_FUNCTIONS = initCustomFunctions();
    
    // 变量模式匹配
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{([^}]+)\\}");

    /** 私有构造函数，禁止实例化 */
    private FormulaCalculatorUtil() {
        throw new UnsupportedOperationException("This utility class cannot be instantiated");
    }

    /**
     * 计算数学表达式
     * 
     * @param formula 数学表达式字符串
     * @return 计算结果
     * @throws IllegalArgumentException 如果表达式无效
     */
    public static double calculate(String formula) throws IllegalArgumentException {
        if (formula == null || formula.trim().isEmpty()) {
            throw new IllegalArgumentException("公式不能为空");
        }

        // 预处理公式
        String processedFormula = preprocessFormula(formula.trim());
        
        try {
            // 如果是纯数字，直接返回
            return Double.parseDouble(processedFormula);
        } catch (NumberFormatException e) {
            // 继续处理复杂表达式
        }

        try {
            ExpressionBuilder builder = new ExpressionBuilder(processedFormula);
            
            // 添加自定义函数
            for (Function func : CUSTOM_FUNCTIONS.values()) {
                builder.function(func);
            }
            
            Expression expression = builder.build();
            return expression.evaluate();
            
        } catch (Exception e) {
            throw new IllegalArgumentException("无法计算公式 '" + formula + "': " + e.getMessage(), e);
        }
    }

    /**
     * 计算包含变量的数学表达式
     * 
     * @param formula 数学表达式字符串
     * @param variables 变量映射
     * @return 计算结果
     * @throws IllegalArgumentException 如果表达式无效
     */
    public static double calculate(String formula, Map<String, Double> variables) throws IllegalArgumentException {
        if (formula == null || formula.trim().isEmpty()) {
            throw new IllegalArgumentException("公式不能为空");
        }

        // 预处理公式并替换变量
        String processedFormula = preprocessFormula(formula.trim());
        processedFormula = replaceVariables(processedFormula, variables);
        
        try {
            // 如果是纯数字，直接返回
            return Double.parseDouble(processedFormula);
        } catch (NumberFormatException e) {
            // 继续处理复杂表达式
        }

        try {
            ExpressionBuilder builder = new ExpressionBuilder(processedFormula);
            
            // 添加自定义函数
            for (Function func : CUSTOM_FUNCTIONS.values()) {
                builder.function(func);
            }
            
            // 添加变量（如果还有剩余的变量名）
            if (variables != null) {
                for (Map.Entry<String, Double> entry : variables.entrySet()) {
                    builder.variable(entry.getKey());
                }
            }
            
            Expression expression = builder.build();
            
            // 设置变量值
            if (variables != null) {
                for (Map.Entry<String, Double> entry : variables.entrySet()) {
                    expression.setVariable(entry.getKey(), entry.getValue());
                }
            }
            
            return expression.evaluate();
            
        } catch (Exception e) {
            throw new IllegalArgumentException("无法计算公式 '" + formula + "': " + e.getMessage(), e);
        }
    }

    /**
     * 检查表达式是否包含数学运算
     * 
     * @param formula 表达式字符串
     * @return 如果包含数学运算则返回true
     */
    public static boolean containsMathExpression(String formula) {
        if (formula == null || formula.isEmpty()) {
            return false;
        }
        
        // 检查是否包含运算符
        return formula.matches(".*[+\\-*/^()]+.*") || containsMathFunction(formula);
    }

    /**
     * 检查是否包含数学函数
     */
    private static boolean containsMathFunction(String formula) {
        for (String funcName : CUSTOM_FUNCTIONS.keySet()) {
            if (formula.contains(funcName + "(")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 预处理公式，标准化格式
     */
    private static String preprocessFormula(String formula) {
        // 移除多余空格
        String processed = formula.replaceAll("\\s+", "");
        
        // 处理一些常见的替换
        processed = processed.replace("×", "*");
        processed = processed.replace("÷", "/");
        processed = processed.replace("π", String.valueOf(Math.PI));
        processed = processed.replace("e", String.valueOf(Math.E));
        
        return processed;
    }

    /**
     * 替换变量占位符
     */
    private static String replaceVariables(String formula, Map<String, Double> variables) {
        if (variables == null || variables.isEmpty()) {
            return formula;
        }
        
        Matcher matcher = VARIABLE_PATTERN.matcher(formula);
        StringBuffer result = new StringBuffer();
        
        while (matcher.find()) {
            String varName = matcher.group(1);
            Double value = variables.get(varName);
            
            if (value != null) {
                matcher.appendReplacement(result, value.toString());
            } else {
                // 保持原样，可能是exp4j的变量名
                matcher.appendReplacement(result, varName);
            }
        }
        matcher.appendTail(result);
        
        return result.toString();
    }

    /**
     * 初始化自定义数学函数
     */
    private static Map<String, Function> initCustomFunctions() {
        Map<String, Function> functions = new HashMap<>();
        
        // 基本数学函数
        functions.put("min", new Function("min", 2) {
            @Override
            public double apply(double... args) {
                return Math.min(args[0], args[1]);
            }
        });
        
        functions.put("max", new Function("max", 2) {
            @Override
            public double apply(double... args) {
                return Math.max(args[0], args[1]);
            }
        });
        
        functions.put("floor", new Function("floor", 1) {
            @Override
            public double apply(double... args) {
                return Math.floor(args[0]);
            }
        });
        
        functions.put("ceil", new Function("ceil", 1) {
            @Override
            public double apply(double... args) {
                return Math.ceil(args[0]);
            }
        });
        
        functions.put("round", new Function("round", 1) {
            @Override
            public double apply(double... args) {
                return Math.round(args[0]);
            }
        });
        
        functions.put("abs", new Function("abs", 1) {
            @Override
            public double apply(double... args) {
                return Math.abs(args[0]);
            }
        });
        
        functions.put("sqrt", new Function("sqrt", 1) {
            @Override
            public double apply(double... args) {
                return Math.sqrt(args[0]);
            }
        });
        
        functions.put("pow", new Function("pow", 2) {
            @Override
            public double apply(double... args) {
                return Math.pow(args[0], args[1]);
            }
        });
        
        // 三角函数
        functions.put("sin", new Function("sin", 1) {
            @Override
            public double apply(double... args) {
                return Math.sin(args[0]);
            }
        });
        
        functions.put("cos", new Function("cos", 1) {
            @Override
            public double apply(double... args) {
                return Math.cos(args[0]);
            }
        });
        
        functions.put("tan", new Function("tan", 1) {
            @Override
            public double apply(double... args) {
                return Math.tan(args[0]);
            }
        });
        
        // 对数函数
        functions.put("log", new Function("log", 1) {
            @Override
            public double apply(double... args) {
                return Math.log10(args[0]);
            }
        });
        
        functions.put("ln", new Function("ln", 1) {
            @Override
            public double apply(double... args) {
                return Math.log(args[0]);
            }
        });
        
        // 游戏相关的特殊函数
        functions.put("clamp", new Function("clamp", 3) {
            @Override
            public double apply(double... args) {
                // clamp(value, min, max)
                return Math.max(args[1], Math.min(args[0], args[2]));
            }
        });
        
        functions.put("lerp", new Function("lerp", 3) {
            @Override
            public double apply(double... args) {
                // lerp(start, end, t)
                return args[0] + args[2] * (args[1] - args[0]);
            }
        });
        
        functions.put("percentage", new Function("percentage", 2) {
            @Override
            public double apply(double... args) {
                // percentage(value, total) = (value / total) * 100
                return (args[0] / args[1]) * 100.0;
            }
        });
        
        return functions;
    }

    /**
     * 获取所有可用的函数名称
     */
    public static String[] getAvailableFunctions() {
        return CUSTOM_FUNCTIONS.keySet().toArray(new String[0]);
    }

    /**
     * 验证公式语法是否正确（不执行计算）
     * 
     * @param formula 公式字符串
     * @return 如果语法正确返回true
     */
    public static boolean validateFormula(String formula) {
        try {
            calculate(formula);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
} 