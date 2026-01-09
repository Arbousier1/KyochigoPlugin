package com.kyochigo.economy.managers;

import com.google.gson.JsonObject;
import com.kyochigo.economy.KyochigoPlugin;
import com.kyochigo.economy.TradeData;
import com.kyochigo.economy.gui.TransactionDialog; // 导入新 GUI 类
import com.kyochigo.economy.model.MarketItem;
import net.kyori.adventure.text.Component;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;

public class TransactionManager {
    private final KyochigoPlugin plugin;
    private final InventoryManager inventoryManager;
    private final BackendManager backendManager;
    private final Economy economy;
    private final Map<UUID, TradeData> tradeCache;

    public TransactionManager(KyochigoPlugin plugin, InventoryManager inventoryManager, BackendManager backendManager, Economy economy, Map<UUID, TradeData> tradeCache) {
        this.plugin = plugin;
        this.inventoryManager = inventoryManager;
        this.backendManager = backendManager;
        this.economy = economy;
        this.tradeCache = tradeCache;
    }

    public void setTradeCache(UUID uuid, String key, String name, String mat, int amt, double unit, double total, double env) {
        tradeCache.put(uuid, new TradeData(key, name, mat, amt, unit, total, env));
    }

    /**
     * 业务逻辑：执行最终的交易扣除和发钱
     */
    public void executeTransaction(Player p, MarketItem item, int amountNeeded) {
        UUID uuid = p.getUniqueId();
        TradeData data = tradeCache.get(uuid);

        if (data == null || !data.configKey.equals(item.getConfigKey()) || data.amount != amountNeeded) {
            p.sendMessage(Component.text("§c交易数据已失效或已过期，请重新打开窗口。"));
            return;
        }

        if (!inventoryManager.hasEnoughItems(p, item, amountNeeded)) {
            p.sendMessage(Component.text("§c背包内物品不足，交易取消。"));
            return;
        }

        String historyKey = backendManager.getHistoryKey(uuid.toString(), item.getConfigKey());
        double startN = backendManager.getHistoryConfig().getDouble(historyKey, 0.0);

        JsonObject req = new JsonObject();
        req.addProperty("base_price", item.getBasePrice());
        req.addProperty("start_n", startN);
        req.addProperty("amount", (double) amountNeeded);
        req.addProperty("decay_lambda", item.getLambda());
        req.addProperty("is_preview", false);
        req.addProperty("manual_env_index", data.envIndex);

        backendManager.sendCalculateRequest(req, json -> {
            if (json == null) {
                p.sendMessage(Component.text("§c交易失败，服务器拒绝。"));
                return;
            }

            try {
                double backendTotalPrice = json.get("total_price").getAsDouble();
                if (Math.abs(backendTotalPrice - data.totalPrice) > 0.01) {
                    p.sendMessage(Component.text("§c价格发生意外变动，交易终止。"));
                    return;
                }

                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (inventoryManager.removeItemSafe(p, item, amountNeeded)) {
                        backendManager.getHistoryConfig().set(historyKey, startN + amountNeeded);
                        backendManager.saveHistory();

                        if (economy != null) {
                            economy.depositPlayer(p, backendTotalPrice);
                        }
                        
                        tradeCache.remove(uuid);
                        p.sendMessage(Component.text(String.format("§a✔ 出售成功！获得: §e$%.2f", backendTotalPrice)));
                        p.closeDialog(); 
                    } else {
                        p.sendMessage(Component.text("§c扣除物品失败 (可能物品已被移动)。"));
                        p.closeDialog();
                    }
                });
            } catch (Exception e) {
                p.sendMessage(Component.text("§c交易数据异常。"));
            }
        });
    }

    /**
     * 预处理：获取后端实时价格，然后调用 GUI 打开对话框
     */
    public void openSellConfirmDialog(Player player, MarketItem item, int amount, double totalPrice, double unitPrice) {
        String historyKey = backendManager.getHistoryKey(player.getUniqueId().toString(), item.getConfigKey());
        double startN = backendManager.getHistoryConfig().getDouble(historyKey, 0.0);

        JsonObject req = new JsonObject();
        req.addProperty("base_price", item.getBasePrice());
        req.addProperty("start_n", startN);
        req.addProperty("amount", (double) amount);
        req.addProperty("decay_lambda", item.getLambda());
        req.addProperty("is_preview", true); 

        backendManager.sendCalculateRequest(req, json -> {
            if (json == null) {
                player.sendMessage(Component.text("§c交易所连接失败。"));
                return;
            }

            double realTotalPrice = json.get("total_price").getAsDouble();
            double realUnitPrice = json.get("unit_price_avg").getAsDouble();
            double envIndex = json.get("env_index").getAsDouble();

            Bukkit.getScheduler().runTask(plugin, () -> {
                // 更新缓存
                setTradeCache(player.getUniqueId(), item.getConfigKey(), item.getDisplayName(), item.getMaterialName(), amount, realUnitPrice, realTotalPrice, envIndex);
                
                // [关键调用] 调用剥离出的 GUI 类打开对话框
                TransactionDialog.openConfirm(player, item, amount, realTotalPrice, realUnitPrice, this);
            });
        });
    }
}