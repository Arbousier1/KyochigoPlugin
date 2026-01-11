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
 * 市场行情中心 (v4.0 核心重构版)
 * 优化内容：
 * 1. 采用通用 Dialog 构建器，减少代码重复
 * 2. 优化渲染逻辑，统一 UI 风格
 * 3. 保持 "售卖/购买" 术语与 TransactionDialog 一致
 */
public class MarketDialog {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final ClickCallback.Options DEFAULT_OPTIONS = ClickCallback.Options.builder().build();
    
    // 静态常量
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

        // 构建分类按钮
        List<ActionButton> buttons = categories.getKeys(false).stream()
                .map(key -> createBtn(
                    getCategoryName(plugin, key), 
                    (v, a) -> { if (a instanceof Player p) fetchPricesAndOpenSubMenu(p, key, viewOnly); }
                ))
                .collect(Collectors.toList());

        showMarketDialog(player, MM.deserialize("<gold><b>实时市场行情</b></gold>"), List.of(), buttons);
    }

    /**
     * 数据预加载逻辑
     */
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

    /**
     * 显示具体分类面板 (Sub-Menu)
     */
    private static void showCategoryBoard(Player player, String category, boolean viewOnly) {
        KyochigoPlugin plugin = KyochigoPlugin.getInstance();
        
        // 1. 筛选物品
        List<MarketItem> items = plugin.getMarketManager().getAllItems().stream()
                .filter(i -> i.getCategory().equalsIgnoreCase(category))
                .collect(Collectors.toList());

        // 2. 构建列表内容
        List<DialogBody> rows = buildMarketRows(plugin, items, player);

        // 3. 构建底部导航 (返回/离开)
        List<ActionButton> navButtons = List.of(
            createBtn(BTN_BACK, (v, a) -> { if (a instanceof Player p) open(p, viewOnly); }),
            ActionButton.builder(BTN_LEAVE).build()
        );

        showMarketDialog(player, getCategoryName(plugin, category), rows, navButtons);
    }

    // =========================================================================
    // 核心构建器 (Core Builders)
    // =========================================================================

    /**
     * 通用对话框显示方法
     */
    private static void showMarketDialog(Player player, Component title, List<DialogBody> body, List<ActionButton> actions) {
        player.showDialog(Dialog.create(factory -> {
            DialogRegistryEntry.Builder builder = factory.empty();
            builder.base(DialogBase.builder(title).body(body).build());

            // 智能判断类型：如果是2个按钮则为 Confirmation (用于子菜单)，否则为 MultiAction (用于主菜单)
            if (actions.size() == 2) {
                builder.type(DialogType.confirmation(actions.get(0), actions.get(1)));
            } else {
                builder.type(DialogType.multiAction(actions).build());
            }
        }));
    }

    private static List<DialogBody> buildMarketRows(KyochigoPlugin plugin, List<MarketItem> items, Player player) {
        List<DialogBody> rows = new ArrayList<>();
        double envIndex = plugin.getMarketManager().getLastEnvIndex();

        // 添加头部信息
        rows.add(DialogBody.plainMessage(renderMarketHeader(envIndex)));
        rows.add(DialogBody.plainMessage(SEPARATOR));

        // 添加物品列表
        for (MarketItem item : items) {
            ItemStack icon = plugin.getMarketManager().getItemIcon(item);
            icon.lore(renderItemLore(item, player, plugin));

            rows.add(DialogBody.item(icon)
                    .description(DialogBody.plainMessage(renderItemInfo(item, plugin)))
                    .build());
        }
        return rows;
    }

    private static ActionButton createBtn(Component label, DialogActionCallback callback) {
        return ActionButton.builder(label)
                .action(DialogAction.customClick(callback, DEFAULT_OPTIONS))
                .build();
    }
    
    // 重载方法支持 String
    private static ActionButton createBtn(String label, DialogActionCallback callback) {
        return createBtn(MM.deserialize(label), callback);
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

    private static Optional<Component> renderQuotaLore(MarketItem item, Player player, KyochigoPlugin plugin) {
        int limit = plugin.getConfiguration().getItemDailyLimit(item.getConfigKey());
        if (limit <= 0) return Optional.empty();

        int traded = plugin.getHistoryManager().getDailyTradeCount(player.getUniqueId().toString(), item.getConfigKey());
        int remain = Math.max(0, limit - traded);
        
        NamedTextColor color = (remain > limit * 0.2) ? NamedTextColor.WHITE : NamedTextColor.RED;

        return Optional.of(MM.deserialize(
            "<gray>今日配额：</gray><color><traded> / <limit></color> <gray>(余 <remain>)</gray>",
            Placeholder.styling("color", color),
            Placeholder.unparsed("traded", String.valueOf(traded)),
            Placeholder.unparsed("limit", String.valueOf(limit)),
            Placeholder.unparsed("remain", String.valueOf(remain))
        ));
    }

    /**
     * 物品信息渲染 (售卖/购买 格式)
     */
    private static Component renderItemInfo(MarketItem item, KyochigoPlugin plugin) {
        return Component.text()
                .append(Component.text("售卖：", NamedTextColor.GRAY))
                .append(Component.text(String.format("%.2f ⛁", item.getSellPrice()), NamedTextColor.WHITE))
                .append(Component.text(" ┃ ", NamedTextColor.DARK_GRAY))
                .append(Component.text("购买：", NamedTextColor.GRAY))
                .append(Component.text(String.format("%.2f ⛁", item.getBuyPrice()), NamedTextColor.WHITE))
                .build();
    }

    private static Component getCategoryName(KyochigoPlugin plugin, String categoryKey) {
        String path = "categories." + categoryKey + ".name";
        String rawName = plugin.getConfiguration().getRaw().getString(path, categoryKey);
        return MM.deserialize(rawName).decoration(TextDecoration.ITALIC, false);
    }
}