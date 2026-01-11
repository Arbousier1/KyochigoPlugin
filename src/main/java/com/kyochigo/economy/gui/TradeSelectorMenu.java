package com.kyochigo.economy.gui;

import com.kyochigo.economy.KyochigoPlugin;
import com.kyochigo.economy.model.MarketItem;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 商业柜台 (严格对齐 MarketDialog 逻辑标准版)
 * * 核心逻辑：
 * 1. 物理同步：直接调用 MarketItem.getBuyPrice/getSellPrice，不进行本地二次计算。
 * 2. 视觉一致：统一使用 minecraft:uniform 字体与 %8.2f 格式化。
 * 3. 术语对齐：统一使用“购买：”与“售卖：”。
 */
public class TradeSelectorMenu implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final NamespacedKey MENU_KEY = new NamespacedKey("kyochigo", "trade_menu");
    private static final Key FONT_UNIFORM = Key.key("minecraft:uniform");
    
    private static final int ITEMS_PER_PAGE = 45; 
    private static final Map<UUID, String> playerCurrentCategory = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> playerCurrentPage = new ConcurrentHashMap<>();
    private static final ItemStack BORDER_PANE = createBorderPane();

    /**
     * 打开柜台菜单（包含后端价格强制同步）
     */
    public static void openItemSelect(Player player, String categoryId, int page) {
        KyochigoPlugin plugin = KyochigoPlugin.getInstance();
        
        // 1. 筛选当前分类下的所有物品
        List<MarketItem> categoryItems = plugin.getMarketManager().getAllItems().stream()
                .filter(i -> i.getCategory().equalsIgnoreCase(categoryId))
                .collect(Collectors.toList());

        List<String> itemIds = categoryItems.stream()
                .map(MarketItem::getConfigKey)
                .collect(Collectors.toList());

        // 2. 强制拉取后端最新实时价格
        plugin.getBackendManager().fetchBulkPrices(itemIds, response -> {
            if (response != null) {
                plugin.getMarketManager().updateInternalData(response);
            }
            
            // 3. 回到主线程构建 UI
            Bukkit.getScheduler().runTask(plugin, () -> {
                buildAndShowInventory(player, categoryId, page, categoryItems);
            });
        });
    }

    private static void buildAndShowInventory(Player player, String categoryId, int page, List<MarketItem> items) {
        KyochigoPlugin plugin = KyochigoPlugin.getInstance();
        String categoryName = getCategoryNameRaw(plugin, categoryId).replaceAll("<[^>]*>", "");
        
        playerCurrentCategory.put(player.getUniqueId(), categoryId);
        playerCurrentPage.put(player.getUniqueId(), page);

        int totalPages = Math.max(1, (int) Math.ceil((double) items.size() / ITEMS_PER_PAGE));
        Component title = MM.deserialize("<gradient:#40E0D0:#008080>商业柜台 » " + categoryName + "</gradient> <gray>(" + (page + 1) + "/" + totalPages + ")");
        Inventory inv = Bukkit.createInventory(null, 54, title);

        // 底部导航栏布局
        for (int i = 45; i < 54; i++) inv.setItem(i, BORDER_PANE);
        inv.setItem(45, createNavButton(Material.IRON_DOOR, "<red>返回主柜台", "back", true));
        inv.setItem(48, createNavButton(Material.ARROW, "<aqua>上一页", "prev", page > 0));
        inv.setItem(49, createNavButton(Material.NETHER_STAR, "<yellow>刷新行情", "refresh", true));
        inv.setItem(50, createNavButton(Material.ARROW, "<aqua>下一页", "next", page < totalPages - 1));
        inv.setItem(53, createNavButton(Material.BARRIER, "<gray>关闭菜单", "close", true));

        // 物品列表渲染
        int startIdx = page * ITEMS_PER_PAGE;
        int endIdx = Math.min(startIdx + ITEMS_PER_PAGE, items.size());
        int slot = 0;
        for (int i = startIdx; i < endIdx; i++) {
            inv.setItem(slot++, buildMarketItemStack(items.get(i), plugin));
        }

        player.openInventory(inv);
    }

    /**
     * 核心渲染器：应用物理同步与视觉对齐标准
     */
    private static ItemStack buildMarketItemStack(MarketItem item, KyochigoPlugin plugin) {
        ItemStack stack = plugin.getMarketManager().getItemIcon(item);
        ItemMeta meta = stack.getItemMeta();

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        
        // 1. 环境状态参考
        double envIndex = plugin.getMarketManager().getLastEnvIndex();
        String envNote = plugin.getMarketManager().getLastEnvNote();
        String translatedNote = switch (envNote.toLowerCase()) {
            case "normal" -> "行情平稳";
            case "weekend" -> "周末特惠";
            case "prosperous" -> "贸易繁荣";
            case "depressed" -> "行情低迷";
            default -> envNote;
        };
        lore.add(MM.deserialize("<gray>实时状态: <white>" + translatedNote + "</white> <aqua>(x" + String.format("%.2f", envIndex) + ")</aqua>"));
        lore.add(Component.empty());

        // 2. 【核心对齐】物理同步：直接读取逻辑层判定的价格
        String buyStr = String.format("%8.2f ⛁", item.getBuyPrice());
        String sellStr = String.format("%8.2f ⛁", item.getSellPrice());

        // 3. 描述对齐：统一标签、颜色、等宽字体
        lore.add(Component.text()
                .append(Component.text("购买：", NamedTextColor.GRAY))
                .append(Component.text(buyStr, NamedTextColor.WHITE).font(FONT_UNIFORM))
                .build());

        lore.add(Component.text()
                .append(Component.text("售卖：", NamedTextColor.GRAY))
                .append(Component.text(sellStr, NamedTextColor.WHITE).font(FONT_UNIFORM))
                .build());
        
        lore.add(Component.empty());
        lore.add(MM.deserialize("<gradient:#40E0D0:#00F260><b>左键点击 ➔ 发起采购</b></gradient>"));
        lore.add(MM.deserialize("<gradient:#F2994A:#F2C94C><b>右键点击 ➔ 发起出售</b></gradient>"));
        lore.add(Component.empty());
        lore.add(MM.deserialize("<dark_gray>Serial: " + item.getConfigKey().toUpperCase() + "</dark_gray>"));

        meta.lore(lore);
        
        // 渲染名称
        Component displayName = item.getDisplayNameComponent(plugin.getMarketManager().getCraftEngineHook())
                .decoration(TextDecoration.ITALIC, false);
        
        meta.displayName(Component.text()
                .append(Component.text("✨ ", NamedTextColor.AQUA))
                .append(displayName)
                .build());

        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES);
        meta.getPersistentDataContainer().set(MENU_KEY, PersistentDataType.STRING, "product");
        
        stack.setItemMeta(meta);
        return stack;
    }

    // =========================================================================
    // 交互逻辑处理器
    // =========================================================================

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!isKyochigoMenu(event.getView().title())) return;

        event.setCancelled(true); 

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        String action = clicked.getItemMeta().getPersistentDataContainer().get(MENU_KEY, PersistentDataType.STRING);
        if (action == null) return;

        handleAction(player, action, event);
    }

    private void handleAction(Player player, String action, InventoryClickEvent event) {
        UUID uuid = player.getUniqueId();
        String cat = playerCurrentCategory.getOrDefault(uuid, "ores");
        int page = playerCurrentPage.getOrDefault(uuid, 0);

        switch (action) {
            case "next" -> openItemSelect(player, cat, page + 1);
            case "prev" -> openItemSelect(player, cat, page - 1);
            case "refresh" -> openItemSelect(player, cat, page);
            case "back" -> TransactionDialog.openEntryMenu(player, null);
            case "close" -> player.closeInventory();
            case "product" -> {
                MarketItem item = KyochigoPlugin.getInstance().getMarketManager().findMarketItem(event.getCurrentItem());
                if (item != null) {
                    if (event.getClick() == ClickType.LEFT) {
                        TransactionDialog.openActionMenu(player, item, true);
                    } else if (event.getClick() == ClickType.RIGHT) {
                        TransactionDialog.openActionMenu(player, item, false);
                    }
                }
            }
        }
    }

    // =========================================================================
    // 辅助工具类
    // =========================================================================

    private static ItemStack createNavButton(Material mat, String name, String action, boolean enabled) {
        if (!enabled) return BORDER_PANE;
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(MM.deserialize(name).decoration(TextDecoration.ITALIC, false).decorate(TextDecoration.BOLD));
        meta.getPersistentDataContainer().set(MENU_KEY, PersistentDataType.STRING, action);
        meta.lore(Collections.singletonList(MM.deserialize("<gray>点击执行导航操作</gray>")));
        stack.setItemMeta(meta);
        return stack;
    }

    private static ItemStack createBorderPane() {
        ItemStack item = new ItemStack(Material.CYAN_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            item.setItemMeta(meta);
        }
        return item;
    }

    private boolean isKyochigoMenu(Component title) {
        String plainTitle = PlainTextComponentSerializer.plainText().serialize(title);
        return plainTitle.contains("商业柜台");
    }

    private static String getCategoryNameRaw(KyochigoPlugin plugin, String categoryKey) {
        return plugin.getConfiguration().getRaw().getString("categories." + categoryKey + ".name", categoryKey);
    }

    @EventHandler public void onInventoryDrag(InventoryDragEvent e) { if (isKyochigoMenu(e.getView().title())) e.setCancelled(true); }
    @EventHandler public void onInventoryClose(InventoryCloseEvent e) { playerCurrentCategory.remove(e.getPlayer().getUniqueId()); playerCurrentPage.remove(e.getPlayer().getUniqueId()); }
}