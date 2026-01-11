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
 * 交易执行管理器 (v4.0 工业级健壮版)
 * <p>
 * 职责：
 * 1. 管理交易生命周期：从计价预检到最终资产交换。
 * 2. 交易互斥锁：防止连点导致的重复交易。
 * 3. 汇率锁定：确保后端计算与玩家看到的快照一致。
 */
public class TransactionManager {

    private static final String PREFIX = "§8[§bKyochigo§8] ";
    private static final String ERR_EXPIRED = "§c交易会话已过期，请重新打开菜单。";
    private static final String ERR_BACKEND = "§c计算失败：后端核心未响应。";
    private static final String ERR_LOCK_FAIL = "§c§l致命错误：§f汇率锁定失败，交易被安全拦截！";
    private static final String ERR_PROCESSING = "§6请稍候，上一笔交易正在结算中...";
    private static final String MSG_LOCKING = "§7正在接入核心执行资产结算...";

    private final KyochigoPlugin plugin;
    private final InventoryManager inventoryManager;
    private final BackendManager backendManager;
    private final HistoryManager historyManager;
    
    private Economy economy;
    private final Map<UUID, TradeData> tradeCache;
    
    // ★★★ 核心：交易互斥锁，防止高并发下的重复点击 ★★★
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
    // 1. 计价与打开确认框 (Preview Phase)
    // =========================================================================

    public void openBuyConfirmDialog(Player p, MarketItem i, int amt) { requestPriceAndOpen(p, i, amt, "buy"); }
    public void openSellConfirmDialog(Player p, MarketItem i, int amt) { requestPriceAndOpen(p, i, amt, "sell"); }

    private void requestPriceAndOpen(Player player, MarketItem item, int amount, String action) {
        // [预览模式] 向后端请求当前实时报价
        backendManager.sendCalculateRequest(player, action, item.getConfigKey(), (double) amount,
                item.getBasePrice(), item.getLambda(), null, true, response -> {
                    
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (response == null || !response.has("totalPrice")) {
                            sendMsg(player, ERR_BACKEND);
                            return;
                        }

                        double unitPrice = response.get("unitPriceAvg").getAsDouble();
                        double currentEnv = response.get("envIndex").getAsDouble(); 

                        // 创建交易快照，锁定当前看到的价格和环境指数
                        TradeData data = new TradeData(item.getConfigKey(), item.getPlainDisplayName(), 
                            item.getMaterial().name(), amount, unitPrice, 
                            response.get("totalPrice").getAsDouble(), 
                            currentEnv, action.equals("buy"));

                        tradeCache.put(player.getUniqueId(), data);

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
        
        // 1. 互斥锁预检
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

        // 2. 数量与资产预检
        int finalAmount = calculateAdjustedAmount(player, item, amount);
        if (finalAmount <= 0) return;
        if (!isAssetCheckPassed(player, item, finalAmount, snapshot.isBuy)) return;

        // 3. 上锁，开始进入网络请求阶段
        processingPlayers.add(uuid);
        sendMsg(player, MSG_LOCKING);
        
        // [正式模式] 发起锁定汇率的交易请求 (manualEnvIndex = snapshot.envIndex)
        backendManager.sendCalculateRequest(player, snapshot.isBuy ? "buy" : "sell", item.getConfigKey(), 
            (double) finalAmount, item.getBasePrice(), item.getLambda(), snapshot.envIndex, false, response -> {
                
                // 4. 返回主线程结算资产
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        if (response == null || !response.has("totalPrice")) {
                            sendMsg(player, ERR_LOCK_FAIL);
                            return;
                        }
                        
                        double finalPrice = response.get("totalPrice").getAsDouble();
                        finalizeAssetSwap(player, item, snapshot, finalAmount, finalPrice);
                    } finally {
                        // 5. 无论成败，必须解锁
                        processingPlayers.remove(uuid);
                        tradeCache.remove(uuid);
                    }
                });
            });
    }

    // =========================================================================
    // 3. 资产交换 (Asset Swapping Logic)
    // =========================================================================

    private void finalizeAssetSwap(Player player, MarketItem item, TradeData snapshot, int amount, double price) {
        // [安全校验] 再次检查买家余额（防止由于网络延迟期间余额变动）
        if (snapshot.isBuy && !economy.has(player, price)) {
            sendMsg(player, "§c余额不足，交易取消。");
            return;
        }

        if (snapshot.isBuy) {
            // 买入逻辑：先扣钱，后发货
            economy.withdrawPlayer(player, price);
            inventoryManager.giveItems(player, item, amount);
            handleTransactionSuccess(player, item, amount, price, true);
        } else {
            // 卖出逻辑：先扣货，扣除成功后再给钱（极致防御：防止手速快把物品丢掉）
            if (inventoryManager.removeItems(player, item, amount)) {
                economy.depositPlayer(player, price);
                handleTransactionSuccess(player, item, amount, price, false);
            } else {
                sendMsg(player, "§c§l交易失败：§f物品状态异常（可能已移出背包）。");
            }
        }
    }

    // =========================================================================
    // 4. 辅助验证与通知
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
            if (!inventoryManager.hasSpaceForItem(player, item, amount)) {
                sendMsg(player, "§c背包空间不足，请清理后再试。");
                return false;
            }
        } else {
            if (!inventoryManager.hasEnoughItems(player, item, amount)) {
                sendMsg(player, "§c物品数量不足或不匹配。");
                return false;
            }
        }
        return true;
    }

    private void handleTransactionSuccess(Player p, MarketItem item, int amt, double price, boolean isBuy) {
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        
        String actionText = isBuy ? "§a§l购买成功" : "§a§l出售成功";
        String moneyText = isBuy ? "§f花费 §c-" : "§f获得 §a+";
        
        sendMsg(p, String.format("%s！ %s%.2f §7(x%d)", actionText, moneyText, price, amt));
        
        // 记录到本地 player_counter.yml
        historyManager.incrementTradeCount(p.getUniqueId().toString(), item.getConfigKey(), amt);
        historyManager.saveAsync();
    }

    private void sendMsg(Player p, String msg) { p.sendMessage(PREFIX + msg); }
}