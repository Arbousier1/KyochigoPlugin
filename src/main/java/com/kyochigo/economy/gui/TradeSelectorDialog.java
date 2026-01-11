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
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 交易确认对话框 (v7.0 视觉重构版)
 */
public class TransactionDialog {

    private static final ClickCallback.Options DEFAULT_OPTIONS = ClickCallback.Options.builder().build();
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Key FONT_UNIFORM = Key.key("minecraft:uniform");

    // 高级感配色与文案
    private static final String ACTION_TITLE = "<gradient:#40E0D0:#008080><b>商业交易中心</b></gradient>";
    private static final String ENTRY_TITLE = "<gradient:#FFD700:#FFA500><b>Kyochigo 交易所</b></gradient>";
    private static final String BUY_TITLE = "<gradient:#55FF55:#00AA00><b>确认采购申请</b></gradient>";
    private static final String SELL_TITLE = "<gradient:#FFCC33:#E67E22><b>确认资产出售</b></gradient>";
    
    // 视觉装饰符
    private static final String DIVIDER = "<dark_gray>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━</dark_gray>";
    private static final String CURRENCY = " <gold>⛁</gold>";

    // 按钮样式
    private static final Component CONFIRM_SELL = MM.deserialize("<bold><gradient:#FFCC33:#E67E22>[ 签署出售协议 ]</gradient></bold>");
    private static final Component CONFIRM_BUY = MM.deserialize("<bold><gradient:#55FF55:#00AA00>[ 支付并完成采购 ]</gradient></bold>");
    private static final Component CANCEL = MM.deserialize("<gray>[ 放弃本次业务 ]</gray>");
    private static final Component INSUFFICIENT_FUNDS = MM.deserialize("<gray>账户资金余额不足</gray>");

    public static void openEntryMenu(Player player, String targetCategory) {
        // 使用空格补位确保按钮宽度平衡
        ActionButton btnBuy = createBtn("<gradient:#00F260:#0575E6><b>  我要采购物品  </b></gradient>", (v, a) -> {
            if (a instanceof Player p) {
                if (targetCategory != null) TradeSelectorDialog.openItemSelect(p, targetCategory, true);
                else TradeSelectorDialog.openCategorySelect(p, true);
            }
        });

        ActionButton btnSell = createBtn("<gradient:#F2994A:#F2C94C><b>  我要出售资产  </b></gradient>", (v, a) -> {
            if (a instanceof Player p) {
                if (targetCategory != null) TradeSelectorDialog.openItemSelect(p, targetCategory, false);
                else TradeSelectorDialog.openCategorySelect(p, false);
            }
        });

        Component desc;
        if (targetCategory != null) {
            String friendlyName = getCategoryFriendlyName(targetCategory);
            desc = MM.deserialize("<newline><gray>当前服务柜台：<white>" + friendlyName + "</white><newline><gray>请选择您的业务意向：</gray>");
        } else {
            desc = MM.deserialize("<newline><gray>尊敬的客户，请选择您的业务意向：</gray>");
        }

        createAndShowDialog(player, MM.deserialize(ENTRY_TITLE), desc, List.of(btnBuy, btnSell));
    }

    public static void openActionMenu(Player player, MarketItem item, Boolean isBuyMode) {
        List<ActionButton> actions = new ArrayList<>();

        if (shouldShowBuyOptions(isBuyMode)) addBuyActions(actions, item);
        if (shouldShowSellOptions(isBuyMode)) addSellActions(actions, item);
        
        actions.add(ActionButton.builder(CANCEL).build());

        TextComponent.Builder desc = Component.text();
        desc.append(MM.deserialize("<newline>"));
        if (shouldShowBuyOptions(isBuyMode)) {
            desc.append(formatRichPrice(item.getBuyPrice(), "采购基准单价", "#00FF7F"));
            if (isBuyMode == null) desc.append(Component.newline());
        }
        if (shouldShowSellOptions(isBuyMode)) {
            desc.append(formatRichPrice(item.getSellPrice(), "市场回收单价", "#FFD700"));
        }

        showTransactionDialog(player, item, ACTION_TITLE, desc.build(), actions);
    }

    private static void addBuyActions(List<ActionButton> actions, MarketItem item) {
        double price = item.getBuyPrice();
        actions.add(createBtn("<green>▸ 采购少量 (1个)</green>", (v, a) -> openBuyConfirm((Player)a, item, 1, price)));
        actions.add(createBtn("<green>▸ 采购成组 (64个)</green>", (v, a) -> openBuyConfirm((Player)a, item, 64, price)));
        actions.add(createBtn("<green>▸ 补齐库存 (背包装满)</green>", (v, a) -> {
            Player p = (Player) a;
            int maxSpace = getInventoryFreeSpace(p, KyochigoPlugin.getInstance().getMarketManager().getItemIcon(item));
            if (maxSpace > 0) openBuyConfirm(p, item, maxSpace, price);
            else p.sendMessage(MM.deserialize("<red>仓库已满，无法容纳更多物资。</red>"));
        }));
    }

    private static void addSellActions(List<ActionButton> actions, MarketItem item) {
        double price = item.getSellPrice();
        actions.add(createBtn("<gold>▸ 出售少量 (1个)</gold>", (v, a) -> openSellConfirm((Player)a, item, 1, price)));
        actions.add(createBtn("<gold>▸ 出售整组 (64个)</gold>", (v, a) -> openSellConfirm((Player)a, item, 64, price)));
        actions.add(createBtn("<gold>▸ 出售全部 (清空背包)</gold>", (v, a) -> {
            Player p = (Player) a;
            int count = countPlayerItems(p, item, KyochigoPlugin.getInstance());
            if (count > 0) openSellConfirm(p, item, count, price);
            else p.sendMessage(MM.deserialize("<red>您的行囊中没有该物资。</red>"));
        }));
    }

    // --- 视觉增强工具 ---

    private static Component formatRichPrice(double price, String label, String colorCode) {
        return Component.text()
                .append(Component.text(label + " ", NamedTextColor.GRAY))
                .append(Component.text(" » ", NamedTextColor.DARK_GRAY))
                .append(Component.text(String.format("%8.2f", price), TextColor.fromHexString(colorCode)).font(FONT_UNIFORM))
                .append(MM.deserialize(CURRENCY))
                .build();
    }

    private static Component buildTransactionContent(MarketItem item, int amount, double price, double balance, boolean isBuy) {
        KyochigoPlugin plugin = KyochigoPlugin.getInstance();
        double total = amount * price;

        TextComponent.Builder builder = Component.text()
                .append(MM.deserialize("<newline><gray>正在准备 <white>" + (isBuy ? "购入" : "售出") + "</white> 业务：</gray><newline>"))
                .append(item.getDisplayNameComponent(plugin.getMarketManager().getCraftEngineHook())
                        .color(NamedTextColor.WHITE).decorate(TextDecoration.BOLD))
                .append(Component.text(" x" + amount, NamedTextColor.AQUA)).append(Component.newline())
                .append(MM.deserialize(DIVIDER)).append(Component.newline());

        if (isBuy) {
            builder.append(MM.deserialize("<gray>预计总额：</gray><red>-" + String.format("%.2f", total) + "</red>")).append(MM.deserialize(CURRENCY)).append(Component.newline())
                   .append(MM.deserialize("<gray>当前余额：</gray><white>" + String.format("%.2f", balance) + "</white>")).append(MM.deserialize(CURRENCY));
            if (balance < total) builder.append(MM.deserialize("<newline><red><b>余额不足，请调整采购方案！</b></red>"));
        } else {
            builder.append(MM.deserialize("<gray>成交单价：</gray><white>" + String.format("%.2f", price) + "</white>")).append(MM.deserialize(CURRENCY)).append(Component.newline())
                   .append(MM.deserialize("<gray>预计收益：</gray><green>+" + String.format("%.2f", total) + "</green>")).append(MM.deserialize(CURRENCY));
        }
        return builder.build();
    }

    private static void createAndShowDialog(Player player, Component title, Component body, List<ActionButton> actions) {
        player.showDialog(Dialog.create(factory -> {
            DialogRegistryEntry.Builder builder = factory.empty();
            builder.base(DialogBase.builder(title).body(List.of(DialogBody.plainMessage(body))).build());
            if (actions.size() == 2) {
                builder.type(DialogType.confirmation(actions.get(0), actions.get(1)));
            } else {
                builder.type(DialogType.multiAction(actions).build());
            }
        }));
    }

    private static void showTransactionDialog(Player player, MarketItem item, String title, Component content, List<ActionButton> actions) {
        KyochigoPlugin plugin = KyochigoPlugin.getInstance();
        player.showDialog(Dialog.create(factory -> {
            DialogRegistryEntry.Builder builder = factory.empty();
            ItemStack icon = plugin.getMarketManager().getItemIcon(item);
            builder.base(DialogBase.builder(MM.deserialize(title))
                    .body(List.of(DialogBody.item(icon).description(DialogBody.plainMessage(content)).build()))
                    .build());
            builder.type(DialogType.multiAction(actions).build());
        }));
    }

    // --- 逻辑与辅助逻辑 (保持不变) ---

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
            ActionButton.builder(confirmBtnText).action(canProceed ? DialogAction.customClick(callback, DEFAULT_OPTIONS) : null).build(),
            ActionButton.builder(CANCEL).build()
        );
        showTransactionDialog(player, item, isBuy ? BUY_TITLE : SELL_TITLE, content, actions);
    }

    private static String getCategoryFriendlyName(String categoryId) {
        KyochigoPlugin plugin = KyochigoPlugin.getInstance();
        String path = "categories." + categoryId + ".name";
        String fromConfig = plugin.getConfiguration().getRaw().getString(path);
        return fromConfig != null ? fromConfig.replaceAll("<[^>]*>", "") : 
               Map.of("ores", "矿产资源", "food", "烹饪物资", "crops", "农耕作物", "weapons", "神兵利器")
               .getOrDefault(categoryId.toLowerCase(), categoryId);
    }

    private static boolean shouldShowBuyOptions(Boolean isBuyMode) { return isBuyMode == null || isBuyMode; }
    private static boolean shouldShowSellOptions(Boolean isBuyMode) { return isBuyMode == null || !isBuyMode; }

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