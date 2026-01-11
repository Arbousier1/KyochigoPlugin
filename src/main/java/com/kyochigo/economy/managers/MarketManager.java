package com.kyochigo.economy.managers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.kyochigo.economy.KyochigoPlugin;
import com.kyochigo.economy.gui.MarketDialog;
import com.kyochigo.economy.model.MarketItem;
import com.kyochigo.economy.utils.CraftEngineHook;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 市场物品管理器 (v3.1 兼容性修复版)
 * 职责：适配 MarketItem Builder 模式及枚举类型。
 */
public class MarketManager {

    private final KyochigoPlugin plugin;
    private final CraftEngineHook craftEngineHook;
    
    private final List<MarketItem> loadedMarketItems = new ArrayList<>();
    private final Map<String, MarketItem> itemByKey = new ConcurrentHashMap<>();
    private final Map<String, ItemStack> iconCache = new ConcurrentHashMap<>();

    private double lastEnvIndex = 1.0;
    private String lastEnvNote = "Normal";

    public MarketManager(KyochigoPlugin plugin, CraftEngineHook craftEngineHook) {
        this.plugin = plugin;
        this.craftEngineHook = craftEngineHook;
    }

    public void loadItems() {
        loadedMarketItems.clear();
        iconCache.clear();
        itemByKey.clear();

        ConfigurationSection itemsSection = plugin.getConfiguration().getItemsSection();
        if (itemsSection != null) {
            itemsSection.getKeys(false).forEach(key -> {
                ConfigurationSection itemData = itemsSection.getConfigurationSection(key);
                if (itemData != null) {
                    MarketItem marketItem = createMarketItem(key, itemData);
                    loadedMarketItems.add(marketItem);
                    itemByKey.put(key.toLowerCase(), marketItem);
                    preheatIconCache(marketItem);
                }
            });
        }
        if (!loadedMarketItems.isEmpty()) reSyncToBackend();
    }

    /**
     * [修复 1] 使用 MarketItem.Builder 替换 undefined 的构造函数
     */
    private MarketItem createMarketItem(String key, ConfigurationSection data) {
        return new MarketItem.Builder()
                .key(key)
                .type(data.getString("type", "MATERIAL"))
                .id(data.getString("id"))
                .customName(data.getString("custom_name")) // 注意配置键名对齐
                .iconMaterial(data.getString("icon"))
                .category(data.getString("category", "misc"))
                .basePrice(data.getDouble("base_price"))
                .lambda(data.getDouble("lambda"))
                .allowBuy(data.getBoolean("allow_buy", true))
                .allowSell(data.getBoolean("allow_sell", true))
                .initialN(data.getInt("n", 0))
                .build();
    }

    public void updateInternalData(@NotNull JsonObject response) {
        if (plugin.getConfiguration().isDebug()) {
            plugin.getLogger().info("[DEBUG] 行情解析: " + response);
        }

        Optional.ofNullable(response.get("envIndex")).ifPresent(e -> this.lastEnvIndex = e.getAsDouble());
        Optional.ofNullable(response.get("envNote")).ifPresent(e -> this.lastEnvNote = e.getAsString());

        Optional.ofNullable(response.getAsJsonObject("items")).ifPresent(itemsMap -> {
            itemsMap.entrySet().forEach(entry -> {
                Optional.ofNullable(itemByKey.get(entry.getKey().toLowerCase())).ifPresent(item -> {
                    JsonObject status = entry.getValue().getAsJsonObject();
                    
                    Optional.ofNullable(status.get("sellPrice"))
                            .or(() -> Optional.ofNullable(status.get("price")))
                            .ifPresent(p -> item.setTempPrice(p.getAsDouble()));

                    Optional.ofNullable(status.get("buyPrice")).ifPresent(p -> item.setTempBuyPrice(p.getAsDouble()));
                    Optional.ofNullable(status.get("neff")).ifPresent(p -> item.setTempNeff(p.getAsDouble()));
                });
            });
        });
    }

    /**
     * [修复 2] 将 getType() 替换为 getItemType() 枚举对比
     */
    private void preheatIconCache(MarketItem item) {
        ItemStack stack = Optional.ofNullable(item.getIcon(craftEngineHook))
                .or(() -> {
                    // 使用枚举类型判定替代字符串判定
                    if (item.getItemType() == MarketItem.ItemType.MATERIAL) {
                        Material mat = Material.matchMaterial(item.getId());
                        if (mat != null) return Optional.of(new ItemStack(mat));
                    }
                    return Optional.empty();
                })
                .orElse(new ItemStack(Material.BARRIER));

        iconCache.put(item.getConfigKey(), stack);
    }

    public MarketItem findMarketItemByKey(String key) {
        return (key == null) ? null : itemByKey.get(key.toLowerCase());
    }

    public MarketItem findMarketItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return null;
        return loadedMarketItems.stream()
                .filter(mi -> mi.matches(item, craftEngineHook))
                .findFirst()
                .orElse(null);
    }

    public ItemStack getItemIcon(MarketItem item) {
        return Optional.ofNullable(iconCache.get(item.getConfigKey()))
                .map(ItemStack::clone)
                .orElse(new ItemStack(Material.BARRIER));
    }

    public void reSyncToBackend() {
        if (loadedMarketItems.isEmpty()) return;
        JsonArray jsonArray = new JsonArray();
        loadedMarketItems.forEach(item -> jsonArray.add(item.toJsonObject()));
        plugin.getBackendManager().syncMarketData(jsonArray, success -> {
            if (success) plugin.getLogger().info("✅ 后端物品数据对齐成功 (" + loadedMarketItems.size() + " items)");
        });
    }

    public void fetchMarketPricesAndOpenGui(Player player, boolean viewOnly) {
        List<String> itemIds = loadedMarketItems.stream()
                .map(MarketItem::getConfigKey)
                .collect(Collectors.toList());

        plugin.getBackendManager().fetchBulkPrices(itemIds, response -> {
            Optional.ofNullable(response).ifPresentOrElse(res -> {
                if (res.has("items") && res.getAsJsonObject("items").size() == 0 && !loadedMarketItems.isEmpty()) {
                    plugin.getLogger().warning("检测到后端映射丢失，执行重同步...");
                    reSyncToBackend();
                }
                updateInternalData(res);
            }, () -> {
                if (player != null) player.sendMessage("§8[§bKyochigo§8] §c通信链路中断，显示本地参考价。");
            });

            if (player != null) MarketDialog.open(player, viewOnly);
        });
    }

    public List<MarketItem> getAllItems() { return List.copyOf(loadedMarketItems); }
    public double getLastEnvIndex() { return lastEnvIndex; }
    public String getLastEnvNote() { return lastEnvNote; }
    public CraftEngineHook getCraftEngineHook() { return this.craftEngineHook; }
    public MarketItem getItem(String key) { return findMarketItemByKey(key); }
}