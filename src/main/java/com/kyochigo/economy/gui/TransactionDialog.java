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

import java.util.List;

/**
 * 交易确认对话框 (v3.0 逻辑对齐版)
 */
public class TransactionDialog {

    private static final ClickCallback.Options DEFAULT_OPTIONS = ClickCallback.Options.builder().build();
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private static final String SELL_TITLE = "<gold><b>交易确认</b></gold>";
    private static final String BUY_TITLE = "<aqua><b>申购确认</b></aqua>";
    private static final String DIVIDER = "<dark_gray>────────────────────────────────</dark_gray>";
    private static final String CURRENCY = " ⛁";

    private static final Component CONFIRM_SELL = MM.deserialize("<green>[ 确认出售 ]</green>");
    private static final Component CONFIRM_BUY = MM.deserialize("<green>[ 支付并获取 ]</green>");
    private static final Component CANCEL = MM.deserialize("<gray>[ 取消 ]</gray>");
    private static final Component INSUFFICIENT_FUNDS = MM.deserialize("<gray>资金不足</gray>");
    private static final Component INSUFFICIENT_WARNING = MM.deserialize("<red><b>余额不足，请调整申购数量。</b></red>");

    public static void openSellConfirm(Player player, MarketItem item, int amount, double pricePerUnit) {
        KyochigoPlugin plugin = KyochigoPlugin.getInstance();
        double totalGains = amount * pricePerUnit;

        Component content = buildSellContent(item, amount, pricePerUnit, totalGains, plugin);
        DialogActionCallback callback = (view, audience) -> {
            if (audience instanceof Player p) {
                plugin.getTransactionManager().executeSell(p, item, amount, pricePerUnit);
            }
        };

        showConfirmationDialog(player, item, content, SELL_TITLE, CONFIRM_SELL, callback, plugin);
    }

    public static void openBuyConfirm(Player player, MarketItem item, int amount, double pricePerUnit) {
        KyochigoPlugin plugin = KyochigoPlugin.getInstance();
        double totalCost = amount * pricePerUnit;
        double balance = plugin.getEconomy().getBalance(player);
        boolean canAfford = balance >= totalCost;

        Component content = buildBuyContent(item, amount, pricePerUnit, totalCost, balance, canAfford, plugin);
        DialogActionCallback callback = (view, audience) -> {
            if (audience instanceof Player p) {
                plugin.getTransactionManager().executeBuy(p, item, amount, pricePerUnit);
            }
        };

        Component confirmButton = canAfford ? CONFIRM_BUY : INSUFFICIENT_FUNDS;
        showConfirmationDialog(player, item, content, BUY_TITLE, confirmButton, canAfford ? callback : null, plugin);
    }

    private static Component buildSellContent(MarketItem item, int amount, double pricePerUnit, double totalGains, KyochigoPlugin plugin) {
        return Component.text()
                .append(MM.deserialize("<gray>确认出售以下物品：</gray>")).append(Component.newline())
                .append(item.getDisplayNameComponent(plugin.getMarketManager().getCraftEngineHook()).decorate(TextDecoration.BOLD))
                .append(Component.text(" x" + amount, NamedTextColor.WHITE)).append(Component.newline())
                .append(MM.deserialize(DIVIDER)).append(Component.newline())
                .append(MM.deserialize("<gray>实时报价：</gray><white>" + String.format("%.2f", pricePerUnit) + CURRENCY + "</white>")).append(Component.newline())
                .append(MM.deserialize("<gray>预计获得：</gray><green>+" + String.format("%.2f", totalGains) + CURRENCY + "</green>"))
                .build();
    }

    private static Component buildBuyContent(MarketItem item, int amount, double pricePerUnit, double totalCost, double balance, boolean canAfford, KyochigoPlugin plugin) {
        TextComponent.Builder builder = Component.text()
                .append(MM.deserialize("<gray>确认申购以下物品：</gray>")).append(Component.newline())
                .append(item.getDisplayNameComponent(plugin.getMarketManager().getCraftEngineHook()).decorate(TextDecoration.BOLD))
                .append(Component.text(" x" + amount, NamedTextColor.WHITE)).append(Component.newline())
                .append(MM.deserialize(DIVIDER)).append(Component.newline())
                .append(MM.deserialize("<gray>所需支出：</gray><red>-" + String.format("%.2f", totalCost) + CURRENCY + "</red>")).append(Component.newline())
                .append(MM.deserialize("<gray>当前余额：</gray><white>" + String.format("%.2f", balance) + CURRENCY + "</white>"));

        if (!canAfford) {
            builder.append(Component.newline()).append(INSUFFICIENT_WARNING);
        }

        return builder.build();
    }

    private static void showConfirmationDialog(Player player, MarketItem item, Component content, String title,
                                               Component confirmButton, DialogActionCallback callback,
                                               KyochigoPlugin plugin) {
        player.showDialog(Dialog.create(factory -> {
            DialogRegistryEntry.Builder registryBuilder = factory.empty();
            ItemStack icon = plugin.getMarketManager().getItemIcon(item);
            
            registryBuilder.base(DialogBase.builder(MM.deserialize(title))
                    .body(List.of(
                            DialogBody.item(icon).description(DialogBody.plainMessage(content)).build()
                    )).build());

            ActionButton confirmAction = ActionButton.builder(confirmButton)
                    .action(callback != null ? DialogAction.customClick(callback, DEFAULT_OPTIONS) : null).build();
            ActionButton cancelAction = ActionButton.builder(CANCEL).build();

            registryBuilder.type(DialogType.confirmation(confirmAction, cancelAction));
        }));
    }
}