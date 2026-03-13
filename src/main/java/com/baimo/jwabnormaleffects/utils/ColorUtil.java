package com.baimo.jwabnormaleffects.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Server;

/**
 * 颜色转换工具类
 * <p>支持：</p>
 * <ul>
 *   <li>传统的 &amp;0–&amp;f 颜色码</li>
 *   <li>形如 &#RRGGBB 的 RGB 颜色码（仅在 MC 1.16+ 生效）</li>
 * </ul>
 */
public class ColorUtil {
    // 匹配 &#RRGGBB 格式
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");
    // 匹配所有颜色代码
    private static final Pattern COLOR_PATTERN = Pattern.compile("(?i)[§&].");

    private ColorUtil() {
        // 私有构造，禁止实例化
    }

    /**
     * 把文本中的颜色代码翻译成 Minecraft 格式。
     * @param text 带颜色码的原始文本
     * @param server 用于获取 MC 版本号
     * @return 转换后可直接发送给玩家的文本
     */
    public static String translateColors(String text) {
        if (text == null) return "";

        // 先处理 &#RRGGBB
        Matcher matcher = HEX_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String hex = matcher.group(1);
            String replacement;
            if (getMajorVersion(Bukkit.getServer()) >= 16) {
                // §x§R§R§G§G§B§B
                StringBuilder rep = new StringBuilder("§x");
                for (char c : hex.toCharArray()) {
                    rep.append('§').append(c);
                }
                replacement = rep.toString();
            } else {
                // 低版本则降级为最接近的传统色
                replacement = nearestLegacy(hex);
            }
            matcher.appendReplacement(sb, replacement);
        }
        matcher.appendTail(sb);

        // 再处理 & 颜色码
        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }
    /**
     * 去除文本中的所有颜色代码
     * @param text 带颜色码的原始文本
     * @return 去除颜色码后的文本
     * 
     * <p>注意：用的上才用,用不上请忽略该方法</p>
     */
    public static String stripColorCodes(String text) {
        if (text == null) return "";
        
        // 先处理 &#RRGGBB
        String result = HEX_PATTERN.matcher(text).replaceAll("");
        
        // 再处理 & 和 § 颜色码
        return COLOR_PATTERN.matcher(result).replaceAll("");
    }

    /** 提取服务器 Bukkit 主版本号，比如 "1.18.2" → 18 */
    private static int getMajorVersion(Server server) {
        String v = server.getBukkitVersion();
        Pattern p = Pattern.compile("1\\.(\\d+)");
        Matcher m = p.matcher(v);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return 8; // 默认当作低版本
    }

    /** 简单亮度法选取最接近的传统颜色 */
    private static String nearestLegacy(String hex) {
        int rgb = Integer.parseInt(hex, 16);
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        double bright = 0.299 * r + 0.587 * g + 0.114 * b;
        if (r > g * 2 && r > b * 2) return bright > 160 ? "§c" : "§4";
        if (g > r * 2 && g > b * 2) return bright > 160 ? "§a" : "§2";
        if (b > r * 2 && b > g * 2) return bright > 160 ? "§9" : "§1";
        if (bright > 180) return "§f";
        if (bright > 120) return "§7";
        if (bright > 60)  return "§8";
        return "§0";
    }
}
