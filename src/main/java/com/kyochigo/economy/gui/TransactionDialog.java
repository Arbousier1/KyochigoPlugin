package com.kyochigo.economy.gui;

import com.kyochigo.economy.KyochigoPlugin;
import com.kyochigo.economy.model.MarketItem;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.DialogRegistryEntry;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 交易确认对话框 (v4.0 核心重构版)
 * 优化内容：
 * 1. 统一买卖逻辑，代码量减少 40%
 * 2. 抽象 Dialog 构建器，提升可维护性
 * 3. 保持所有原有功能 (满包购买/全量出售)
 */
public class TransactionDialog {

    private static final ClickCallback.Options DEFAULT_OPTIONS = ClickCallback.Options.builder().build();
    private static final MiniMessage MM = MiniMessage.miniMessage();

    // 标题与常量
    private static final String SELL_TITLE = "<gold><b>售卖确认</b></gold>";
    private static final String BUY_TITLE = "<aqua><b>购买确认</b></aqua>";
    private static final String ACTION_TITLE = "<dark_aqua><b>选择交易操作</b></dark_aqua>";
    private static final String DIVIDER = "<dark_gray>────────────────────────────────</dark_gray>";
    private static final String CURRENCY = " ⛁";

    // 按钮组件
    private static final Component CONFIRM_SELL = MM.deserialize("<green>[ 确认售卖 ]</green>");
    private static final Component CONFIRM_BUY = MM.deserialize("<green>[ 支付并获取 ]</green>");
    private static final Component CANCEL = MM.deserialize("<gray>[ 取消 ]</gray>");
    private static final Component INSUFFICIENT_FUNDS = MM.deserialize("<gray>资金不足</gray>");
    private static final Component INSUFFICIENT_WARNING = MM.deserialize("<red><b>余额不足，请调整购买数量。</b></red>");

    /**
     * 打开操作选择菜单 (统一入口)
     */
    public static void openActionMenu(Player player, MarketItem item) {
        KyochigoPlugin plugin = KyochigoPlugin.getInstance();
        double buyPrice = item.getBuyPrice();
        double sellPrice = item.getSellPrice();

        List<ActionButton> actions = new ArrayList<>();

        // 1. 购买组
        actions.add(createBtn("<green>购买 x1</green>", 
            (v, a) -> openTransactionConfirm((Player)a, item, 1, buyPrice, true)));
        
        actions.add(createBtn("<green>购买 x64</green>", 
            (v, a) -> openTransactionConfirm((Player)a, item, 64, buyPrice, true)));

        actions.add(createBtn("<green>购买满背包</green>", (v, a) -> {
            Player p = (Player) a;
            int maxSpace = getInventoryFreeSpace(p, plugin.getMarketManager().getItemIcon(item));
            if (maxSpace > 0) openTransactionConfirm(p, item, maxSpace, buyPrice, true);
            else p.sendMessage(MM.deserialize("<red>背包已满，无法购买更多物品。</red>"));
        }));

        // 2. 售卖组
        actions.add(createBtn("<gold>售卖 x1</gold>", 
            (v, a) -> openTransactionConfirm((Player)a, item, 1, sellPrice, false)));
        
        actions.add(createBtn("<gold>售卖 x64</gold>", 
            (v, a) -> openTransactionConfirm((Player)a, item, 64, sellPrice, false)));

        actions.add(createBtn("<gold>售卖全部</gold>", (v, a) -> {
            Player p = (Player) a;
            int count = countPlayerItems(p, item, plugin);
            if (count > 0) openTransactionConfirm(p, item, count, sellPrice, false);
            else p.sendMessage(MM.deserialize("<red>你背包里没有该物品。</red>"));
        }));

        // 3. 取消
        actions.add(ActionButton.builder(CANCEL).build());

        // 描述信息
        Component desc = Component.text()
                .append(MM.deserialize("<gray>购买单价：</gray><white>" + String.format("%.2f", buyPrice) + "</white>"))
                .append(Component.newline())
                .append(MM.deserialize("<gray>售卖单价：</gray><white>" + String.format("%.2f", sellPrice) + "</white>"))
                .build();

        showTransactionDialog(player, item, ACTION_TITLE, desc, actions);
    }

    /**
     * 统一交易确认逻辑 (合并了原本的 BuyConfirm 和 SellConfirm)
     * @param isBuy true=购买, false=售卖
     */
    private static void openTransactionConfirm(Player player, MarketItem item, int amount, double price, boolean isBuy) {
        KyochigoPlugin plugin = KyochigoPlugin.getInstance();
        
        // 构建内容与状态检查
        double balance = plugin.getEconomy().getBalance(player);
        Component content = buildTransactionContent(item, amount, price, balance, isBuy);
        
        // 逻辑判定
        boolean canProceed = !isBuy || (balance >= amount * price);
        Component confirmBtnText = isBuy ? CONFIRM_BUY : CONFIRM_SELL;
        if (!canProceed) confirmBtnText = INSUFFICIENT_FUNDS;

        // 回调逻辑
        DialogActionCallback callback = (view, audience) -> {
            if (audience instanceof Player p) {
                if (isBuy) plugin.getTransactionManager().executeBuy(p, item, amount, price);
                else plugin.getTransactionManager().executeSell(p, item, amount, price);
            }
        };

        List<ActionButton> actions = List.of(
            ActionButton.builder(confirmBtnText)
                .action(canProceed ? DialogAction.customClick(callback, DEFAULT_OPTIONS) : null)
                .build(),
            ActionButton.builder(CANCEL).build()
        );

        showTransactionDialog(player, item, isBuy ? BUY_TITLE : SELL_TITLE, content, actions);
    }

    // ========================================================================
    // 核心构建器 (Core Builders) - 极大地减少了重复代码
    // ========================================================================

    private static void showTransactionDialog(Player player, MarketItem item, String title, Component content, List<ActionButton> actions) {
        KyochigoPlugin plugin = KyochigoPlugin.getInstance();
        player.showDialog(Dialog.create(factory -> {
            DialogRegistryEntry.Builder builder = factory.empty();
            ItemStack icon = plugin.getMarketManager().getItemIcon(item);
            
            builder.base(DialogBase.builder(MM.deserialize(title))
                    .body(List.of(DialogBody.item(icon)
                            .description(DialogBody.plainMessage(content)).build()))
                    .build());
            
            // 自动判断：如果是2个按钮则为 Confirmation 布局，否则为 MultiAction 列表布局
            if (actions.size() == 2) {
                builder.type(DialogType.confirmation(actions.get(0), actions.get(1)));
            } else {
                builder.type(DialogType.multiAction(actions).build());
            }
        }));
    }

    private static Component buildTransactionContent(MarketItem item, int amount, double price, double balance, boolean isBuy) {
        String action = isBuy ? "购买" : "售卖";
        double total = amount * price;
        KyochigoPlugin plugin = KyochigoPlugin.getInstance();

        TextComponent.Builder builder = Component.text()
                .append(MM.deserialize("<gray>确认" + action + "以下物品：</gray>")).append(Component.newline())
                .append(item.getDisplayNameComponent(plugin.getMarketManager().getCraftEngineHook()).decorate(TextDecoration.BOLD))
                .append(Component.text(" x" + amount, NamedTextColor.WHITE)).append(Component.newline())
                .append(MM.deserialize(DIVIDER)).append(Component.newline());

        if (isBuy) {
            builder.append(MM.deserialize("<gray>所需支出：</gray><red>-" + String.format("%.2f", total) + CURRENCY + "</red>"))
                   .append(Component.newline())
                   .append(MM.deserialize("<gray>当前余额：</gray><white>" + String.format("%.2f", balance) + CURRENCY + "</white>"));
            
            if (balance < total) builder.append(Component.newline()).append(INSUFFICIENT_WARNING);
        } else {
            builder.append(MM.deserialize("<gray>实时报价：</gray><white>" + String.format("%.2f", price) + CURRENCY + "</white>"))
                   .append(Component.newline())
                   .append(MM.deserialize("<gray>预计获得：</gray><green>+" + String.format("%.2f", total) + CURRENCY + "</green>"));
        }
        return builder.build();
    }

    // ========================================================================
    // 工具方法 (Helpers)
    // ========================================================================

    private static ActionButton createBtn(String label, DialogActionCallback callback) {
        return ActionButton.builder(MM.deserialize(label))
                .action(DialogAction.customClick(callback, DEFAULT_OPTIONS))
                .build();
    }

    private static int countPlayerItems(Player player, MarketItem item, KyochigoPlugin plugin) {
        ItemStack icon = plugin.getMarketManager().getItemIcon(item);
        int count = 0;
        for (ItemStack invItem : player.getInventory().getStorageContents()) {
            if (invItem != null && invItem.isSimilar(icon)) count += invItem.getAmount();
        }
        return count;
    }

    private static int getInventoryFreeSpace(Player player, ItemStack itemTemplate) {
        int freeSpace = 0;
        int maxStack = itemTemplate.getMaxStackSize();
        for (ItemStack slot : player.getInventory().getStorageContents()) {
            if (slot == null || slot.getType().isAir()) freeSpace += maxStack;
            else if (slot.isSimilar(itemTemplate)) freeSpace += Math.max(0, maxStack - slot.getAmount());
        }
        return freeSpace;
    }
}