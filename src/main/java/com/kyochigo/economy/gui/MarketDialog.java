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
 * 市场行情中心 (v4.1 对齐优化版)
 * 优化内容：
 * 1. 采用通用 Dialog 构建器
 * 2. 使用 minecraft:uniform 等宽字体实现价格完美对齐
 * 3. 统一 "售卖/购买" 术语
 */
public class MarketDialog {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final ClickCallback.Options DEFAULT_OPTIONS = ClickCallback.Options.builder().build();
    // 定义 Minecraft 自带的等宽字体 Key
    private static final Key FONT_UNIFORM = Key.key("minecraft:uniform");
    
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
        // 当物品被点击时打开交易菜单 (TransactionDialog)
        // 注意：具体的点击逻辑在 buildMarketRows -> renderItemInfo (作为描述) 
        // 实际上 Paper Dialog 的 item 点击逻辑通常在 inputs 或者 DialogType 结构中，
        // 但根据你的旧代码逻辑，这里主要是展示。如果需要点击物品交易，通常需要将 Item 放入 inputs 
        // 或者使用 MultiAction 列表。
        // *修正*：根据 v4.0 的逻辑，这里展示的是分类面板。
        // 如果要在点击物品时交易，通常是在 buildMarketRows 里构造 ActionButton (如果布局允许) 
        // 或者 PaperDialog 的 body item 本身不支持点击回调(除非作为 input)。
        // 假设你的交互逻辑是在 TransactionDialog.openActionMenu 被调用时。
        // 这里我们先保持展示逻辑。
        
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

            // 这里使用 DialogBody.item 展示
            // 为了实现点击购买，通常需要在 DialogType 中定义 inputs 或者使用 ActionButton
            // 但如果这是一个纯展示板，或者你的插件通过 InventoryClickEvent 拦截 (非 Paper Dialog 原生逻辑)，则保持原样。
            // 假设需要点击交互：Paper Dialog 目前 body item 不直接支持 click callback。
            // 如果你需要点击物品进入 TransactionDialog，建议将每个物品做成一个 ActionButton (MultiAction)，
            // 但那样图标显示会受限。
            // *为了保持原有逻辑不变，我们这里仅负责渲染*。
            // *重要提示*：如果你的需求是点击这个物品图标打开交易菜单，你可能需要将 DialogType 改为 input 选择模式，
            // 或者通过监听器拦截。但在本类中，我们先关注渲染对齐。

            // 若要支持点击，通常做法是把物品作为 Button。
            // 但为了美观（显示描述），我们这里保持 DialogBody.item。
            // 并在点击事件处理逻辑中（可能在 DialogActionCallback 或者外部监听器）调用 TransactionDialog.openActionMenu。
            // 下方代码假设你的交互逻辑在外部或由 Dialog 框架处理。
            
            // 为了方便起见，这里我们假设这里是一个列表展示。
            // 如果你想让它可点击，这里需要改为 inputs，或者每一行是一个单独的 button。
            // 鉴于篇幅，这里保持原有的 Body 结构。

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
     * 物品信息渲染 (优化对齐版)
     * 使用等宽字体 + 固定宽度格式化，确保价格显示整齐。
     */
    private static Component renderItemInfo(MarketItem item, KyochigoPlugin plugin) {
        // 使用 String.format 固定保留位数和最小宽度 (总宽8字符，保留2位小数)
        String sellPrice = String.format("%8.2f ⛁", item.getSellPrice());
        String buyPrice  = String.format("%8.2f ⛁", item.getBuyPrice());

        return Component.text()
                .append(Component.text("售卖：", NamedTextColor.GRAY))
                .append(Component.text(sellPrice, NamedTextColor.WHITE).font(FONT_UNIFORM)) // 应用等宽字体
                .append(Component.text(" ┃ ", NamedTextColor.DARK_GRAY))
                .append(Component.text("购买：", NamedTextColor.GRAY))
                .append(Component.text(buyPrice, NamedTextColor.WHITE).font(FONT_UNIFORM)) // 应用等宽字体
                .build();
    }

    private static Component getCategoryName(KyochigoPlugin plugin, String categoryKey) {
        String path = "categories." + categoryKey + ".name";
        String rawName = plugin.getConfiguration().getRaw().getString(path, categoryKey);
        return MM.deserialize(rawName).decoration(TextDecoration.ITALIC, false);
    }
}