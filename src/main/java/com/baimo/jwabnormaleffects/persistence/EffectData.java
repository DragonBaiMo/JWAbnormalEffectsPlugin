package com.baimo.jwabnormaleffects.persistence;

/**
 * 存储单个效果的所有数据
 * 用于纯内存缓存
 */
public class EffectData {
    private double accumulatedValue;
    private double currentThreshold;
    private int lastTriggeredStage;
    private long lastDecayTick;
    private long lastActivityTime;
    
    // 非积累效果的到期时间（毫秒时间戳）。0 表示无限制或尚未设置
    private long expireTime;
    
    private double lastFinalValue; // 最近一次触发的最终值
    private double lastScaleFactor = 1.0;
    private double lastBaseValue = 0.0;
    private boolean lastInstant = false;
    
    // ===== 新增：同名效果叠加统计 =====
    private int sameEffectStack          = 0;   // 当前叠加次数
    private long sameEffectLastApply     = 0L;  // 最近一次获得该效果的时间戳
    private int  sameEffectWindowSeconds = 0;   // 叠加窗口秒数
    
    private java.util.UUID lastCasterUUID = null; // 最近一次应用该效果的施法者

    // --- 阶段下降通知 ---
    private int lastDecreaseNotifiedStage = -1; // 最近一次通知玩家阶段下降至的阶段百分比
    
    public EffectData() {
        this.accumulatedValue = 0.0;
        this.currentThreshold = 0.0;
        this.lastTriggeredStage = 0;
        this.lastDecayTick = 0L;
        this.lastActivityTime = System.currentTimeMillis();
        this.expireTime = 0L;
        this.lastFinalValue = 0.0;
        this.lastScaleFactor = 1.0;
    }
    
    public EffectData(double defaultThreshold) {
        this();
        this.currentThreshold = defaultThreshold;
    }
    
    // Getters and Setters
    public double getAccumulatedValue() {
        return accumulatedValue;
    }
    
    public void setAccumulatedValue(double accumulatedValue) {
        this.accumulatedValue = accumulatedValue;
        this.lastActivityTime = System.currentTimeMillis(); // 更新活动时间
    }
    
    public double getCurrentThreshold() {
        return currentThreshold;
    }
    
    public void setCurrentThreshold(double currentThreshold) {
        this.currentThreshold = currentThreshold;
    }
    
    public int getLastTriggeredStage() {
        return lastTriggeredStage;
    }
    
    public void setLastTriggeredStage(int lastTriggeredStage) {
        this.lastTriggeredStage = lastTriggeredStage;
    }
    
    public long getLastDecayTick() {
        return lastDecayTick;
    }
    
    public void setLastDecayTick(long lastDecayTick) {
        this.lastDecayTick = lastDecayTick;
    }
    
    public long getLastActivityTime() {
        return lastActivityTime;
    }
    
    public void setLastActivityTime(long lastActivityTime) {
        this.lastActivityTime = lastActivityTime;
    }
    
    /**
     * 获取非积累效果的到期时间戳。
     * @return 到期时间（毫秒），0 表示未设置
     */
    public long getExpireTime() {
        return expireTime;
    }
    
    /**
     * 设置非积累效果的到期时间戳。
     * @param expireTimeMillis 绝对毫秒时间戳
     */
    public void setExpireTime(long expireTimeMillis) {
        this.expireTime = expireTimeMillis;
    }
    
    /**
     * 检查效果是否为空（累积值为0）
     */
    public boolean isEmpty() {
        return accumulatedValue <= 0 && expireTime <= 0;
    }
    
    public double getLastFinalValue() { return lastFinalValue; }
    public void setLastFinalValue(double v) { this.lastFinalValue = v; }
    public double getLastScaleFactor() { return lastScaleFactor; }
    public void setLastScaleFactor(double v) { this.lastScaleFactor = v; }
    
    public double getLastBaseValue() { return lastBaseValue; }
    public void setLastBaseValue(double v) { this.lastBaseValue = v; }
    
    public boolean isLastInstant() { return lastInstant; }
    public void setLastInstant(boolean b) { this.lastInstant = b; }
    
    // ----- same-effect stacking -----
    public int getSameEffectStack() { return sameEffectStack; }
    public void setSameEffectStack(int v) { this.sameEffectStack = v; }
    public long getSameEffectLastApply() { return sameEffectLastApply; }
    public void setSameEffectLastApply(long t) { this.sameEffectLastApply = t; }
    public int getSameEffectWindowSeconds() { return sameEffectWindowSeconds; }
    public void setSameEffectWindowSeconds(int s) { this.sameEffectWindowSeconds = s; }
    
    public java.util.UUID getLastCasterUUID() { return lastCasterUUID; }
    public void setLastCasterUUID(java.util.UUID uuid) { this.lastCasterUUID = uuid; }

    // ----- 阶段下降通知 -----
    public int getLastDecreaseNotifiedStage() { return lastDecreaseNotifiedStage; }
    public void setLastDecreaseNotifiedStage(int stage) { this.lastDecreaseNotifiedStage = stage; }
    
    @Override
    public String toString() {
        return String.format("EffectData{value=%.2f, threshold=%.2f, stage=%d}", 
                accumulatedValue, currentThreshold, lastTriggeredStage);
    }
} 