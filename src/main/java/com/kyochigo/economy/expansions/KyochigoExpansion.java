package com.kyochigo.economy.expansions;

import com.kyochigo.economy.KyochigoPlugin;
import com.kyochigo.economy.TradeData;
import com.kyochigo.economy.managers.InventoryManager;
import com.kyochigo.economy.managers.MarketManager;
import com.kyochigo.economy.model.MarketItem;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;

/**
 * KyochigoEconomy PAPI 扩展 (v2.1 增强版)
 */
public class KyochigoExpansion extends PlaceholderExpansion {
    
    private final KyochigoPlugin plugin;
    private final InventoryManager inventoryManager;
    private final MarketManager marketManager;
    private final Map<UUID, TradeData> tradeCache;

    public KyochigoExpansion(KyochigoPlugin plugin, InventoryManager inventoryManager, MarketManager marketManager, Map<UUID, TradeData> tradeCache) {
        this.plugin = plugin; // 建议直接由主类传入
        this.inventoryManager = inventoryManager;
        this.marketManager = marketManager;
        this.tradeCache = tradeCache;
    }

    @Override
    public @NotNull String getIdentifier() { return "kyochigo"; }
    
    @Override
    public @NotNull String getAuthor() { return "Kyochigo"; }
    
    @Override
    public @NotNull String getVersion() { return "2.1.0"; } 
    
    @Override
    public boolean persist() { return true; }

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) return "";

        // --- [1. 物品基础属性] ---

        // %kyochigo_balance_<Key>% : 玩家持有量
        if (params.startsWith("balance_")) {
            MarketItem item = marketManager.findMarketItemByKey(params.substring(8));
            return item != null ? String.valueOf(inventoryManager.countItems(player, item)) : "0";
        }

        // %kyochigo_item_name_<Key>% : 物品配置的展示名 (不带颜色代码的纯文本)
        if (params.startsWith("item_name_")) {
            MarketItem item = marketManager.findMarketItemByKey(params.substring(10));
            return item != null ? item.getPlainDisplayName() : "未知物品";
        }

        // --- [2. 价格与市场动态] ---

        // %kyochigo_price_sell_<Key>%
        if (params.startsWith("price_sell_")) {
            MarketItem item = marketManager.findMarketItemByKey(params.substring(11));
            return item != null ? String.format("%.2f", item.getSellPrice()) : "0.00";
        }

        // %kyochigo_price_buy_<Key>%
        if (params.startsWith("price_buy_")) {
            MarketItem item = marketManager.findMarketItemByKey(params.substring(10));
            return item != null ? String.format("%.2f", item.getBuyPrice()) : "0.00";
        }

        // %kyochigo_trend_<Key>% : 涨跌趋势符号
        if (params.startsWith("trend_")) {
            MarketItem item = marketManager.findMarketItemByKey(params.substring(6));
            if (item == null) return "";
            double current = item.getSellPrice();
            double base = item.getBasePrice();
            if (current > base) return "§a↑";
            if (current < base) return "§c↓";
            return "§7-";
        }

        // --- [3. 个人限额统计] ---

        // %kyochigo_daily_remaining_<Key>%
        if (params.startsWith("daily_remaining_")) {
            String itemKey = params.substring(16);
            int limit = plugin.getConfiguration().getItemDailyLimit(itemKey);
            if (limit <= 0) return "∞";
            int traded = plugin.getHistoryManager().getDailyTradeCount(player.getUniqueId().toString(), itemKey);
            return String.valueOf(Math.max(0, limit - traded));
        }

        // --- [4. 全局状态与分类] ---

        // %kyochigo_item_category_<Key>% : 获取物品所属分类的本地化名称
        if (params.startsWith("item_category_")) {
            MarketItem item = marketManager.findMarketItemByKey(params.substring(14));
            if (item == null) return "未知";
            return plugin.getConfiguration().getRaw().getString("categories." + item.getCategory() + ".name", item.getCategory());
        }

        // %kyochigo_env_note%
        if (params.equalsIgnoreCase("env_note")) {
            return marketManager.getLastEnvNote();
        }

        // --- [5. 交易会话缓存] ---
        TradeData data = tradeCache.get(player.getUniqueId());
        if (data != null) {
            switch (params.toLowerCase()) {
                case "session_total": return String.format("%.2f", data.totalPrice);
                case "session_type": return data.isBuy ? "购买" : "出售";
            }
        }

        return null; 
    }
}