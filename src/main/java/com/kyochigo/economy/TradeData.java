package com.kyochigo.economy;

import com.google.gson.JsonObject;
import com.kyochigo.economy.model.MarketItem;
import org.bukkit.entity.Player;

/**
 * 交易快照数据类 (Transaction Snapshot)
 * <p>
 * 职责：
 * 1. 在 GUI 确认窗口打开期间，"锁定" 价格和汇率。
 * 2. 作为数据传输对象 (DTO)，负责按照 Rust 后端约定的 CamelCase 格式序列化请求。
 */
public class TradeData {
    
    // --- 核心标识数据 ---
    public final String configKey;    // 对应后端 itemId (例如 "diamond")
    
    // --- UI 显示数据 (仅用于 Java GUI 展示) ---
    public final String displayName;  // 物品展示名
    public final String material;     // 物品材质图标

    // --- 交易数值 (Java 端计算用于 UI 显示) ---
    public final int amount;          // 数量
    public final double unitPrice;    // 视觉锁定的单价 (Snapshot Price)
    public final double totalPrice;   // 视觉锁定的总价
    
    // --- 环境上下文 ---
    public final double envIndex;     // 创建快照时的环境指数 (ε)

    // --- 交易类型 ---
    public final boolean isBuy;       // true = 买入 (BUY), false = 卖出 (SELL)

    /**
     * 全参构造函数
     * 确保交易快照在创建那一刻，所有状态都被固定，防止网络延迟或行情跳变导致的纠纷
     */
    public TradeData(String configKey, String displayName, String material, int amount, 
                     double unitPrice, double totalPrice, double envIndex, boolean isBuy) {
        this.configKey = configKey;
        this.displayName = displayName;
        this.material = material;
        this.amount = amount;
        this.unitPrice = unitPrice;
        this.totalPrice = totalPrice;
        this.envIndex = envIndex;
        this.isBuy = isBuy;
    }

    /**
     * 转换为后端专用 JSON 请求体
     * 完全对齐 Rust 后端 models.rs 中的 TradeRequest 结构
     * * @param player     发起交易的玩家
     * @param originItem 对应的原始物品定义（用于获取 basePrice 和 lambda）
     * @param isPreview  是否为预览模式。true=试算价格不记账; false=正式成交并持久化
     * @return 准备发送给后端 API 的 JsonObject
     */
    public JsonObject toJsonForBackend(Player player, MarketItem originItem, boolean isPreview) {
        JsonObject json = new JsonObject();

        // 1. 身份识别 (对齐 Rust: playerId, playerName)
        json.addProperty("playerId", player.getUniqueId().toString());
        json.addProperty("playerName", player.getName());

        // 2. 物品识别 (对齐 Rust: itemId)
        json.addProperty("itemId", this.configKey);

        // 3. 核心数学参数 (用于后端积分模型重算)
        // 注意：后端不接收 totalPrice，而是根据以下参数实时演算，防止玩家通过修改内存改钱
        json.addProperty("basePrice", originItem.getBasePrice());
        json.addProperty("amount", (double) this.amount);
        json.addProperty("decayLambda", originItem.getLambda());

        // 4. 业务控制 (对齐 Rust: isPreview)
        json.addProperty("isPreview", isPreview);

        // 5. 环境指数锁定 (对齐 Rust: manualEnvIndex)
        // 传递快照时的 envIndex，确保后端计算的基准环境倍率与玩家看到的一致
        json.addProperty("manualEnvIndex", this.envIndex);

        return json;
    }

    /**
     * 获取后端路由名称
     * @return "buy" 或 "sell"
     */
    public String getActionPath() {
        return isBuy ? "buy" : "sell";
    }

    @Override
    public String toString() {
        return String.format("TradeSnapshot{key=%s, action=%s, amount=%d, lockedPrice=%.2f}",
                configKey, isBuy ? "BUY" : "SELL", amount, unitPrice);
    }
}