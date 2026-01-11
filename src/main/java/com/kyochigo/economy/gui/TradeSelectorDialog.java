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
import net.kyori.adventure.key.Key;
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
 * 交易选货中心 (v5.5 最终优化版)
 * 职责：专门用于交易流程中的商品选择。
 * 优化：提取通用按钮逻辑，统一价格格式化，简化构建流程。
 */
public class TradeSelectorDialog {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final ClickCallback.Options DEFAULT_OPTIONS = ClickCallback.Options.builder().build();
    private static final Component BTN_BACK = MM.deserialize("<gray>[ 返回上级 ]</gray>");
    private static final Component BTN_CLOSE = MM.deserialize("<red>[ 关闭菜单 ]</red>");
    private static final Key FONT_UNIFORM = Key.key("minecraft:uniform");

    /**
     * 打开分类选择器
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

    // =========================================================================
    // 核心工厂方法 (Factory Methods)
    // =========================================================================

    /**
     * 通用按钮创建器 (消除重复代码)
     */
    private static ActionButton createButton(Component label, DialogActionCallback callback) {
        return ActionButton.builder(label)
                .action(DialogAction.customClick(callback, DEFAULT_OPTIONS))
                .build();
    }

    /**
     * 价格格式化器 (统一视觉风格)
     */
    private static Component formatPrice(double price, boolean isBuyMode) {
        String priceStr = String.format("%8.2f ⛁", price);
        return Component.text(priceStr, isBuyMode ? NamedTextColor.AQUA : NamedTextColor.GOLD)
                .font(FONT_UNIFORM);
    }

    // =========================================================================
    // 业务构建逻辑
    // =========================================================================

    private static Component createTitle(boolean isBuyMode, String template) {
        String color = isBuyMode ? "<green>" : "<gold>";
        String actionName = isBuyMode ? "采购" : "出售";
        return MM.deserialize(String.format(color + "<b>" + template + "</b>" + color, actionName));
    }

    private static ActionButton createCategoryButton(KyochigoPlugin plugin, String categoryKey, boolean isBuyMode) {
        return createButton(
            getCategoryName(plugin, categoryKey),
            (v, a) -> { if (a instanceof Player p) openItemSelect(p, categoryKey, isBuyMode); }
        );
    }

    private static ActionButton createItemButton(KyochigoPlugin plugin, MarketItem item, boolean isBuyMode) {
        double price = isBuyMode ? item.getBuyPrice() : item.getSellPrice();
        
        Component label = Component.text()
                .append(item.getDisplayNameComponent(plugin.getMarketManager().getCraftEngineHook()))
                .append(Component.text(" ┃ ", NamedTextColor.DARK_GRAY))
                .append(formatPrice(price, isBuyMode))
                .build();

        return createButton(
            label,
            (v, a) -> { if (a instanceof Player p) TransactionDialog.openActionMenu(p, item, isBuyMode); }
        );
    }

    private static ActionButton createBackButton(boolean isBuyMode) {
        return createButton(
            BTN_BACK,
            (v, a) -> { if (a instanceof Player p) openCategorySelect(p, isBuyMode); }
        );
    }

    private static void showDialog(Player player, Component title, List<ActionButton> buttons) {
        player.showDialog(Dialog.create(factory -> {
            DialogRegistryEntry.Builder builder = factory.empty();
            builder.base(DialogBase.builder(title).build());
            // 强制垂直列表布局
            builder.type(DialogType.multiAction(buttons).build());
        }));
    }

    private static Component getCategoryName(KyochigoPlugin plugin, String categoryKey) {
        String path = "categories." + categoryKey + ".name";
        String rawName = plugin.getConfiguration().getRaw().getString(path, categoryKey);
        return MM.deserialize(rawName).decoration(TextDecoration.ITALIC, false);
    }
}