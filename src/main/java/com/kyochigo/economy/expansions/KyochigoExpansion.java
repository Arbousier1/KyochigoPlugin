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

public class KyochigoExpansion extends PlaceholderExpansion {
    
    private final KyochigoPlugin plugin;
    private final InventoryManager inventoryManager;
    private final MarketManager marketManager;
    private final Map<UUID, TradeData> tradeCache;

    public KyochigoExpansion(KyochigoPlugin plugin, InventoryManager inventoryManager, MarketManager marketManager, Map<UUID, TradeData> tradeCache) {
        this.plugin = plugin;
        this.inventoryManager = inventoryManager;
        this.marketManager = marketManager;
        this.tradeCache = tradeCache;
    }

    @Override
    public @NotNull String getIdentifier() { return "kyochigo"; }
    @Override
    public @NotNull String getAuthor() { return "System"; }
    @Override
    public @NotNull String getVersion() { return "1.6"; } // 更新版本号
    @Override
    public boolean persist() { return true; }

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) return "";

        // 1. 判断玩家是否有足够物品出售: %kyochigo_can_sell_<Key>_<数量>%
        if (params.startsWith("can_sell_")) {
            String strip = params.substring(9); // 提升性能，避免 replace
            int lastUnderscore = strip.lastIndexOf('_');
            if (lastUnderscore != -1) {
                try {
                    String itemKey = strip.substring(0, lastUnderscore);
                    int amountNeeded = Integer.parseInt(strip.substring(lastUnderscore + 1));
                    
                    MarketItem item = marketManager.findMarketItemByKey(itemKey);
                    if (item != null) {
                        // 使用优化后的 hasEnoughItems (高性能提前退出逻辑)
                        return inventoryManager.hasEnoughItems(player, item, amountNeeded) ? "true" : "false";
                    }
                } catch (NumberFormatException ignored) {}
            }
            return "false";
        }

        // 2. 获取当前交易缓存数据
        TradeData data = tradeCache.get(player.getUniqueId());
        if (data != null) {
            switch (params.toLowerCase()) {
                case "config_key": return data.configKey;
                case "item_name": return data.displayName;
                case "material": return data.material;
                case "amount": return String.valueOf(data.amount);
                case "unit_price": return String.format("%.2f", data.unitPrice);
                case "total_price": return String.format("%.2f", data.totalPrice);
                case "env_index": return String.format("%.2f", data.envIndex); // 新增环境指数变量
            }
        }

        return null; // 如果没有匹配的变量，返回 null 让 PAPI 处理
    }
}