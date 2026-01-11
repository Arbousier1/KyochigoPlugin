package com.kyochigo.economy.managers;

import com.kyochigo.economy.KyochigoPlugin;
import com.kyochigo.economy.TradeData;
import com.kyochigo.economy.gui.TransactionDialog;
import com.kyochigo.economy.model.MarketItem;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 交易执行管理器 (v4.1 逻辑对齐版)
 * 职责：管理交易生命周期，确保后端计价与 Java 侧资产交换的绝对一致性。
 */
public class TransactionManager {

    private static final String PREFIX = "§8[§bKyochigo§8] ";
    private static final String ERR_EXPIRED = "§c交易会话已过期，请重新打开菜单。";
    private static final String ERR_BACKEND = "§c计算失败：后端核心未响应。";
    private static final String ERR_LOCK_FAIL = "§c§l致命错误：§f价格锁定失败，交易被安全拦截！";
    private static final String ERR_PROCESSING = "§6请稍候，上一笔业务正在结算中...";
    private static final String MSG_LOCKING = "§7正在接入核心执行资产结算...";

    private final KyochigoPlugin plugin;
    private final InventoryManager inventoryManager;
    private final BackendManager backendManager;
    private final HistoryManager historyManager;
    
    private Economy economy;
    private final Map<UUID, TradeData> tradeCache;
    
    // 交易互斥锁
    private final Set<UUID> processingPlayers = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public TransactionManager(KyochigoPlugin plugin, InventoryManager inv, BackendManager backend, Economy eco, Map<UUID, TradeData> cache) {
        this.plugin = plugin;
        this.inventoryManager = inv;
        this.backendManager = backend;
        this.economy = eco;
        this.tradeCache = cache;
        this.historyManager = plugin.getHistoryManager();
    }

    public void setEconomy(Economy economy) {
        this.economy = economy;
    }

    // =========================================================================
    // 1. 计价预检阶段
    // =========================================================================

    public void openBuyConfirmDialog(Player p, MarketItem i, int amt) { requestPriceAndOpen(p, i, amt, "buy"); }
    public void openSellConfirmDialog(Player p, MarketItem i, int amt) { requestPriceAndOpen(p, i, amt, "sell"); }

    private void requestPriceAndOpen(Player player, MarketItem item, int amount, String action) {
        // 向 Rust 后端请求实时报价
        backendManager.sendCalculateRequest(player, action, item.getConfigKey(), (double) amount,
                item.getBasePrice(), item.getLambda(), null, true, response -> {
                    
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (response == null || !response.has("totalPrice")) {
                            sendMsg(player, ERR_BACKEND);
                            return;
                        }

                        double unitPrice = response.get("unitPriceAvg").getAsDouble();
                        double currentEnv = response.get("envIndex").getAsDouble(); 

                        // 创建交易快照，锁定环境指数以防在确认期间发生变动
                        TradeData data = new TradeData(item.getConfigKey(), item.getPlainDisplayName(), 
                            item.getMaterial().name(), amount, unitPrice, 
                            response.get("totalPrice").getAsDouble(), 
                            currentEnv, action.equals("buy"));

                        tradeCache.put(player.getUniqueId(), data);

                        // 统一术语：调用对齐后的 Dialog
                        if (data.isBuy) TransactionDialog.openBuyConfirm(player, item, amount, unitPrice);
                        else TransactionDialog.openSellConfirm(player, item, amount, unitPrice);
                    });
                });
    }

    // =========================================================================
    // 2. 核心执行阶段
    // =========================================================================

    public void executeTransaction(Player player, MarketItem item, int amount) {
        UUID uuid = player.getUniqueId();
        
        if (processingPlayers.contains(uuid)) {
            sendMsg(player, ERR_PROCESSING);
            return;
        }

        TradeData snapshot = tradeCache.get(uuid);
        if (snapshot == null) {
            sendMsg(player, ERR_EXPIRED);
            return;
        }

        if (economy == null) {
            sendMsg(player, "§c严重错误：经济系统未就绪。");
            return;
        }

        // 数量与限额验证
        int finalAmount = calculateAdjustedAmount(player, item, amount);
        if (finalAmount <= 0) return;
        if (!isAssetCheckPassed(player, item, finalAmount, snapshot.isBuy)) return;

        processingPlayers.add(uuid);
        sendMsg(player, MSG_LOCKING);
        
        // 正式提交：使用快照中的环境指数进行锁定汇率计算
        backendManager.sendCalculateRequest(player, snapshot.isBuy ? "buy" : "sell", item.getConfigKey(), 
            (double) finalAmount, item.getBasePrice(), item.getLambda(), snapshot.envIndex, false, response -> {
                
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        if (response == null || !response.has("totalPrice")) {
                            sendMsg(player, ERR_LOCK_FAIL);
                            return;
                        }
                        
                        double finalPrice = response.get("totalPrice").getAsDouble();
                        finalizeAssetSwap(player, item, snapshot, finalAmount, finalPrice);
                    } finally {
                        processingPlayers.remove(uuid);
                        tradeCache.remove(uuid);
                    }
                });
            });
    }

    // =========================================================================
    // 3. 资产交换逻辑 (安全排序)
    // =========================================================================

    private void finalizeAssetSwap(Player player, MarketItem item, TradeData snapshot, int amount, double price) {
        if (snapshot.isBuy && !economy.has(player, price)) {
            sendMsg(player, "§c账户余额不足，购买取消。");
            return;
        }

        if (snapshot.isBuy) {
            // 购买：先扣钱，确保资金到账再发货
            economy.withdrawPlayer(player, price);
            inventoryManager.giveItems(player, item, amount);
            handleTransactionSuccess(player, item, amount, price, true);
        } else {
            // 售卖：先扣货，扣除成功后再给钱（防止刷物品）
            if (inventoryManager.removeItems(player, item, amount)) {
                economy.depositPlayer(player, price);
                handleTransactionSuccess(player, item, amount, price, false);
            } else {
                sendMsg(player, "§c§l交易失败：§f物品状态异常（可能已离开背包）。");
            }
        }
    }

    // =========================================================================
    // 4. 辅助验证
    // =========================================================================

    private int calculateAdjustedAmount(Player player, MarketItem item, int amount) {
        int limit = plugin.getConfiguration().getItemDailyLimit(item.getConfigKey());
        if (limit <= 0) return amount;

        int traded = historyManager.getDailyTradeCount(player.getUniqueId().toString(), item.getConfigKey());
        if (traded >= limit) {
            sendMsg(player, "§c§l业务拒绝！§7今日额度已达上限 (§f" + limit + "§7)。");
            return 0;
        }
        
        if (traded + amount > limit) {
            int adjusted = limit - traded;
            sendMsg(player, "§e提示: §7受限于配额，交易数量已调整为 §a" + adjusted + " §7个。");
            return adjusted;
        }
        return amount;
    }

    private boolean isAssetCheckPassed(Player player, MarketItem item, int amount, boolean isBuy) {
        if (isBuy) {
            if (!inventoryManager.hasSpaceForItem(player, item, amount)) {
                sendMsg(player, "§c行囊空间不足，请清理后再试。");
                return false;
            }
        } else {
            if (!inventoryManager.hasEnoughItems(player, item, amount)) {
                sendMsg(player, "§c所需物资数量不足。");
                return false;
            }
        }
        return true;
    }

    private void handleTransactionSuccess(Player p, MarketItem item, int amt, double price, boolean isBuy) {
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        
        String actionText = isBuy ? "§a§l购买成功" : "§a§l售卖成功";
        String moneyText = isBuy ? "§f支出 §c-" : "§f获得 §a+";
        
        // 消息对齐 GUI 风格
        sendMsg(p, String.format("%s！ %s%.2f §6⛁ §7(x%d)", actionText, moneyText, price, amt));
        
        // 记录历史
        historyManager.incrementTradeCount(p.getUniqueId().toString(), item.getConfigKey(), amt);
        historyManager.saveAsync();
    }

    private void sendMsg(Player p, String msg) { p.sendMessage(PREFIX + msg); }
}