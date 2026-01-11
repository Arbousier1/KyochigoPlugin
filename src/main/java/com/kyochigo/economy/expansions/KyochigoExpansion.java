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
 * KyochigoEconomy PAPI 扩展 (v3.2 最终修正版)
 * 修正点：
 * 1. 价格计算逻辑增加 envIndex 乘数，与 GUI 保持绝对对齐。
 * 2. 汉化了 env_note 的输出。
 * 3. 修正了趋势判断的基准值。
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
    public @NotNull String getVersion() { return "3.2.0"; } 
    @Override
    public boolean persist() { return true; }

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) return "";

        // 1. 环境因子 (增加汉化映射，与 GUI 状态对齐)
        if (params.equalsIgnoreCase("env_note")) {
            String rawNote = marketManager.getLastEnvNote();
            return switch (rawNote.toLowerCase()) {
                case "normal" -> "行情平稳";
                case "weekend" -> "周末特惠";
                case "prosperous" -> "贸易繁荣";
                case "depressed" -> "行情低迷";
                default -> rawNote;
            };
        }
        
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
        // 使用 getPlainDisplayName 以确保获取的是经过汉化处理的名称
        return item != null ? item.getPlainDisplayName() : "未知物品";
    }

    /**
     * 核心修正：价格获取逻辑增加环境指数加成
     */
    private String handlePrice(String itemKey, boolean isBuy) {
        MarketItem item = getCachedItem(itemKey);
        if (item == null) return "0.00";
        
        // 获取实时环境指数
        double envIndex = marketManager.getLastEnvIndex();
        // 获取基础实时价格 (来自后端推送)
        double basePrice = isBuy ? item.getBuyPrice() : item.getSellPrice();
        
        // 返回 最终单价 = 基础实时价 * 环境指数
        return String.format("%.2f", basePrice * envIndex);
    }

    /**
     * 核心修正：趋势判断逻辑同步应用环境指数
     */
    private String handleTrend(Player player, String itemKey) {
        MarketItem item = getCachedItem(itemKey);
        if (item == null) return "";
        
        double envIndex = marketManager.getLastEnvIndex();
        // 当前最终售价
        double current = item.getSellPrice() * envIndex;
        // 配置的基础参考价
        double base = item.getBasePrice();
        
        if (current > base * 1.01) return "§a↑"; // 涨幅超过 1%
        if (current < base * 0.99) return "§c↓"; // 跌幅超过 1%
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