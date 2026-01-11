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
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 市场行情中心 (v3.2 极致性能版)
 * 修正内容：使用 Placeholder 替代失效的 asString() 方法，解决颜色解析报错。
 */
public class MarketDialog {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final ClickCallback.Options DEFAULT_OPTIONS = ClickCallback.Options.builder().build();
    
    // 静态 UI 组件常量
    private static final Component SEPARATOR = Component.text("────────────────────────────────", NamedTextColor.DARK_GRAY);
    private static final Component BTN_BACK = MM.deserialize("<gray>[ 返回 ]</gray>");
    private static final Component BTN_LEAVE = MM.deserialize("<red>[ 离开 ]</red>");
    
    private static final double THRESHOLD_PROSPEROUS = 1.05;
    private static final double THRESHOLD_DEPRESSED = 0.95;

    /**
     * 打开行情总览 (主菜单)
     */
    public static void open(@NotNull Player player, boolean viewOnly) {
        KyochigoPlugin plugin = KyochigoPlugin.getInstance();
        ConfigurationSection categories = plugin.getConfiguration().getRaw().getConfigurationSection("categories");

        if (categories == null) {
            player.sendMessage(MM.deserialize("<red>错误：无法读取分类配置。</red>"));
            return;
        }

        List<ActionButton> buttons = categories.getKeys(false).stream()
                .map(key -> createCategoryButton(plugin, key, viewOnly))
                .collect(Collectors.toList());

        player.showDialog(Dialog.create(factory -> {
            DialogRegistryEntry.Builder builder = factory.empty();
            builder.base(DialogBase.builder(MM.deserialize("<gold><b>实时市场行情</b></gold>")).build());
            builder.type(DialogType.multiAction(buttons).build());
        }));
    }

    private static ActionButton createCategoryButton(KyochigoPlugin plugin, String key, boolean viewOnly) {
        DialogActionCallback callback = (view, audience) -> {
            if (audience instanceof Player p) fetchPricesAndOpenSubMenu(p, key, viewOnly);
        };
        return ActionButton.builder(getCategoryName(plugin, key))
                .action(DialogAction.customClick(callback, DEFAULT_OPTIONS))
                .build();
    }

    private static void fetchPricesAndOpenSubMenu(Player player, String categoryId, boolean viewOnly) {
        KyochigoPlugin plugin = KyochigoPlugin.getInstance();
        List<String> itemIds = plugin.getMarketManager().getAllItems().stream()
                .filter(i -> i.getCategory().equalsIgnoreCase(categoryId))
                .map(MarketItem::getConfigKey)
                .collect(Collectors.toList());

        plugin.getBackendManager().fetchBulkPrices(itemIds, response -> {
            if (!player.isOnline()) return;
            if (response != null) plugin.getMarketManager().updateInternalData(response);
            showCategoryBoard(player, categoryId, viewOnly);
        });
    }

    private static void showCategoryBoard(Player player, String category, boolean viewOnly) {
        KyochigoPlugin plugin = KyochigoPlugin.getInstance();
        List<MarketItem> items = plugin.getMarketManager().getAllItems().stream()
                .filter(i -> i.getCategory().equalsIgnoreCase(category))
                .collect(Collectors.toList());

        List<DialogBody> rows = buildItemRows(plugin, items, player);

        player.showDialog(Dialog.create(factory -> {
            DialogRegistryEntry.Builder builder = factory.empty();
            builder.base(DialogBase.builder(getCategoryName(plugin, category)).body(rows).build());

            DialogActionCallback backAction = (v, a) -> { if (a instanceof Player p) open(p, viewOnly); };
            
            builder.type(DialogType.confirmation(
                    ActionButton.builder(BTN_BACK).action(DialogAction.customClick(backAction, DEFAULT_OPTIONS)).build(),
                    ActionButton.builder(BTN_LEAVE).build()
            ));
        }));
    }

    private static List<DialogBody> buildItemRows(KyochigoPlugin plugin, List<MarketItem> items, Player player) {
        List<DialogBody> rows = new ArrayList<>();
        double envIndex = plugin.getMarketManager().getLastEnvIndex();

        rows.add(DialogBody.plainMessage(renderMarketHeader(envIndex)));
        rows.add(DialogBody.plainMessage(SEPARATOR));

        for (MarketItem item : items) {
            ItemStack icon = plugin.getMarketManager().getItemIcon(item);
            icon.lore(renderItemLore(item, player, plugin));

            rows.add(DialogBody.item(icon)
                    .description(DialogBody.plainMessage(renderItemInfo(item, plugin)))
                    .build());
        }
        return rows;
    }

    // =========================================================================
    // 渲染器方法 (Renderers)
    // =========================================================================

    private static Component renderMarketHeader(double envIndex) {
        TextComponent.Builder header = Component.text().append(Component.text("市场环境：", NamedTextColor.GRAY));
        
        if (envIndex > THRESHOLD_PROSPEROUS) {
            header.append(Component.text("贸易繁荣 (+" + (int)((envIndex - 1) * 100) + "%)", NamedTextColor.GREEN));
        } else if (envIndex < THRESHOLD_DEPRESSED) {
            header.append(Component.text("行情低迷", NamedTextColor.RED));
        } else {
            header.append(Component.text("基本平稳", NamedTextColor.WHITE));
        }
        return header.build();
    }

    private static List<Component> renderItemLore(MarketItem item, Player player, KyochigoPlugin plugin) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("» 市场详细数据", NamedTextColor.DARK_GRAY));
        lore.add(renderHeatLore(item.getTempNeff()));
        renderQuotaLore(item, player, plugin).ifPresent(lore::add);
        return lore;
    }

    private static Component renderHeatLore(double neff) {
        TextComponent.Builder line = Component.text().append(Component.text("当前热度：", NamedTextColor.GRAY));
        if (neff < 100) line.append(Component.text("极度匮乏", NamedTextColor.GREEN));
        else if (neff < 1000) line.append(Component.text("供需平衡", NamedTextColor.WHITE));
        else line.append(Component.text("大量积压", NamedTextColor.RED));
        return line.build();
    }

    /**
     * 核心修正：使用 Placeholder 占位符解析动态颜色与数值
     */
    private static Optional<Component> renderQuotaLore(MarketItem item, Player player, KyochigoPlugin plugin) {
        int limit = plugin.getConfiguration().getItemDailyLimit(item.getConfigKey());
        if (limit <= 0) return Optional.empty();

        int traded = plugin.getHistoryManager().getDailyTradeCount(player.getUniqueId().toString(), item.getConfigKey());
        int remain = Math.max(0, limit - traded);
        
        // 判定颜色对象
        NamedTextColor color = (remain > limit * 0.2) ? NamedTextColor.WHITE : NamedTextColor.RED;

        // 使用 Placeholder 链式调用
        return Optional.of(MM.deserialize(
            "<gray>今日配额：</gray><color><traded> / <limit></color> <gray>(余 <remain>)</gray>",
            Placeholder.styling("color", color),
            Placeholder.unparsed("traded", String.valueOf(traded)),
            Placeholder.unparsed("limit", String.valueOf(limit)),
            Placeholder.unparsed("remain", String.valueOf(remain))
        ));
    }

    private static Component renderItemInfo(MarketItem item, KyochigoPlugin plugin) {
        return Component.text()
                .append(item.getDisplayNameComponent(plugin.getMarketManager().getCraftEngineHook()).decorate(TextDecoration.BOLD))
                .append(Component.newline())
                .append(Component.text("商会回收：", NamedTextColor.GRAY))
                .append(Component.text(String.format("%.2f ⛁", item.getSellPrice()), NamedTextColor.WHITE))
                .append(Component.text(" ┃ ", NamedTextColor.DARK_GRAY))
                .append(Component.text("物资申购：", NamedTextColor.GRAY))
                .append(Component.text(String.format("%.2f ⛁", item.getBuyPrice()), NamedTextColor.WHITE))
                .build();
    }

    private static Component getCategoryName(KyochigoPlugin plugin, String categoryKey) {
        String path = "categories." + categoryKey + ".name";
        String rawName = plugin.getConfiguration().getRaw().getString(path, categoryKey);
        return MM.deserialize(rawName).decoration(TextDecoration.ITALIC, false);
    }
}