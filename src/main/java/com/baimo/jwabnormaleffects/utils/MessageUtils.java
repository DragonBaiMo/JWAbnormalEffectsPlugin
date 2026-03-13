package com.baimo.jwabnormaleffects.utils;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import com.baimo.jwabnormaleffects.JWAbnormalEffects;

/**
 * 消息工具类 - 负责处理消息国际化和颜色转换
 */
public class MessageUtils {
    private static Logger logger; // 由主插件类设置
    private static FileConfiguration langConfig;
    private static String messagePrefix = "&6[AbnormalEffects] &r";
    private static JavaPlugin plugin;
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{([^{}]+)\\}");

    /**
     * 初始化消息工具类
     * @param plugin 插件实例
     * @param pluginLogger 日志记录器
     */
    public static void init(JavaPlugin plugin, Logger pluginLogger) {
        MessageUtils.plugin = plugin;
        MessageUtils.logger = pluginLogger;
        loadLangConfig();
    }

    /**
     * 加载语言配置文件
     */
    public static void loadLangConfig() {
        // 确保目录存在
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }

        // 保存默认语言文件
        File langFile = new File(plugin.getDataFolder(), "lang.yml");
        if (!langFile.exists()) {
            plugin.saveResource("lang.yml", false);
        }

        // 加载语言文件
        langConfig = YamlConfiguration.loadConfiguration(langFile);
        
        // 加载前缀
        messagePrefix = getString("prefix", "&6[AbnormalEffects] &r");
    }

    /**
     * 重新加载语言配置文件
     */
    public static void reloadLangConfig() {
        loadLangConfig();
    }

    /**
     * 与ConfigManager同步消息前缀
     * @param configPrefix 配置管理器中的消息前缀
     */
    public static void syncWithConfigManager(String configPrefix) {
        if (configPrefix != null && !configPrefix.isEmpty()) {
            messagePrefix = configPrefix;
        }
    }

    /**
     * 向命令发送者发送消息，支持颜色代码和占位符
     * @param sender 命令发送者
     * @param messageKey 消息键值
     * @param placeholders 占位符值对 (可选)
     */
    public static void send(CommandSender sender, String messageKey, Object... placeholders) {
        // 如果发送目标是控制台且当前日志级别为 INFO，则忽略所有发送
        if (shouldSkipConsoleOutput(sender)) {
            return;
        }
        if (sender == null) return; // 安全检查
        
        String message = getMessage(messageKey);
        
        // 处理占位符
        if (placeholders.length > 0) {
            if (placeholders.length % 2 != 0) {
                log(Level.WARNING, "占位符参数数量不匹配: " + messageKey);
            } else {
                Map<String, String> placeholderMap = new HashMap<>();
                for (int i = 0; i < placeholders.length; i += 2) {
                    if (placeholders[i] != null && placeholders[i+1] != null) {
                        placeholderMap.put(placeholders[i].toString(), placeholders[i + 1].toString());
                    }
                }
                message = replacePlaceholders(message, placeholderMap);
            }
        }
        
        // 转换颜色代码并发送
        String finalMessage = ColorUtil.translateColors(messagePrefix + message);
        sender.sendMessage(finalMessage);
    }

    /**
     * 获取消息内容，自动添加前缀
     * @param messageKey 消息键值
     * @param placeholders 占位符值对
     * @return 格式化后的消息
     */
    public static String getMessageWithPrefix(String messageKey, Object... placeholders) {
        return messagePrefix + getMessage(messageKey, placeholders);
    }

    /**
     * 获取消息内容，不包含前缀
     * @param messageKey 消息键值
     * @param placeholders 占位符值对
     * @return 格式化后的消息
     */
    public static String getMessage(String messageKey, Object... placeholders) {
        String message = getString(messageKey, messageKey);
        
        // 处理占位符
        if (placeholders.length > 0) {
            if (placeholders.length % 2 != 0) {
                log(Level.WARNING, "占位符参数数量不匹配: " + messageKey);
            } else {
                Map<String, String> placeholderMap = new HashMap<>();
                for (int i = 0; i < placeholders.length; i += 2) {
                    placeholderMap.put(placeholders[i].toString(), placeholders[i + 1].toString());
                }
                message = replacePlaceholders(message, placeholderMap);
            }
        }
        
        return message;
    }

    /**
     * 从配置文件中获取文本
     * @param path 配置路径
     * @param defaultValue 默认值
     * @return 配置文本
     */
    private static String getString(String path, String defaultValue) {
        if (langConfig == null) return defaultValue;
        
        // 直接检查完整路径
        if (langConfig.contains(path)) {
            String value = langConfig.getString(path);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        
        // 对于命令相关路径，尝试添加messages.前缀
        if (path.startsWith("command.") && !path.startsWith("messages.command.")) {
            String altPath = "messages." + path;
            if (langConfig.contains(altPath)) {
                String value = langConfig.getString(altPath);
                if (value != null && !value.isEmpty()) {
                    return value;
                }
            }
        }
        
        // 对于effects相关路径，尝试添加messages.前缀
        if (path.startsWith("effects.") && !path.startsWith("messages.effects.")) {
            String altPath = "messages." + path;
            if (langConfig.contains(altPath)) {
                String value = langConfig.getString(altPath);
                if (value != null && !value.isEmpty()) {
                    return value;
                }
            }
        }
        
        // 回退到默认值
        return defaultValue;
    }

    /**
     * 替换消息中的占位符
     * @param message 消息文本
     * @param placeholders 占位符列表
     * @return 替换后的文本
     */
    private static String replacePlaceholders(String message, Map<String, String> placeholders) {
        if (message == null || placeholders == null || placeholders.isEmpty()) {
            return message;
        }
        
        try {
            StringBuffer result = new StringBuffer();
            Matcher matcher = PLACEHOLDER_PATTERN.matcher(message);
            
            while (matcher.find()) {
                String placeholder = matcher.group(1);
                String replacement = placeholders.getOrDefault(placeholder, "{" + placeholder + "}");
                if (replacement == null) replacement = "{" + placeholder + "}";
                matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
            }
            
            matcher.appendTail(result);
            return result.toString();
        } catch (Exception e) {
            log(Level.WARNING, "替换占位符时发生错误: " + e.getMessage());
            return message; // 发生异常时返回原消息
        }
    }

    /**
     * 记录日志
     * @param level 日志级别
     * @param message 日志内容
     */
    public static void log(Level level, String message) {
        if (logger != null) {
            logger.log(level, message);
        } else {
            String logMsg = getString("debug.logger-not-init", "[{level}] AbnormalEffects (logger not init): {message}");
            Map<String, String> placeholderMap = new HashMap<>();
            placeholderMap.put("level", level.getName());
            placeholderMap.put("message", message);
            logMsg = replacePlaceholders(logMsg, placeholderMap);
            System.out.println(logMsg);
        }
    }
    
    /**
     * 记录带有占位符的日志
     * @param level 日志级别
     * @param messageKey 消息键值
     * @param placeholders 占位符值对 (可选)
     */
    public static void log(Level level, String messageKey, Object... placeholders) {
        String message = getMessage(messageKey, placeholders);
        log(level, message);
    }

    // ================================
    // 效果相关消息辅助方法
    // ================================

    /**
     * 发送效果相关消息，自动处理effect_name占位符
     * 
     * @param sender 命令发送者
     * @param messageKey 消息键值
     * @param effectId 效果ID
     * @param placeholders 其他占位符值对 (可选)
     */
    public static void sendEffectMessage(CommandSender sender, String messageKey, String effectId, Object... placeholders) {
        if (shouldSkipConsoleOutput(sender)) {
            return;
        }
        if (sender == null || messageKey == null || effectId == null) return;

        // 创建占位符映射，添加effect_name
        Map<String, String> placeholderMap = new HashMap<>();
        placeholderMap.put("effect_name", getEffectDisplayName(effectId));

        // 处理其他占位符
        if (placeholders.length > 0) {
            if (placeholders.length % 2 != 0) {
                log(Level.WARNING, "占位符参数数量不匹配: " + messageKey);
            } else {
                for (int i = 0; i < placeholders.length; i += 2) {
                    if (placeholders[i] != null && placeholders[i+1] != null) {
                        placeholderMap.put(placeholders[i].toString(), placeholders[i + 1].toString());
                    }
                }
            }
        }

        String message = getMessage(messageKey);
        message = replacePlaceholders(message, placeholderMap);
        
        // 转换颜色代码并发送
        String finalMessage = ColorUtil.translateColors(messagePrefix + message);
        sender.sendMessage(finalMessage);
    }

    /**
     * 获取效果显示名称（委托给ConfigManager）
     * 注意：此方法需要在插件初始化后调用，因为需要访问ConfigManager实例
     * 
     * @param effectId 效果ID
     * @return 格式化后的显示名称
     */
    public static String getEffectDisplayName(String effectId) {
        if (plugin != null && plugin instanceof JWAbnormalEffects) {
            JWAbnormalEffects abnormalPlugin =
                (JWAbnormalEffects) plugin;
            return abnormalPlugin.getConfigManager().getFormattedEffectDisplayName(effectId);
        }
        
        // 回退方案：直接返回effectId
        return effectId != null ? effectId : "未知效果";
    }

    /**
     * 获取效果描述（委托给ConfigManager）
     * 注意：此方法需要在插件初始化后调用，因为需要访问ConfigManager实例
     * 
     * @param effectId 效果ID
     * @return 格式化后的描述
     */
    public static String getEffectDescription(String effectId) {
        if (plugin != null && plugin instanceof JWAbnormalEffects) {
            JWAbnormalEffects abnormalPlugin =
                (JWAbnormalEffects) plugin;
            return abnormalPlugin.getConfigManager().getFormattedEffectDescription(effectId);
        }
        
        // 回退方案：返回空字符串
        return "";
    }

    /**
     * 获取效果完整信息（显示名称 + 描述）
     * 
     * @param effectId 效果ID
     * @return 格式化的完整效果信息
     */
    public static String getEffectInfo(String effectId) {
        if (plugin != null && plugin instanceof JWAbnormalEffects) {
            JWAbnormalEffects abnormalPlugin =
                (JWAbnormalEffects) plugin;
            return abnormalPlugin.getConfigManager().getFormattedEffectInfo(effectId);
        }
        
        // 回退方案
        return getEffectDisplayName(effectId) + " - " + getEffectDescription(effectId);
    }

    /**
     * 判断是否应当忽略向控制台输出的消息。
     * 规则：当发送者为控制台并且核心配置中的 debug.log-level 设置为 INFO 时，返回 true。
     * 
     * @param sender 消息目标
     * @return 是否应跳过发送
     */
    private static boolean shouldSkipConsoleOutput(CommandSender sender) {
        if (!(sender instanceof org.bukkit.command.ConsoleCommandSender)) {
            return false;
        }

        if (plugin instanceof com.baimo.jwabnormaleffects.JWAbnormalEffects) {
            com.baimo.jwabnormaleffects.JWAbnormalEffects p = (com.baimo.jwabnormaleffects.JWAbnormalEffects) plugin;
            com.baimo.jwabnormaleffects.config.ConfigManager cm = p.getConfigManager();
            if (cm != null && "INFO".equalsIgnoreCase(cm.getLogLevel())) {
                return true;
            }
        }

        return false;
    }
}