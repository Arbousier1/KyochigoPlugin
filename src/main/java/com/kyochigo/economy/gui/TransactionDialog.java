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
 * 交易确认对话框 (v5.1 NPC分类适配版)
 * 更新：openEntryMenu 支持接收 targetCategory，实现点击特定NPC直接进入该分类的买卖选择。
 */
public class TransactionDialog {

    private static final ClickCallback.Options DEFAULT_OPTIONS = ClickCallback.Options.builder().build();
    private static final MiniMessage MM = MiniMessage.miniMessage();

    // 标题与常量
    private static final String SELL_TITLE = "<gold><b>售卖确认</b></gold>";
    private static final String BUY_TITLE = "<aqua><b>购买确认</b></aqua>";
    private static final String ACTION_TITLE = "<dark_aqua><b>选择交易操作</b></dark_aqua>";
    private static final String ENTRY_TITLE = "<gold><b>Kyochigo 交易所</b></gold>";
    private static final String DIVIDER = "<dark_gray>────────────────────────────────</dark_gray>";
    private static final String CURRENCY = " ⛁";

    private static final Component CONFIRM_SELL = MM.deserialize("<green>[ 确认售卖 ]</green>");
    private static final Component CONFIRM_BUY = MM.deserialize("<green>[ 支付并获取 ]</green>");
    private static final Component CANCEL = MM.deserialize("<gray>[ 取消 ]</gray>");
    private static final Component INSUFFICIENT_FUNDS = MM.deserialize("<gray>资金不足</gray>");
    private static final Component INSUFFICIENT_WARNING = MM.deserialize("<red><b>余额不足，请调整购买数量。</b></red>");

    /**
     * [通用入口] 打开全分类入口 (用于指令 /market)
     */
    public static void openEntryMenu(Player player) {
        openEntryMenu(player, null);
    }

    /**
     * [指定入口] 打开特定分类入口 (用于 NPC 点击)
     * @param targetCategory 如果不为 null，则选完意图后直接跳转该分类的物品列表
     */
    public static void openEntryMenu(Player player, String targetCategory) {
        // 构建 "我要购买" 按钮
        ActionButton btnBuy = ActionButton.builder(MM.deserialize("<gradient:green:aqua><b>[ 我要购买 ]</b></gradient>"))
            .action(DialogAction.customClick((view, audience) -> {
                if (audience instanceof Player p) {
                    if (targetCategory != null) {
                        // NPC 模式：直接进入该分类的物品列表
                        TradeSelectorDialog.openItemSelect(p, targetCategory, true);
                    } else {
                        // 指令模式：进入分类选择列表
                        TradeSelectorDialog.openCategorySelect(p, true);
                    }
                }
            }, DEFAULT_OPTIONS))
            .build();

        // 构建 "我要出售" 按钮
        ActionButton btnSell = ActionButton.builder(MM.deserialize("<gradient:gold:red><b>[ 我要出售 ]</b></gradient>"))
            .action(DialogAction.customClick((view, audience) -> {
                if (audience instanceof Player p) {
                    if (targetCategory != null) {
                        // NPC 模式：直接进入该分类的物品列表
                        TradeSelectorDialog.openItemSelect(p, targetCategory, false);
                    } else {
                        // 指令模式：进入分类选择列表
                        TradeSelectorDialog.openCategorySelect(p, false);
                    }
                }
            }, DEFAULT_OPTIONS))
            .build();

        player.showDialog(Dialog.create(factory -> {
            DialogRegistryEntry.Builder builder = factory.empty();
            Component desc;
            
            if (targetCategory != null) {
                // 显示当前柜台名称
                String categoryName = getCategoryName(targetCategory);
                desc = MM.deserialize("<gray>当前柜台：<white>" + categoryName + "</white><newline>请选择您的交易意向：</gray>");
            } else {
                desc = MM.deserialize("<gray>请选择您的交易意向：</gray>");
            }

            builder.base(DialogBase.builder(MM.deserialize(ENTRY_TITLE))
                .body(DialogBody.plainMessage(desc))
                .build());

            builder.type(DialogType.confirmation(btnBuy, btnSell));
        }));
    }

    /**
     * [操作菜单] 手持物品直接打开 / 物品列表点击打开
     * @param isBuyMode true=只买, false=只卖, null=显示全部
     */
    public static void openActionMenu(Player player, MarketItem item, Boolean isBuyMode) {
        KyochigoPlugin plugin = KyochigoPlugin.getInstance();
        double buyPrice = item.getBuyPrice();
        double sellPrice = item.getSellPrice();

        List<ActionButton> actions = new ArrayList<>();

        // 1. 购买组 (意图为 Null 或 True 时显示)
        if (isBuyMode == null || isBuyMode) {
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
        }

        // 2. 售卖组 (意图为 Null 或 False 时显示)
        if (isBuyMode == null || !isBuyMode) {
            actions.add(createBtn("<gold>售卖 x1</gold>", 
                (v, a) -> openTransactionConfirm((Player)a, item, 1, sellPrice, false)));
            
            actions.add(createBtn("<gold>售卖 x64</gold>", 
                (v, a) -> openTransactionConfirm((Player)a, item, 64, sellPrice, false)));

            actions.add(createBtn("<gold>售卖全部</gold>", (v, a) -> {
                Player p = (Player) a;
                int count = countPlayerItems(p, item, plugin);
                if (count > 0) openTransactionConfirm(p, item, count, sellPrice, false);
                else p.sendMessage(MM.deserialize("<red>背包里没有该物品。</red>"));
            }));
        }

        actions.add(ActionButton.builder(CANCEL).build());

        // 动态构建描述
        TextComponent.Builder desc = Component.text();
        if (isBuyMode == null || isBuyMode) {
            desc.append(MM.deserialize("<gray>购买单价：</gray><white>" + String.format("%.2f", buyPrice) + "</white>"));
            if (isBuyMode == null) desc.append(Component.newline());
        }
        if (isBuyMode == null || !isBuyMode) {
            desc.append(MM.deserialize("<gray>售卖单价：</gray><white>" + String.format("%.2f", sellPrice) + "</white>"));
        }

        showTransactionDialog(player, item, ACTION_TITLE, desc.build(), actions);
    }

    // [重载] 兼容旧接口
    public static void openActionMenu(Player player, MarketItem item) {
        openActionMenu(player, item, null);
    }

    // ========================================================================
    // 兼容性接口 (Bridge Methods)
    // ========================================================================

    public static void openBuyConfirm(Player player, MarketItem item, int amount, double price) {
        openTransactionConfirm(player, item, amount, price, true);
    }

    public static void openSellConfirm(Player player, MarketItem item, int amount, double price) {
        openTransactionConfirm(player, item, amount, price, false);
    }

    // ========================================================================
    // 内部核心逻辑
    // ========================================================================

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

    private static void showTransactionDialog(Player player, MarketItem item, String title, Component content, List<ActionButton> actions) {
        KyochigoPlugin plugin = KyochigoPlugin.getInstance();
        player.showDialog(Dialog.create(factory -> {
            DialogRegistryEntry.Builder builder = factory.empty();
            ItemStack icon = plugin.getMarketManager().getItemIcon(item);
            builder.base(DialogBase.builder(MM.deserialize(title))
                    .body(List.of(DialogBody.item(icon).description(DialogBody.plainMessage(content)).build()))
                    .build());
            if (actions.size() == 2) builder.type(DialogType.confirmation(actions.get(0), actions.get(1)));
            else builder.type(DialogType.multiAction(actions).build());
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

    // 简单获取中文名，用于显示当前柜台
    private static String getCategoryName(String id) {
        return java.util.Map.of(
            "ores", "矿产资源", "food", "烹饪美食", "crops", "农耕作物",
            "animal_husbandry", "畜牧产品", "weapons", "神兵利器", "misc", "综合杂项"
        ).getOrDefault(id.toLowerCase(), id);
    }
}