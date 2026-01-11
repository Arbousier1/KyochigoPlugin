package com.kyochigo.economy.managers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kyochigo.economy.KyochigoPlugin;
import com.kyochigo.economy.gui.MarketDialog;
import com.kyochigo.economy.model.MarketItem;
import com.kyochigo.economy.utils.CraftEngineHook;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class MarketManager {

    private final KyochigoPlugin plugin;
    private final Logger log;
    private final CraftEngineHook craftEngineHook;
    
    private final List<MarketItem> loadedMarketItems = new ArrayList<>();
    private final Map<String, MarketItem> itemByKey = new ConcurrentHashMap<>();
    private final Map<String, ItemStack> iconCache = new ConcurrentHashMap<>();

    private double lastEnvIndex = 1.0;
    private String lastEnvNote = "Normal";

    public MarketManager(KyochigoPlugin plugin, CraftEngineHook craftEngineHook) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.craftEngineHook = craftEngineHook;
    }

    public void loadItems() {
        log.info("[DEBUG] 开始加载本地配置文件 items.yml...");
        loadedMarketItems.clear();
        iconCache.clear();
        itemByKey.clear();

        ConfigurationSection itemsSection = plugin.getConfiguration().getItemsSection();
        if (itemsSection != null) {
            Set<String> keys = itemsSection.getKeys(false);
            log.info("[DEBUG] 发现 " + keys.size() + " 个物品配置项。");
            
            keys.forEach(key -> {
                ConfigurationSection itemData = itemsSection.getConfigurationSection(key);
                if (itemData != null) {
                    MarketItem marketItem = createMarketItem(key, itemData);
                    loadedMarketItems.add(marketItem);
                    itemByKey.put(key.toLowerCase(), marketItem);
                    preheatIconCache(marketItem);
                    log.info("[DEBUG] 已注册本地物品: " + key + " (ID: " + marketItem.getId() + ")");
                }
            });
        }
        if (!loadedMarketItems.isEmpty()) {
            log.info("[DEBUG] 正在触发启动同步...");
            reSyncToBackend();
        }
    }

    private MarketItem createMarketItem(String key, ConfigurationSection data) {
        return new MarketItem.Builder()
                .key(key)
                .type(data.getString("type", "MATERIAL")) 
                .id(data.getString("id"))
                .customName(data.getString("custom_name"))
                .iconMaterial(data.getString("icon"))
                .category(data.getString("category", "misc"))
                .basePrice(data.getDouble("base_price"))
                .lambda(data.getDouble("lambda"))
                .allowBuy(data.getBoolean("allow_buy", true))
                .allowSell(data.getBoolean("allow_sell", true))
                .initialN(data.getInt("n", 0))
                .build();
    }

    /**
     * 【核心 Debug 解析】
     */
    public void updateInternalData(@NotNull JsonObject response) {
        log.info("[DEBUG] ============= 收到后端数据更新包 =============");
        log.info("[DEBUG] 原始数据: " + response.toString());

        // 1. 解析环境
        if (response.has("envIndex")) {
            this.lastEnvIndex = response.get("envIndex").getAsDouble();
            log.info("[DEBUG] 环境指数更新: " + lastEnvIndex);
        }
        if (response.has("envNote")) {
            this.lastEnvNote = response.get("envNote").getAsString();
            log.info("[DEBUG] 环境描述更新: " + lastEnvNote);
        }

        // 2. 解析物品列表
        if (!response.has("items")) {
            log.warning("[DEBUG] 同步失败：响应包中缺失 'items' 字段！");
            return;
        }

        JsonElement itemsEl = response.get("items");
        int successCount = 0;

        // 如果 items 是 Object (Map)
        if (itemsEl.isJsonObject()) {
            log.info("[DEBUG] 识别为 Map 结构数据格式。");
            JsonObject itemsMap = itemsEl.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : itemsMap.entrySet()) {
                if (parseAndInjectItem(entry.getKey(), entry.getValue().getAsJsonObject())) {
                    successCount++;
                }
            }
        } 
        // 如果 items 是 Array
        else if (itemsEl.isJsonArray()) {
            log.info("[DEBUG] 识别为 List 结构数据格式。");
            JsonArray itemsArray = itemsEl.getAsJsonArray();
            for (JsonElement el : itemsArray) {
                if (!el.isJsonObject()) continue;
                JsonObject itemObj = el.getAsJsonObject();
                if (itemObj.has("id")) {
                    if (parseAndInjectItem(itemObj.get("id").getAsString(), itemObj)) {
                        successCount++;
                    }
                }
            }
        }

        log.info("[DEBUG] 同步流程结束。预期物品: " + loadedMarketItems.size() + " | 成功更新: " + successCount);
        log.info("[DEBUG] ===============================================");
    }

    private boolean parseAndInjectItem(String id, JsonObject data) {
        String lookupKey = id.toLowerCase();
        MarketItem item = itemByKey.get(lookupKey);
        
        if (item == null) {
            log.warning("[DEBUG] 匹配失败：后端返回了 ID '" + id + "'，但本地配置找不到对应的 Key。");
            return false;
        }

        // 尝试解析价格
        double sellPrice = data.has("sellPrice") ? data.get("sellPrice").getAsDouble() : 
                          data.has("price") ? data.get("price").getAsDouble() : 0.0;
        
        double buyPrice = data.has("buyPrice") ? data.get("buyPrice").getAsDouble() : 0.0;
        double neff = data.has("neff") ? data.get("neff").getAsDouble() : 0.0;

        item.setTempPrice(sellPrice);
        item.setTempBuyPrice(buyPrice);
        item.setTempNeff(neff);

        log.info(String.format("[DEBUG] 更新成功 [%s]: 售卖=%.2f, 购买=%.2f, 热度=%.2f", 
                item.getConfigKey(), sellPrice, buyPrice, neff));
        
        return true;
    }

    private void preheatIconCache(MarketItem item) {
        ItemStack stack = item.getIcon(craftEngineHook);
        iconCache.put(item.getConfigKey(), stack);
    }

    public void reSyncToBackend() {
        if (loadedMarketItems.isEmpty()) return;
        JsonArray jsonArray = new JsonArray();
        loadedMarketItems.forEach(item -> jsonArray.add(item.toJsonObject()));
        
        log.info("[DEBUG] 正在推送全量同步到后端...");
        log.info("[DEBUG] 推送 Payload: " + jsonArray.toString());
        
        plugin.getBackendManager().syncMarketData(jsonArray, success -> {
            if (success) log.info("✅ [DEBUG] 后端名录对齐成功。");
            else log.severe("❌ [DEBUG] 后端名录对齐失败！请检查 Rust 后端 API。");
        });
    }

    public void fetchMarketPricesAndOpenGui(Player player, boolean viewOnly) {
        List<String> itemIds = loadedMarketItems.stream()
                .map(MarketItem::getConfigKey)
                .collect(Collectors.toList());

        log.info("[DEBUG] 玩家 " + (player != null ? player.getName() : "CONSOLE") + " 触发价格同步请求...");
        log.info("[DEBUG] 请求物品列表: " + itemIds);

        plugin.getBackendManager().fetchBulkPrices(itemIds, response -> {
            // 切回主线程处理 UI 和数据
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (response == null) {
                    log.severe("[DEBUG] 严重错误：fetchBulkPrices 回调返回 null！后端可能崩溃或超时。");
                    if (player != null) player.sendMessage("§c通信异常：后端未响应。");
                    return;
                }

                updateInternalData(response);

                if (player != null) {
                    log.info("[DEBUG] 正在为 " + player.getName() + " 开启行情对话框...");
                    MarketDialog.open(player, viewOnly);
                }
            });
        });
    }

    // =========================================================================
    // Getters
    // =========================================================================

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

    public List<MarketItem> getAllItems() { return List.copyOf(loadedMarketItems); }
    public double getLastEnvIndex() { return lastEnvIndex; }
    public String getLastEnvNote() { return lastEnvNote; }
    public CraftEngineHook getCraftEngineHook() { return this.craftEngineHook; }
    public MarketItem getItem(String key) { return findMarketItemByKey(key); }
}