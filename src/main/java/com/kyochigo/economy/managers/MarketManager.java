package com.kyochigo.economy.managers;

import com.kyochigo.economy.model.MarketItem;
import com.kyochigo.economy.utils.CraftEngineHook;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public class MarketManager {
    private final JavaPlugin plugin;
    private final CraftEngineHook craftEngineHook;
    private final List<MarketItem> loadedMarketItems = new ArrayList<>();

    public MarketManager(JavaPlugin plugin, CraftEngineHook craftEngineHook) {
        this.plugin = plugin;
        this.craftEngineHook = craftEngineHook;
    }

    /**
     * 加载市场物品
     */
    public void loadItems() {
        loadedMarketItems.clear();
        ConfigurationSection itemsSection = plugin.getConfig().getConfigurationSection("items");
        if (itemsSection != null) {
            for (String key : itemsSection.getKeys(false)) {
                String type = itemsSection.getString(key + ".type", "MATERIAL");
                String id = itemsSection.getString(key + ".id");
                String name = itemsSection.getString(key + ".name", null);
                String icon = itemsSection.getString(key + ".icon", null);
                String cat = itemsSection.getString(key + ".category");
                double price = itemsSection.getDouble(key + ".base_price");
                double lambda = itemsSection.getDouble(key + ".lambda");
                boolean buy = itemsSection.getBoolean(key + ".allow_buy", true);
                boolean sell = itemsSection.getBoolean(key + ".allow_sell", true);
                
                loadedMarketItems.add(new MarketItem(key, type, id, name, icon, cat, price, lambda, buy, sell));
            }
        }
        plugin.getLogger().info("已加载 " + loadedMarketItems.size() + " 个市场物品。");
    }

    /**
     * 获取所有物品（用于指令和GUI）
     */
    public List<MarketItem> getAllItems() {
        return new ArrayList<>(this.loadedMarketItems);
    }

    /**
     * 核心优化：获取格式化后的对齐价格字符串
     * 该方法返回处理后的价格，确保无论数字多大，占用的字符宽度一致
     */
    public String getFormattedSellPrice(MarketItem item) {
        // 使用 %-8.1f：负号表示左对齐，8表示总宽度，.1表示一位小数
        // 这样 $10.0 和 $5000.0 都会占用相同的横向空间
        return String.format("$%-8.1f", item.getSellPrice());
    }

    public String getFormattedBuyPrice(MarketItem item) {
        return String.format("$%-8.1f", item.getBuyPrice());
    }

    public List<MarketItem> getLoadedMarketItems() {
        return loadedMarketItems;
    }

    public MarketItem findMarketItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return null;
        for (MarketItem mi : loadedMarketItems) {
            if (mi.matches(item, craftEngineHook)) return mi;
        }
        return null;
    }
    
    public MarketItem findMarketItemByKey(String key) {
        for (MarketItem mi : loadedMarketItems) {
            if (mi.getConfigKey().equalsIgnoreCase(key)) return mi;
        }
        return null;
    }
}