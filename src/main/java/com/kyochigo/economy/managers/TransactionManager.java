package com.kyochigo.economy.managers;

import com.kyochigo.economy.KyochigoPlugin;
import com.kyochigo.economy.TradeData;
import com.kyochigo.economy.gui.TransactionDialog;
import com.kyochigo.economy.model.MarketItem;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;

/**
 * 交易执行管理器 (v3.3 健壮修正版)
 * <p>
 * 修正内容：
 * 1. 增加 setEconomy 方法以支持延迟注入。
 * 2. 增加主线程调度，防止异步操作 Bukkit API。
 * 3. 增强空指针防御。
 */
public class TransactionManager {

    // --- 消息常量 ---
    private static final String PREFIX = "§8[§bKyochigo§8] ";
    private static final String ERR_EXPIRED = "§c交易会话已过期，请重新打开菜单。";
    private static final String ERR_BACKEND = "§c计算失败：后端未响应。";
    private static final String ERR_LOCK_FAIL = "§c§l致命错误：§f汇率锁定失败，交易拦截！";
    private static final String MSG_LOCKING = "§7正在与核心同步交易数据...";

    private final KyochigoPlugin plugin;
    private final InventoryManager inventoryManager;
    private final BackendManager backendManager;
    private final HistoryManager historyManager;
    
    // 移除 final，允许后期注入
    private Economy economy;
    private final Map<UUID, TradeData> tradeCache;

    public TransactionManager(KyochigoPlugin plugin, InventoryManager inv, BackendManager backend, Economy eco, Map<UUID, TradeData> cache) {
        this.plugin = plugin;
        this.inventoryManager = inv;
        this.backendManager = backend;
        this.economy = eco; // 可能为 null，需要后续注入
        this.tradeCache = cache;
        this.historyManager = plugin.getHistoryManager();
    }

    // ★★★ 关键修复：允许主类在 Vault 加载后注入 Economy ★★★
    public void setEconomy(Economy economy) {
        this.economy = economy;
    }

    // =========================================================================
    // 1. 弹窗与预检 (UI Entry - Preview Phase)
    // =========================================================================

    public void openBuyConfirmDialog(Player p, MarketItem i, int amt) { requestPriceAndOpen(p, i, amt, "buy"); }
    public void openSellConfirmDialog(Player p, MarketItem i, int amt) { requestPriceAndOpen(p, i, amt, "sell"); }

    private void requestPriceAndOpen(Player player, MarketItem item, int amount, String action) {
        // [预览阶段] 请求后端报价
        backendManager.sendCalculateRequest(player, action, item.getConfigKey(), (double) amount,
                item.getBasePrice(), item.getLambda(), null, true, response -> {
                    
                    // 切换回主线程处理 UI (防止异步操作报错)
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (response == null || !response.has("totalPrice")) {
                            sendMsg(player, ERR_BACKEND);
                            return;
                        }

                        double unitPrice = response.get("unitPriceAvg").getAsDouble();
                        double currentEnv = response.get("envIndex").getAsDouble(); // 锁定汇率

                        TradeData data = new TradeData(item.getConfigKey(), item.getPlainDisplayName(), 
                            item.getMaterial().name(), amount, unitPrice, 
                            response.get("totalPrice").getAsDouble(), 
                            currentEnv, action.equals("buy"));

                        tradeCache.put(player.getUniqueId(), data);

                        // 打开 GUI
                        if (data.isBuy) TransactionDialog.openBuyConfirm(player, item, amount, unitPrice);
                        else TransactionDialog.openSellConfirm(player, item, amount, unitPrice);
                    });
                });
    }

    // =========================================================================
    // 2. 核心执行流 (Execution Phase - Commit)
    // =========================================================================

    public void executeBuy(Player p, MarketItem i, int a, double pr) { executeTransaction(p, i, a); }
    public void executeSell(Player p, MarketItem i, int a, double pr) { executeTransaction(p, i, a); }

    public void executeTransaction(Player player, MarketItem item, int amount) {
        UUID uuid = player.getUniqueId();
        TradeData snapshot = tradeCache.get(uuid);

        if (snapshot == null) {
            sendMsg(player, ERR_EXPIRED);
            return;
        }

        // 检查 Economy 是否就绪
        if (economy == null) {
            sendMsg(player, "§c严重错误：经济系统未连接，请联系管理员。");
            plugin.getLogger().severe("Economy not injected into TransactionManager!");
            return;
        }

        int finalAmount = calculateAdjustedAmount(player, item, amount);
        if (finalAmount <= 0) return;

        if (!isAssetCheckPassed(player, item, finalAmount, snapshot.isBuy)) return;

        sendMsg(player, MSG_LOCKING);
        
        // [核心] 锁定汇率请求
        backendManager.sendCalculateRequest(player, snapshot.isBuy ? "buy" : "sell", item.getConfigKey(), 
            (double) finalAmount, item.getBasePrice(), item.getLambda(), snapshot.envIndex, false, response -> {
                
                // 切换回主线程执行交易
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (response == null || !response.has("totalPrice")) {
                        sendMsg(player, ERR_LOCK_FAIL);
                        tradeCache.remove(uuid);
                        return;
                    }
                    
                    double finalPrice = response.get("totalPrice").getAsDouble();
                    finalizeAssetSwap(player, item, snapshot, finalAmount, finalPrice);
                });
            });
    }

    // =========================================================================
    // 3. 资产交换逻辑 (Asset Swapping)
    // =========================================================================

    private void finalizeAssetSwap(Player player, MarketItem item, TradeData snapshot, int amount, double price) {
        if (snapshot.isBuy && !economy.has(player, price)) {
            sendMsg(player, "§c余额不足，交易取消。");
            tradeCache.remove(player.getUniqueId());
            return;
        }

        boolean isSuccess = snapshot.isBuy 
                ? performBuyAction(player, item, amount, price) 
                : performSellAction(player, item, amount, price);

        if (isSuccess) {
            handleTransactionSuccess(player, item, amount, price, snapshot.isBuy);
        }
        
        tradeCache.remove(player.getUniqueId());
    }

    private boolean performBuyAction(Player player, MarketItem item, int amount, double price) {
        economy.withdrawPlayer(player, price);
        inventoryManager.giveItems(player, item, amount);
        return true;
    }

    private boolean performSellAction(Player player, MarketItem item, int amount, double price) {
        // 使用具体的移除逻辑，避免 NBT 问题
        if (!inventoryManager.removeItems(player, item, amount)) {
            sendMsg(player, "§c背包内物品不足或不匹配。");
            return false;
        }
        economy.depositPlayer(player, price);
        return true;
    }

    // =========================================================================
    // 4. 辅助校验器 (Validators & Utils)
    // =========================================================================

    private int calculateAdjustedAmount(Player player, MarketItem item, int amount) {
        int limit = plugin.getConfiguration().getItemDailyLimit(item.getConfigKey());
        if (limit <= 0) return amount;

        int traded = historyManager.getDailyTradeCount(player.getUniqueId().toString(), item.getConfigKey());
        
        if (traded >= limit) {
            sendMsg(player, "§c§l交易拒绝！§7今日额度已达上限 (§f" + limit + "§7)。");
            return 0;
        }
        
        if (traded + amount > limit) {
            int adjusted = limit - traded;
            sendMsg(player, "§e提示: §7因触发限额，交易数量已自动调整为 §a" + adjusted + " §7个。");
            return adjusted;
        }
        return amount;
    }

    private boolean isAssetCheckPassed(Player player, MarketItem item, int amount, boolean isBuy) {
        if (isBuy) {
            // 需要 InventoryManager 提供此方法
            // 如果没有，可以简单判断 player.getInventory().firstEmpty() != -1
            if (!inventoryManager.hasSpaceForItem(player, item, amount)) {
                sendMsg(player, "§c背包空间不足，请整理后再试。");
                return false;
            }
        } else {
            if (!inventoryManager.hasEnoughItems(player, item, amount)) {
                sendMsg(player, "§c物品数量不足或不匹配 (请检查NBT/耐久)。");
                return false;
            }
        }
        return true;
    }

    private void handleTransactionSuccess(Player p, MarketItem item, int amt, double price, boolean isBuy) {
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        
        String actionText = isBuy ? "§a§l购买成功" : "§a§l出售成功";
        String moneyText = isBuy ? "§f花费 §e" : "§f获得 §e";
        
        sendMsg(p, String.format("%s！ %s%.2f", actionText, moneyText, price));
        
        historyManager.incrementTradeCount(p.getUniqueId().toString(), item.getConfigKey(), amt);
        historyManager.saveAsync();
    }

    private void sendMsg(Player p, String msg) { p.sendMessage(PREFIX + msg); }
}