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
 * KyochigoEconomy PAPI 扩展 (v3.0 性能优化版)
 * 优化内容：
 * 1. 使用 Map 映射处理器，移除长 If-Else 链
 * 2. 增加 MarketItem 查询缓存 (TTL 60s)
 * 3. 逻辑解耦，提升可维护性
 */
public class KyochigoExpansion extends PlaceholderExpansion {
    
    private final KyochigoPlugin plugin;
    private final InventoryManager inventoryManager;
    private final MarketManager marketManager;
    private final Map<UUID, TradeData> tradeCache;

    // 处理器映射表
    private final Map<String, BiFunction<Player, String, String>> placeholderHandlers = new HashMap<>();
    
    // 物品查询缓存 (Key -> MarketItem)
    private final Map<String, MarketItem> itemCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_TICKS = 1200L; // 60秒

    public KyochigoExpansion(KyochigoPlugin plugin, InventoryManager inventoryManager, MarketManager marketManager, Map<UUID, TradeData> tradeCache) {
        this.plugin = plugin;
        this.inventoryManager = inventoryManager;
        this.marketManager = marketManager;
        this.tradeCache = tradeCache;
        
        initializeHandlers();
    }

    private void initializeHandlers() {
        // 注册各类占位符处理器
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
    public @NotNull String getVersion() { return "3.0.0"; } 
    
    @Override
    public boolean persist() { return true; }

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) return "";

        // 1. 优先处理不需要物品Key的特殊占位符
        if (params.equalsIgnoreCase("env_note")) {
            return marketManager.getLastEnvNote();
        }

        // 2. 处理交易会话缓存 (Session Data)
        TradeData data = tradeCache.get(player.getUniqueId());
        if (data != null) {
            if (params.equalsIgnoreCase("session_total")) return String.format("%.2f", data.totalPrice);
            if (params.equalsIgnoreCase("session_type")) return data.isBuy ? "购买" : "出售";
        }

        // 3. 动态匹配处理器 (Map 查找)
        // 遍历所有注册的前缀，找到匹配的处理器
        for (Map.Entry<String, BiFunction<Player, String, String>> entry : placeholderHandlers.entrySet()) {
            String prefix = entry.getKey();
            if (params.startsWith(prefix)) {
                // 截取参数部分 (去除前缀) 并调用处理器
                String arg = params.substring(prefix.length());
                return entry.getValue().apply(player, arg);
            }
        }

        return null; 
    }

    // ========================================================================
    // 内部逻辑处理器 (Handlers)
    // ========================================================================

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
        double price = isBuy ? item.getBuyPrice() : item.getSellPrice();
        return String.format("%.2f", price);
    }

    private String handleTrend(Player player, String itemKey) {
        MarketItem item = getCachedItem(itemKey);
        if (item == null) return "";
        double current = item.getSellPrice();
        double base = item.getBasePrice();
        
        if (current > base) return "§a↑";
        if (current < base) return "§c↓";
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
        // 注意：Config 读取通常很快，但如果分类很多，这里也可以考虑加一层简单的 String 缓存
        return plugin.getConfiguration().getRaw().getString("categories." + item.getCategory() + ".name", item.getCategory());
    }

    // ========================================================================
    // 辅助方法 (Helpers)
    // ========================================================================

    /**
     * 带缓存的物品查询方法
     * 避免在高频 PAPI 请求中频繁遍历 List
     */
    private MarketItem getCachedItem(String key) {
        return itemCache.computeIfAbsent(key, k -> {
            MarketItem item = marketManager.findMarketItemByKey(k);
            if (item != null) {
                // 设置缓存过期清理
                Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> itemCache.remove(k), CACHE_TTL_TICKS);
            }
            return item;
        });
    }
}