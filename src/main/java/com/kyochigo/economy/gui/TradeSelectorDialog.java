package com.kyochigo.economy.gui;

import com.kyochigo.economy.KyochigoPlugin;
import com.kyochigo.economy.model.MarketItem;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.DialogRegistryEntry;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 交易选货中心 (v5.1 优化版)
 * 职责：专门用于交易流程中的商品选择，区别于 MarketDialog (行情看板)。
 */
public class TradeSelectorDialog {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final ClickCallback.Options DEFAULT_OPTIONS = ClickCallback.Options.builder().build();
    private static final Component BTN_BACK = MM.deserialize("<gray>[ 返回上级 ]</gray>");
    private static final Component BTN_CLOSE = MM.deserialize("<red>[ 关闭菜单 ]</red>");

    /**
     * 打开分类选择器
     * @param isBuyMode true=购买模式, false=售卖模式
     */
    public static void openCategorySelect(Player player, boolean isBuyMode) {
        KyochigoPlugin plugin = KyochigoPlugin.getInstance();
        ConfigurationSection categories = plugin.getConfiguration().getRaw().getConfigurationSection("categories");

        if (categories == null) return;

        Component title = createTitle(isBuyMode, "请选择%s分类");
        
        List<ActionButton> buttons = categories.getKeys(false).stream()
                .map(key -> createCategoryButton(plugin, key, isBuyMode))
                .collect(Collectors.toList());

        buttons.add(ActionButton.builder(BTN_CLOSE).build());

        showDialog(player, title, buttons);
    }

    /**
     * 打开物品选择器
     * 注意：此方法为 public，允许 TransactionDialog 直接调用 (NPC 分类跳转)
     */
    public static void openItemSelect(Player player, String categoryId, boolean isBuyMode) {
        KyochigoPlugin plugin = KyochigoPlugin.getInstance();
        
        List<MarketItem> items = plugin.getMarketManager().getAllItems().stream()
                .filter(i -> i.getCategory().equalsIgnoreCase(categoryId))
                .collect(Collectors.toList());

        Component title = createTitle(isBuyMode, "选择商品 (%s)");
        
        List<ActionButton> actions = items.stream()
                .map(item -> createItemButton(plugin, item, isBuyMode))
                .collect(Collectors.toList());

        actions.add(createBackButton(isBuyMode));

        showDialog(player, title, actions);
    }

    // ========== 辅助方法 ==========

    /**
     * 创建标题组件
     */
    private static Component createTitle(boolean isBuyMode, String template) {
        String color = isBuyMode ? "<green>" : "<gold>";
        String actionName = isBuyMode ? "采购" : "出售";
        // String.format 替换 %s
        return MM.deserialize(String.format(color + "<b>" + template + "</b>" + color, actionName));
    }

    /**
     * 创建分类按钮
     */
    private static ActionButton createCategoryButton(KyochigoPlugin plugin, String categoryKey, boolean isBuyMode) {
        DialogActionCallback callback = (v, a) -> {
            if (a instanceof Player p) openItemSelect(p, categoryKey, isBuyMode);
        };
        
        return ActionButton.builder(getCategoryName(plugin, categoryKey))
                .action(DialogAction.customClick(callback, DEFAULT_OPTIONS))
                .build();
    }

    /**
     * 创建物品按钮
     */
    private static ActionButton createItemButton(KyochigoPlugin plugin, MarketItem item, boolean isBuyMode) {
        DialogActionCallback clickAction = (view, audience) -> {
            if (audience instanceof Player p) {
                TransactionDialog.openActionMenu(p, item, isBuyMode);
            }
        };

        double price = isBuyMode ? item.getBuyPrice() : item.getSellPrice();
        Component label = Component.text()
                .append(item.getDisplayNameComponent(plugin.getMarketManager().getCraftEngineHook()))
                .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                .append(Component.text(String.format("%.2f ⛁", price), NamedTextColor.WHITE))
                .build();

        return ActionButton.builder(label)
                .action(DialogAction.customClick(clickAction, DEFAULT_OPTIONS))
                .build();
    }

    /**
     * 创建返回按钮
     */
    private static ActionButton createBackButton(boolean isBuyMode) {
        DialogActionCallback backCallback = (v, a) -> {
            if (a instanceof Player p) openCategorySelect(p, isBuyMode);
        };
        
        return ActionButton.builder(BTN_BACK)
                .action(DialogAction.customClick(backCallback, DEFAULT_OPTIONS))
                .build();
    }

    /**
     * 显示对话框的通用方法
     */
    private static void showDialog(Player player, Component title, List<ActionButton> buttons) {
        player.showDialog(Dialog.create(factory -> {
            DialogRegistryEntry.Builder builder = factory.empty();
            builder.base(DialogBase.builder(title).build());
            builder.type(DialogType.multiAction(buttons).build());
        }));
    }

    /**
     * 获取分类名称
     */
    private static Component getCategoryName(KyochigoPlugin plugin, String categoryKey) {
        String path = "categories." + categoryKey + ".name";
        String rawName = plugin.getConfiguration().getRaw().getString(path, categoryKey);
        return MM.deserialize(rawName).decoration(TextDecoration.ITALIC, false);
    }
}