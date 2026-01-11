package com.kyochigo.economy.gui;

import com.kyochigo.economy.KyochigoPlugin;
import com.kyochigo.economy.model.MarketItem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
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
 * 现代化分页选货菜单 (v9.0 最终版)
 * 整合了精美视觉、翻页系统与严密的防取防拖拽安全逻辑
 */
public class TradeSelectorMenu implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final NamespacedKey MENU_KEY = new NamespacedKey("kyochigo", "trade_menu");
    private static final NamespacedKey PAGE_KEY = new NamespacedKey("kyochigo", "menu_page");
    
    // 分页配置
    private static final int ITEMS_PER_PAGE = 45; // 5行物品 + 1行导航
    private static final Map<UUID, String> playerCurrentCategory = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> playerCurrentPage = new ConcurrentHashMap<>();

    // 缓存静态视觉组件
    private static final ItemStack BORDER_PANE = createBorderPane();

    // --- 核心入口 ---

    public static void openItemSelect(Player player, String categoryId) {
        openItemSelect(player, categoryId, 0);
    }

    public static void openItemSelect(Player player, String categoryId, int page) {
        KyochigoPlugin plugin = KyochigoPlugin.getInstance();
        String categoryName = getCategoryNameRaw(plugin, categoryId).replaceAll("<[^>]*>", "");
        
        // 记录状态供刷新使用
        playerCurrentCategory.put(player.getUniqueId(), categoryId);
        playerCurrentPage.put(player.getUniqueId(), page);

        List<MarketItem> items = plugin.getMarketManager().getAllItems().stream()
                .filter(i -> i.getCategory().equalsIgnoreCase(categoryId))
                .collect(Collectors.toList());

        int totalPages = (int) Math.ceil((double) items.size() / ITEMS_PER_PAGE);
        if (totalPages == 0) totalPages = 1;

        // 创建大箱子 (54格)
        Component title = MM.deserialize("<gradient:#40E0D0:#008080>商业柜台 » " + categoryName + "</gradient> <gray>(" + (page + 1) + "/" + totalPages + ")");
        Inventory inv = Bukkit.createInventory(null, 54, title);

        // 1. 填充动态边框 (最后一行)
        for (int i = 45; i < 54; i++) inv.setItem(i, BORDER_PANE);

        // 2. 放置翻页/功能按钮
        inv.setItem(48, createNavButton(Material.ARROW, "<aqua>上一页", "prev", page > 0));
        inv.setItem(49, createNavButton(Material.NETHER_STAR, "<yellow>刷新行情", "refresh", true));
        inv.setItem(50, createNavButton(Material.ARROW, "<aqua>下一页", "next", page < totalPages - 1));
        inv.setItem(45, createNavButton(Material.IRON_DOOR, "<red>返回主柜台", "back", true));
        inv.setItem(53, createNavButton(Material.BARRIER, "<gray>关闭菜单", "close", true));

        // 3. 填充当前页物品
        int startIdx = page * ITEMS_PER_PAGE;
        int endIdx = Math.min(startIdx + ITEMS_PER_PAGE, items.size());
        
        int slot = 0;
        for (int i = startIdx; i < endIdx; i++) {
            inv.setItem(slot++, buildMarketItemStack(items.get(i), plugin));
        }

        player.openInventory(inv);
    }

    // --- 安全防护逻辑 ---

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        
        // 通过标题的前缀模糊匹配或检查 Holder
        if (!isKyochigoMenu(event.getView().title())) return;

        event.setCancelled(true); // 核心防护：禁止点击取出

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        String action = clicked.getItemMeta().getPersistentDataContainer().get(MENU_KEY, PersistentDataType.STRING);
        if (action == null) return;

        handleAction(player, action, event);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (isKyochigoMenu(event.getView().title())) {
            event.setCancelled(true); // 核心防护：禁止拖拽物品
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        playerCurrentCategory.remove(event.getPlayer().getUniqueId());
        playerCurrentPage.remove(event.getPlayer().getUniqueId());
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

    // --- 视觉构建辅助 ---

    private static ItemStack buildMarketItemStack(MarketItem item, KyochigoPlugin plugin) {
        ItemStack stack = plugin.getMarketManager().getItemIcon(item);
        ItemMeta meta = stack.getItemMeta();

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(MM.deserialize("<gray>实时行情统计:</gray>"));
        lore.add(MM.deserialize("<white> 采购支付 <green>» " + String.format("%.2f", item.getBuyPrice()) + " ⛁</green>"));
        lore.add(MM.deserialize("<white> 出售收益 <gold>» " + String.format("%.2f", item.getSellPrice()) + " ⛁</gold>"));
        lore.add(Component.empty());
        lore.add(MM.deserialize("<gradient:#40E0D0:#00F260><b>左键点击 ➔ 发起购买申请</b></gradient>"));
        lore.add(MM.deserialize("<gradient:#F2994A:#F2C94C><b>右键点击 ➔ 发起资产出售</b></gradient>"));
        lore.add(Component.empty());
        lore.add(MM.deserialize("<dark_gray>Serial: " + item.getConfigKey().toUpperCase() + "</dark_gray>"));

        meta.lore(lore);
        
        // 增加精美发光效果 (可选)
        meta.addEnchant(Enchantment.DURABILITY, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES);
        
        // 增加星星装饰到名字
        Component originalName = meta.hasDisplayName() ? meta.displayName() : Component.text(item.getPlainDisplayName());
        meta.displayName(Component.text().append(Component.text("✨ ", NamedTextColor.AQUA)).append(originalName).build());

        meta.getPersistentDataContainer().set(MENU_KEY, PersistentDataType.STRING, "product");
        stack.setItemMeta(meta);
        return stack;
    }

    private static ItemStack createNavButton(Material mat, String name, String action, boolean enabled) {
        if (!enabled) return BORDER_PANE;
        
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(MM.deserialize(name).decoration(TextDecoration.ITALIC, false).decorate(TextDecoration.BOLD));
        meta.getPersistentDataContainer().set(MENU_KEY, PersistentDataType.STRING, action);
        
        // 装饰性 Lore
        meta.lore(Collections.singletonList(MM.deserialize("<gray>点击执行此导航操作</gray>")));
        stack.setItemMeta(meta);
        return stack;
    }

    private static ItemStack createBorderPane() {
        ItemStack item = new ItemStack(Material.CYAN_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.empty());
        item.setItemMeta(meta);
        return item;
    }

    private boolean isKyochigoMenu(Component title) {
        String plainTitle = PlainTextComponentSerializer.plainText().serialize(title);
        return plainTitle.contains("商业柜台");
    }

    private static String getCategoryNameRaw(KyochigoPlugin plugin, String categoryKey) {
        return plugin.getConfiguration().getRaw().getString("categories." + categoryKey + ".name", categoryKey);
    }
}