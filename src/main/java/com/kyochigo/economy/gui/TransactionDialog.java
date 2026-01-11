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
 * 交易确认对话框 (v8.0 最终适配版)
 * 职责：作为 TradeSelectorMenu 的延续，负责处理具体商品数量的确认与结算。
 */
public class TransactionDialog {

    private static final ClickCallback.Options DEFAULT_OPTIONS = ClickCallback.Options.builder().build();
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Key FONT_UNIFORM = Key.key("minecraft:uniform");

    // UI 文案
    private static final String ACTION_TITLE = "<gradient:#40E0D0:#008080><b>商业交易中心</b></gradient>";
    private static final String ENTRY_TITLE = "<gradient:#FFD700:#FFA500><b>Kyochigo 交易所</b></gradient>";
    private static final String BUY_TITLE = "<gradient:#55FF55:#00AA00><b>确认采购申请</b></gradient>";
    private static final String SELL_TITLE = "<gradient:#FFCC33:#E67E22><b>确认资产出售</b></gradient>";
    private static final String DIVIDER = "<dark_gray>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━</dark_gray>";
    private static final String CURRENCY = " <gold>⛁</gold>";

    // 静态按钮
    private static final Component CONFIRM_SELL = MM.deserialize("<bold><gradient:#FFCC33:#E67E22> [ 签署出售协议 ] </gradient></bold>");
    private static final Component CONFIRM_BUY = MM.deserialize("<bold><gradient:#55FF55:#00AA00> [ 支付并完成采购 ] </gradient></bold>");
    private static final Component CANCEL = MM.deserialize("<gray> [ 放弃本次交易 ] </gray>");
    private static final Component INSUFFICIENT_FUNDS = MM.deserialize("<gray>账户资金余额不足</gray>");
    private static final Component INSUFFICIENT_WARNING = MM.deserialize("<red><b>余额不足，请调整采购方案！</b></red>");

    // ========================================================================
    // 1. 入口逻辑 (NPC 交互第一层)
    // ========================================================================

    public static void openEntryMenu(Player player, String targetCategory) {
        // 修改点：不再在第一层区分买卖，而是引导进入统一的箱子菜单
        ActionButton btnEnter = createBtn("<gradient:#00F260:#0575E6><b>   进入柜台选货   </b></gradient>", (v, a) -> {
            if (a instanceof Player p) {
                if (targetCategory != null) {
                    TradeSelectorMenu.openItemSelect(p, targetCategory);
                } else {
                    // 若无分类则打开全分类选择 (建议也将此改为箱子菜单)
                    TradeSelectorMenu.openItemSelect(p, "misc"); 
                }
            }
        });

        Component desc;
        if (targetCategory != null) {
            String friendlyName = getCategoryFriendlyName(targetCategory);
            desc = MM.deserialize("<newline><gray>当前服务柜台：<white>" + friendlyName + "</white><newline><gray>操作指引：<white>左键购买 / 右键出售</white></gray>");
        } else {
            desc = MM.deserialize("<newline><gray>欢迎光临，请点击下方按钮开始贸易业务：</gray>");
        }

        createAndShowDialog(player, MM.deserialize(ENTRY_TITLE), desc, List.of(btnEnter, ActionButton.builder(CANCEL).build()));
    }

    // ========================================================================
    // 2. 数量选择逻辑 (箱子菜单点击后进入)
    // ========================================================================

    public static void openActionMenu(Player player, MarketItem item, Boolean isBuyMode) {
        List<ActionButton> actions = new ArrayList<>();

        if (isBuyMode) addBuyActions(actions, item);
        else addSellActions(actions, item);
        
        actions.add(ActionButton.builder(CANCEL).build());

        TextComponent.Builder desc = Component.text();
        desc.append(MM.deserialize("<newline>"));
        if (isBuyMode) {
            desc.append(formatRichPrice(item.getBuyPrice(), "采购基准单价", "#00FF7F"));
        } else {
            desc.append(formatRichPrice(item.getSellPrice(), "市场回收单价", "#FFD700"));
        }

        showTransactionDialog(player, item, ACTION_TITLE, desc.build(), actions);
    }

    private static void addBuyActions(List<ActionButton> actions, MarketItem item) {
        double price = item.getBuyPrice();
        KyochigoPlugin plugin = KyochigoPlugin.getInstance();
        actions.add(createBtn("<green>▸ 采购少量 (1个)        </green>", (v, a) -> plugin.getTransactionManager().openBuyConfirmDialog((Player)a, item, 1)));
        actions.add(createBtn("<green>▸ 采购成组 (64个)       </green>", (v, a) -> plugin.getTransactionManager().openBuyConfirmDialog((Player)a, item, 64)));
        actions.add(createBtn("<green>▸ 补齐库存 (背包装满)   </green>", (v, a) -> {
            Player p = (Player) a;
            int maxSpace = getInventoryFreeSpace(p, plugin.getMarketManager().getItemIcon(item));
            if (maxSpace > 0) plugin.getTransactionManager().openBuyConfirmDialog(p, item, maxSpace);
            else p.sendMessage(MM.deserialize("<red>仓库已满，无法容纳更多物资。</red>"));
        }));
    }

    private static void addSellActions(List<ActionButton> actions, MarketItem item) {
        double price = item.getSellPrice();
        KyochigoPlugin plugin = KyochigoPlugin.getInstance();
        actions.add(createBtn("<gold>▸ 出售少量 (1个)        </gold>", (v, a) -> plugin.getTransactionManager().openSellConfirmDialog((Player)a, item, 1)));
        actions.add(createBtn("<gold>▸ 出售整组 (64个)       </gold>", (v, a) -> plugin.getTransactionManager().openSellConfirmDialog((Player)a, item, 64)));
        actions.add(createBtn("<gold>▸ 出售全部 (清空背包)   </gold>", (v, a) -> {
            Player p = (Player) a;
            int count = countPlayerItems(p, item, plugin);
            if (count > 0) plugin.getTransactionManager().openSellConfirmDialog(p, item, count);
            else p.sendMessage(MM.deserialize("<red>您的行囊中没有该物资。</red>"));
        }));
    }

    // ========================================================================
    // 3. 最终确认阶段 (由 TransactionManager 预计算后调回)
    // ========================================================================

    public static void openBuyConfirm(Player player, MarketItem item, int amount, double unitPrice) {
        openTransactionConfirm(player, item, amount, unitPrice, true);
    }

    public static void openSellConfirm(Player player, MarketItem item, int amount, double unitPrice) {
        openTransactionConfirm(player, item, amount, unitPrice, false);
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
                // 提交执行
                plugin.getTransactionManager().executeTransaction(p, item, amount);
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
    // 4. 视觉与工具函数
    // ========================================================================

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
                .append(item.getDisplayNameComponent(plugin.getMarketManager().getCraftEngineHook()).color(NamedTextColor.WHITE).decorate(TextDecoration.BOLD))
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
            if (actions.size() == 2) builder.type(DialogType.confirmation(actions.get(0), actions.get(1)));
            else builder.type(DialogType.multiAction(actions).build());
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
            if (actions.size() == 2) builder.type(DialogType.confirmation(actions.get(0), actions.get(1)));
            else builder.type(DialogType.multiAction(actions).build());
        }));
    }

    private static String getCategoryFriendlyName(String categoryId) {
        KyochigoPlugin plugin = KyochigoPlugin.getInstance();
        String path = "categories." + categoryId + ".name";
        String fromConfig = plugin.getConfiguration().getRaw().getString(path);
        return fromConfig != null ? fromConfig.replaceAll("<[^>]*>", "") : 
               Map.of("ores", "矿产资源", "food", "烹饪物资", "crops", "农耕作物", "weapons", "神兵利器")
               .getOrDefault(categoryId.toLowerCase(), categoryId);
    }

    private static ActionButton createBtn(String label, DialogActionCallback callback) {
        return ActionButton.builder(MM.deserialize(label)).action(DialogAction.customClick(callback, DEFAULT_OPTIONS)).build();
    }

    private static int countPlayerItems(Player player, MarketItem item, KyochigoPlugin plugin) {
        int count = 0;
        for (ItemStack invItem : player.getInventory().getStorageContents()) {
            if (invItem != null && item.matches(invItem, plugin.getMarketManager().getCraftEngineHook())) {
                count += invItem.getAmount();
            }
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