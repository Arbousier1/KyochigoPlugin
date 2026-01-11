package com.kyochigo.economy.expansions;

import com.kyochigo.economy.KyochigoPlugin;
import com.kyochigo.economy.TradeData;
import com.kyochigo.economy.managers.InventoryManager;
import com.kyochigo.economy.managers.MarketManager;
import com.kyochigo.economy.model.MarketItem;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * KyochigoEconomy PAPI 扩展 (v3.1 最终版)
 * 优化：
 * 1. 增加了 env_index 数值占位符。
 * 2. 优化了价格格式化逻辑。
 */
public class KyochigoExpansion extends PlaceholderExpansion {
    
    private final KyochigoPlugin plugin;
    private final InventoryManager inventoryManager;
    private final MarketManager marketManager;
    private final Map<UUID, TradeData> tradeCache;

    private final Map<String, BiFunction<Player, String, String>> placeholderHandlers = new HashMap<>();
    private final Map<String, MarketItem> itemCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_TICKS = 1200L; 

    public KyochigoExpansion(KyochigoPlugin plugin, InventoryManager inventoryManager, MarketManager marketManager, Map<UUID, TradeData> tradeCache) {
        this.plugin = plugin;
        this.inventoryManager = inventoryManager;
        this.marketManager = marketManager;
        this.tradeCache = tradeCache;
        initializeHandlers();
    }

    private void initializeHandlers() {
        placeholderHandlers.put("balance_", this::handleBalance);
        placeholderHandlers.put("item_name_", this::handleItemName);
        placeholderHandlers.put("price_sell_", (p, s) -> handlePrice(s, false));
        placeholderHandlers.put("price_buy_", (p, s) -> handlePrice(s, true));
        placeholderHandlers.put("trend_", this::handleTrend);
        placeholderHandlers.put("daily_remaining_", this::handleDailyRemaining);
        placeholderHandlers.put("item_category_", this::handleItemCategory);
    }

    @Override
    public @NotNull String getIdentifier() { return "kyochigo"; }
    @Override
    public @NotNull String getAuthor() { return "Kyochigo"; }
    @Override
    public @NotNull String getVersion() { return "3.1.0"; } 
    @Override
    public boolean persist() { return true; }

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) return "";

        // 1. 环境因子 (数值与文案)
        if (params.equalsIgnoreCase("env_note")) return marketManager.getLastEnvNote();
        if (params.equalsIgnoreCase("env_index")) return String.format("%.2f", marketManager.getLastEnvIndex());

        // 2. 交易会话数据
        TradeData data = tradeCache.get(player.getUniqueId());
        if (data != null) {
            if (params.equalsIgnoreCase("session_total")) return String.format("%.2f", data.totalPrice);
            if (params.equalsIgnoreCase("session_type")) return data.isBuy ? "购买" : "出售";
            if (params.equalsIgnoreCase("session_item")) return data.displayName;
        }

        // 3. 动态属性处理器
        for (Map.Entry<String, BiFunction<Player, String, String>> entry : placeholderHandlers.entrySet()) {
            if (params.startsWith(entry.getKey())) {
                String arg = params.substring(entry.getKey().length());
                return entry.getValue().apply(player, arg);
            }
        }

        return null; 
    }

    private String handleBalance(Player player, String itemKey) {
        MarketItem item = getCachedItem(itemKey);
        return item != null ? String.valueOf(inventoryManager.countItems(player, item)) : "0";
    }

    private String handleItemName(Player player, String itemKey) {
        MarketItem item = getCachedItem(itemKey);
        return item != null ? item.getPlainDisplayName() : "未知物品";
    }

    private String handlePrice(String itemKey, boolean isBuy) {
        MarketItem item = getCachedItem(itemKey);
        if (item == null) return "0.00";
        // 这里的 getBuyPrice() 已经包含了 Rust 后端传回的实时价格计算逻辑
        double price = isBuy ? item.getBuyPrice() : item.getSellPrice();
        return String.format("%.2f", price);
    }

    private String handleTrend(Player player, String itemKey) {
        MarketItem item = getCachedItem(itemKey);
        if (item == null) return "";
        double current = item.getSellPrice();
        double base = item.getBasePrice();
        
        if (current > base * 1.01) return "§a↑"; // 1% 波动才显示箭头
        if (current < base * 0.99) return "§c↓";
        return "§7-";
    }

    private String handleDailyRemaining(Player player, String itemKey) {
        int limit = plugin.getConfiguration().getItemDailyLimit(itemKey);
        if (limit <= 0) return "∞";
        
        int traded = plugin.getHistoryManager().getDailyTradeCount(player.getUniqueId().toString(), itemKey);
        return String.valueOf(Math.max(0, limit - traded));
    }

    private String handleItemCategory(Player player, String itemKey) {
        MarketItem item = getCachedItem(itemKey);
        if (item == null) return "未知";
        return plugin.getConfiguration().getRaw().getString("categories." + item.getCategory() + ".name", item.getCategory());
    }

    private MarketItem getCachedItem(String key) {
        return itemCache.computeIfAbsent(key, k -> {
            MarketItem item = marketManager.findMarketItemByKey(k);
            if (item != null) {
                Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> itemCache.remove(k), CACHE_TTL_TICKS);
            }
            return item;
        });
    }
}