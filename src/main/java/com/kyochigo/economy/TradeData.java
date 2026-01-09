package com.kyochigo.economy;

public class TradeData {
    public String configKey;
    public String displayName;
    public String material;
    public int amount;
    public double unitPrice;
    public double totalPrice;
    
    // 【新增】环境指数 (用于锁定汇率，防止预览和成交价格不一致)
    public double envIndex; 

    public TradeData(String k, String d, String m, int a, double u, double t, double env) {
        this.configKey = k;
        this.displayName = d;
        this.material = m;
        this.amount = a;
        this.unitPrice = u;
        this.totalPrice = t;
        this.envIndex = env;
    }
}