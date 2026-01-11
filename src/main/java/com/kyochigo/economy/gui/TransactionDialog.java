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
import net.kyori.adventure.key.Key;
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
 * 交易确认对话框 (v6.0 最终优化版)
 * 优化内容：
 * 1. 提取价格格式化与按钮构建逻辑，极大提升代码复用率。
 * 2. 补全缺失常量，统一 UI 风格。
 * 3. 强制购买数量按钮竖向排列。
 */
public class TransactionDialog {

    private static final ClickCallback.Options DEFAULT_OPTIONS = ClickCallback.Options.builder().build();
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Key FONT_UNIFORM = Key.key("minecraft:uniform");

    // UI 常量
    private static final String ACTION_TITLE = "<dark_aqua><b>选择交易数量</b></dark_aqua>";
    private static final String ENTRY_TITLE = "<gold><b>Kyochigo 交易所</b></gold>";
    private static final String BUY_TITLE = "<green><b>确认购买</b></green>";
    private static final String SELL_TITLE = "<gold><b>确认售卖</b></gold>";
    private static final String DIVIDER = "<dark_gray>────────────────────────────────</dark_gray>";
    private static final String CURRENCY = " ⛁";

    // 按钮组件
    private static final Component CONFIRM_SELL = MM.deserialize("<green>[ 确认售卖 ]</green>");
    private static final Component CONFIRM_BUY = MM.deserialize("<green>[ 支付并获取 ]</green>");
    private static final Component CANCEL = MM.deserialize("<gray>[ 取消 ]</gray>");
    private static final Component INSUFFICIENT_FUNDS = MM.deserialize("<gray>资金不足</gray>");
    private static final Component INSUFFICIENT_WARNING = MM.deserialize("<red><b>余额不足，请调整购买数量。</b></red>");

    // ========================================================================
    // 入口逻辑 (Entry Logic)
    // ========================================================================

    public static void openEntryMenu(Player player) {
        openEntryMenu(player, null);
    }

    public static void openEntryMenu(Player player, String targetCategory) {
        ActionButton btnBuy = createBtn("<gradient:green:aqua><b>[ 我要购买 ]</b></gradient>", (v, a) -> {
            if (a instanceof Player p) {
                if (targetCategory != null) TradeSelectorDialog.openItemSelect(p, targetCategory, true);
                else TradeSelectorDialog.openCategorySelect(p, true);
            }
        });

        ActionButton btnSell = createBtn("<gradient:gold:red><b>[ 我要出售 ]</b></gradient>", (v, a) -> {
            if (a instanceof Player p) {
                if (targetCategory != null) TradeSelectorDialog.openItemSelect(p, targetCategory, false);
                else TradeSelectorDialog.openCategorySelect(p, false);
            }
        });

        Component desc;
        if (targetCategory != null) desc = MM.deserialize("<gray>当前柜台：<white>" + targetCategory + "</white><newline>请选择交易意向：</gray>");
        else desc = MM.deserialize("<gray>请选择交易意向：</gray>");

        createAndShowDialog(player, MM.deserialize(ENTRY_TITLE), desc, List.of(btnBuy, btnSell));
    }

    // ========================================================================
    // 操作菜单逻辑 (Action Menu Logic)
    // ========================================================================

    public static void openActionMenu(Player player, MarketItem item) {
        openActionMenu(player, item, null);
    }

    public static void openActionMenu(Player player, MarketItem item, Boolean isBuyMode) {
        List<ActionButton> actions = new ArrayList<>();

        // 1. 添加购买按钮
        if (shouldShowBuyOptions(isBuyMode)) {
            addBuyActions(actions, item);
        }

        // 2. 添加售卖按钮
        if (shouldShowSellOptions(isBuyMode)) {
            addSellActions(actions, item);
        }

        // 3. 添加取消按钮
        actions.add(ActionButton.builder(CANCEL).build());

        // 4. 构建描述信息
        TextComponent.Builder desc = Component.text();
        if (shouldShowBuyOptions(isBuyMode)) {
            desc.append(formatPrice(item.getBuyPrice(), "购买单价"));
            if (isBuyMode == null) desc.append(Component.newline());
        }
        if (shouldShowSellOptions(isBuyMode)) {
            desc.append(formatPrice(item.getSellPrice(), "售卖单价"));
        }

        // 5. 显示对话框 (注意：这里必须用 showTransactionDialog 来支持 item 图标显示)
        showTransactionDialog(player, item, ACTION_TITLE, desc.build(), actions);
    }

    private static void addBuyActions(List<ActionButton> actions, MarketItem item) {
        double price = item.getBuyPrice();
        actions.add(createBtn("<green>购买 1 个</green>", 
            (v, a) -> openBuyConfirm((Player)a, item, 1, price)));
        
        actions.add(createBtn("<green>购买 64 个 (一组)</green>", 
            (v, a) -> openBuyConfirm((Player)a, item, 64, price)));

        actions.add(createBtn("<green>购买直到背包装满</green>", (v, a) -> {
            Player p = (Player) a;
            int maxSpace = getInventoryFreeSpace(p, KyochigoPlugin.getInstance().getMarketManager().getItemIcon(item));
            if (maxSpace > 0) openBuyConfirm(p, item, maxSpace, price);
            else p.sendMessage(MM.deserialize("<red>背包已满，无法购买。</red>"));
        }));
    }

    private static void addSellActions(List<ActionButton> actions, MarketItem item) {
        double price = item.getSellPrice();
        actions.add(createBtn("<gold>售卖 1 个</gold>", 
            (v, a) -> openSellConfirm((Player)a, item, 1, price)));
        
        actions.add(createBtn("<gold>售卖 64 个 (一组)</gold>", 
            (v, a) -> openSellConfirm((Player)a, item, 64, price)));

        actions.add(createBtn("<gold>售卖背包内所有</gold>", (v, a) -> {
            Player p = (Player) a;
            int count = countPlayerItems(p, item, KyochigoPlugin.getInstance());
            if (count > 0) openSellConfirm(p, item, count, price);
            else p.sendMessage(MM.deserialize("<red>背包里没有该物品。</red>"));
        }));
    }

    // ========================================================================
    // 确认菜单逻辑 (Confirm Menu Logic)
    // ========================================================================

    public static void openBuyConfirm(Player player, MarketItem item, int amount, double price) {
        openTransactionConfirm(player, item, amount, price, true);
    }

    public static void openSellConfirm(Player player, MarketItem item, int amount, double price) {
        openTransactionConfirm(player, item, amount, price, false);
    }

    private static void openTransactionConfirm(Player player, MarketItem item, int amount, double price, boolean isBuy) {
        KyochigoPlugin plugin = KyochigoPlugin.getInstance();
        double balance = plugin.getEconomy().getBalance(player);
        Component content = buildTransactionContent(item, amount, price, balance, isBuy);
        
        boolean canProceed = !isBuy || (balance >= amount * price);
        Component confirmBtnText = isBuy ? CONFIRM_BUY : CONFIRM_SELL;
        if (!canProceed) confirmBtnText = INSUFFICIENT_FUNDS;

        DialogActionCallback callback = (view, audience) -> {
            if (audience instanceof Player p) {
                if (isBuy) plugin.getTransactionManager().executeBuy(p, item, amount, price);
                else plugin.getTransactionManager().executeSell(p, item, amount, price);
            }
        };

        List<ActionButton> actions = List.of(
            ActionButton.builder(confirmBtnText)
                .action(canProceed ? DialogAction.customClick(callback, DEFAULT_OPTIONS) : null).build(),
            ActionButton.builder(CANCEL).build()
        );

        showTransactionDialog(player, item, isBuy ? BUY_TITLE : SELL_TITLE, content, actions);
    }

    // ========================================================================
    // 辅助与构建方法 (Helpers & Builders)
    // ========================================================================

    private static boolean shouldShowBuyOptions(Boolean isBuyMode) {
        return isBuyMode == null || isBuyMode;
    }

    private static boolean shouldShowSellOptions(Boolean isBuyMode) {
        return isBuyMode == null || !isBuyMode;
    }

    private static Component formatPrice(double price, String label) {
        return Component.text()
                .append(Component.text(label + "：", NamedTextColor.GRAY))
                .append(Component.text(String.format("%8.2f ⛁", price), NamedTextColor.WHITE).font(FONT_UNIFORM))
                .build();
    }

    private static Component buildTransactionContent(MarketItem item, int amount, double price, double balance, boolean isBuy) {
        String action = isBuy ? "购买" : "售卖";
        double total = amount * price;
        KyochigoPlugin plugin = KyochigoPlugin.getInstance();

        TextComponent.Builder builder = Component.text()
                .append(MM.deserialize("<gray>确认" + action + "：</gray>")).append(Component.newline())
                .append(item.getDisplayNameComponent(plugin.getMarketManager().getCraftEngineHook()).decorate(TextDecoration.BOLD))
                .append(Component.text(" x" + amount, NamedTextColor.WHITE)).append(Component.newline())
                .append(MM.deserialize(DIVIDER)).append(Component.newline());

        if (isBuy) {
            builder.append(MM.deserialize("<gray>总价：</gray><red>-" + String.format("%.2f", total) + CURRENCY + "</red>"))
                   .append(Component.newline())
                   .append(MM.deserialize("<gray>余额：</gray><white>" + String.format("%.2f", balance) + CURRENCY + "</white>"));
            if (balance < total) builder.append(Component.newline()).append(INSUFFICIENT_WARNING);
        } else {
            builder.append(MM.deserialize("<gray>单价：</gray><white>" + String.format("%.2f", price) + CURRENCY + "</white>"))
                   .append(Component.newline())
                   .append(MM.deserialize("<gray>总收益：</gray><green>+" + String.format("%.2f", total) + CURRENCY + "</green>"));
        }
        return builder.build();
    }

    /**
     * 基础对话框显示 (不带物品图标，用于 Entry Menu)
     */
    private static void createAndShowDialog(Player player, Component title, Component body, List<ActionButton> actions) {
        player.showDialog(Dialog.create(factory -> {
            DialogRegistryEntry.Builder builder = factory.empty();
            builder.base(DialogBase.builder(title)
                    .body(List.of(DialogBody.plainMessage(body)))
                    .build());
            
            if (actions.size() == 2) builder.type(DialogType.confirmation(actions.get(0), actions.get(1)));
            else builder.type(DialogType.multiAction(actions).build());
        }));
    }

    /**
     * 物品对话框显示 (带物品图标，用于 Action/Confirm Menu)
     */
    private static void showTransactionDialog(Player player, MarketItem item, String title, Component content, List<ActionButton> actions) {
        KyochigoPlugin plugin = KyochigoPlugin.getInstance();
        player.showDialog(Dialog.create(factory -> {
            DialogRegistryEntry.Builder builder = factory.empty();
            ItemStack icon = plugin.getMarketManager().getItemIcon(item);
            builder.base(DialogBase.builder(MM.deserialize(title))
                    .body(List.of(DialogBody.item(icon).description(DialogBody.plainMessage(content)).build()))
                    .build());
            
            // 只要不是 Entry Menu 的那两个大按钮，统一使用 multiAction 实现竖排
            // 如果确认菜单想并排，可以单独判断。但为了统一风格，这里也建议 multiAction
            // 特殊处理：如果是 Confirmation 步骤 (actions=2)，且不是 Action Menu，可以并排
            // 但为了安全起见，Action Menu 肯定 > 2，所以这里保持智能判断
            if (actions.size() == 2) builder.type(DialogType.confirmation(actions.get(0), actions.get(1)));
            else builder.type(DialogType.multiAction(actions).build());
        }));
    }

    private static ActionButton createBtn(String label, DialogActionCallback callback) {
        return ActionButton.builder(MM.deserialize(label)).action(DialogAction.customClick(callback, DEFAULT_OPTIONS)).build();
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