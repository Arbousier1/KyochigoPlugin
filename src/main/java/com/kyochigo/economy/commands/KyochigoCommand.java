package com.kyochigo.economy.commands;

import com.kyochigo.economy.KyochigoPlugin;
import com.kyochigo.economy.gui.MarketDialog;
import com.kyochigo.economy.managers.InventoryManager;
import com.kyochigo.economy.managers.MarketManager;
import com.kyochigo.economy.managers.TransactionManager;
import com.kyochigo.economy.model.MarketItem;
import com.kyochigo.economy.utils.CraftEngineHook;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class KyochigoCommand implements CommandExecutor, TabCompleter {
    private final KyochigoPlugin plugin;
    private final MarketManager marketManager;
    private final TransactionManager transactionManager;
    private final InventoryManager inventoryManager;
    private final CraftEngineHook hook;

    public KyochigoCommand(KyochigoPlugin plugin,
                           MarketManager marketManager,
                           TransactionManager transactionManager,
                           InventoryManager inventoryManager,
                           CraftEngineHook hook) {
        this.plugin = plugin;
        this.marketManager = marketManager;
        this.transactionManager = transactionManager;
        this.inventoryManager = inventoryManager;
        this.hook = hook;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§c该命令只能由玩家执行。");
            return true;
        }

        if (args.length == 0) {
            sendHelp(p);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "market" -> handleMarket(p);
            case "reload" -> handleReload(p);
            case "sellall" -> handleSellAll(p, args);
            case "clear" -> handleClear(p, args);
            case "execute_sell" -> handleExecuteSell(p, args);
            default -> sendHelp(p);
        }

        return true;
    }

    // --- 子命令处理逻辑 ---

    private void handleMarket(Player p) {
        List<MarketItem> items = marketManager.getAllItems();
        if (items == null || items.isEmpty()) {
            p.sendMessage("§c当前市场没有可显示的物品。");
            return;
        }
        MarketDialog.open(p, items, hook);
    }

    private void handleReload(Player p) {
        if (!p.hasPermission("kyochigo.admin")) {
            p.sendMessage("§c你没有权限执行此操作。");
            return;
        }
        plugin.reloadConfig();
        marketManager.loadItems();
        p.sendMessage("§a§l[Kyochigo] §7配置与物品列表已重载！");
    }

    private void handleSellAll(Player p, String[] args) {
        MarketItem targetItem = null;
        if (args.length > 1) {
            targetItem = marketManager.findMarketItemByKey(args[1]);
        } else {
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (hand.getType() != Material.AIR) {
                targetItem = marketManager.findMarketItem(hand);
            }
        }

        if (targetItem == null) {
            p.sendMessage("§c该物品不在回收列表中或你需要手持物品。");
            return;
        }
        if (!targetItem.isAllowSell()) {
            p.sendMessage("§c此物品禁止出售。");
            return;
        }

        int totalAmount = inventoryManager.countItems(p, targetItem);
        if (totalAmount <= 0) {
            p.sendMessage("§c背包中没有 " + targetItem.getDisplayName());
            return;
        }

        p.sendMessage("§e正在获取实时报价...");
        transactionManager.openSellConfirmDialog(p, targetItem, totalAmount, 0, 0);
    }

    private void handleClear(Player p, String[] args) {
        if (!p.hasPermission("kyochigo.admin")) {
            p.sendMessage("§c无权限。");
            return;
        }
        if (args.length < 4) {
            p.sendMessage("§c用法: /kyochigo clear <玩家> <物品Key> <数量>");
            return;
        }

        Player target = Bukkit.getPlayer(args[1]);
        MarketItem item = marketManager.findMarketItemByKey(args[2]);
        int amount = tryParseInt(args[3]);

        if (target == null || item == null || amount <= 0) {
            p.sendMessage("§c参数无效或玩家不在线。");
            return;
        }

        if (inventoryManager.removeItemSafe(target, item, amount)) {
            p.sendMessage("§a已清理。");
        } else {
            p.sendMessage("§c清理失败，物品不足。");
        }
    }

    private void handleExecuteSell(Player p, String[] args) {
        if (args.length < 4) return;
        Player target = Bukkit.getPlayer(args[1]);
        MarketItem item = marketManager.findMarketItemByKey(args[2]);
        int amount = tryParseInt(args[3]);

        if (target != null && item != null) {
            transactionManager.executeTransaction(target, item, amount);
        }
    }

    private void sendHelp(Player p) {
        p.sendMessage("§8§m      §e§l Kyochigo Economy §8§m      ");
        p.sendMessage("§7/kyochigo market §8- §f打开市场面板");
        p.sendMessage("§7/kyochigo sellall [Key] §8- §f出售背包所有指定物品");
        if (p.hasPermission("kyochigo.admin")) {
            p.sendMessage("§7/kyochigo reload §8- §f重载插件");
            p.sendMessage("§7/kyochigo clear <玩家> <Key> <数量> §8- §f管理清理");
        }
        p.sendMessage("§8§m                              ");
    }

    private int tryParseInt(String val) {
        try { return Integer.parseInt(val); } catch (Exception e) { return -1; }
    }

    // --- Tab 完成逻辑 ---

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> subCommands = new ArrayList<>(Arrays.asList("market", "sellall"));
            if (sender.hasPermission("kyochigo.admin")) {
                subCommands.addAll(Arrays.asList("reload", "clear", "execute_sell"));
            }
            return filter(subCommands, args[0]);
        }

        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("sellall") || args[0].equalsIgnoreCase("clear") || args[0].equalsIgnoreCase("execute_sell")) {
                if (args[0].equalsIgnoreCase("sellall")) {
                    return filter(marketManager.getAllItems().stream().map(MarketItem::getConfigKey).collect(Collectors.toList()), args[1]);
                }
                return null; // 返回 null 默认显示在线玩家列表
            }
        }

        if (args.length == 3 && (args[0].equalsIgnoreCase("clear") || args[0].equalsIgnoreCase("execute_sell"))) {
            return filter(marketManager.getAllItems().stream().map(MarketItem::getConfigKey).collect(Collectors.toList()), args[2]);
        }

        return new ArrayList<>();
    }

    private List<String> filter(List<String> list, String input) {
        return list.stream().filter(s -> s.toLowerCase().startsWith(input.toLowerCase())).collect(Collectors.toList());
    }
}