package com.kyochigo.economy.gui;

import com.kyochigo.economy.KyochigoPlugin;
import com.kyochigo.economy.model.MarketItem;
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

public class TradeSelectorMenu implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final NamespacedKey MENU_KEY = new NamespacedKey("kyochigo", "trade_menu");
    
    private static final int ITEMS_PER_PAGE = 45; 
    private static final Map<UUID, String> playerCurrentCategory = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> playerCurrentPage = new ConcurrentHashMap<>();

    private static final ItemStack BORDER_PANE = createBorderPane();

    public static void openItemSelect(Player player, String categoryId) {
        openItemSelect(player, categoryId, 0);
    }

    public static void openItemSelect(Player player, String categoryId, int page) {
        KyochigoPlugin plugin = KyochigoPlugin.getInstance();
        
        List<MarketItem> items = plugin.getMarketManager().getAllItems().stream()
                .filter(i -> i.getCategory().equalsIgnoreCase(categoryId))
                .collect(Collectors.toList());

        String categoryName = getCategoryNameRaw(plugin, categoryId).replaceAll("<[^>]*>", "");
        playerCurrentCategory.put(player.getUniqueId(), categoryId);
        playerCurrentPage.put(player.getUniqueId(), page);

        int totalPages = (int) Math.ceil((double) items.size() / ITEMS_PER_PAGE);
        if (totalPages == 0) totalPages = 1;

        Component title = MM.deserialize("<gradient:#40E0D0:#008080>商业柜台 » " + categoryName + "</gradient> <gray>(" + (page + 1) + "/" + totalPages + ")");
        Inventory inv = Bukkit.createInventory(null, 54, title);

        for (int i = 45; i < 54; i++) inv.setItem(i, BORDER_PANE);

        inv.setItem(45, createNavButton(Material.IRON_DOOR, "<red>返回主柜台", "back", true));
        inv.setItem(48, createNavButton(Material.ARROW, "<aqua>上一页", "prev", page > 0));
        inv.setItem(49, createNavButton(Material.NETHER_STAR, "<yellow>刷新单价", "refresh", true));
        inv.setItem(50, createNavButton(Material.ARROW, "<aqua>下一页", "next", page < totalPages - 1));
        inv.setItem(53, createNavButton(Material.BARRIER, "<gray>关闭菜单", "close", true));

        int startIdx = page * ITEMS_PER_PAGE;
        int endIdx = Math.min(startIdx + ITEMS_PER_PAGE, items.size());
        
        int slot = 0;
        for (int i = startIdx; i < endIdx; i++) {
            inv.setItem(slot++, buildMarketItemStack(items.get(i), plugin));
        }

        player.openInventory(inv);
    }

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

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (isKyochigoMenu(event.getView().title())) {
            event.setCancelled(true); 
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
            case "refresh" -> {
                KyochigoPlugin plugin = KyochigoPlugin.getInstance();
                List<String> itemIds = plugin.getMarketManager().getAllItems().stream()
                        .map(MarketItem::getConfigKey)
                        .collect(Collectors.toList());

                plugin.getBackendManager().fetchBulkPrices(itemIds, response -> {
                    if (response != null) plugin.getMarketManager().updateInternalData(response);
                    Bukkit.getScheduler().runTask(plugin, () -> openItemSelect(player, cat, page));
                });
            }
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

    private static ItemStack buildMarketItemStack(MarketItem item, KyochigoPlugin plugin) {
        ItemStack stack = plugin.getMarketManager().getItemIcon(item);
        ItemMeta meta = stack.getItemMeta();

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        
        // 1. 修复：状态汉化映射
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

        // 2. 修复：价格实时化 (单价 * 环境指数)
        double displayBuy = item.getBuyPrice() * envIndex;
        double displaySell = item.getSellPrice() * envIndex;

        lore.add(MM.deserialize("<white> 采购支付 <green>» " + String.format("%.2f", displayBuy) + " ⛁</green>"));
        lore.add(MM.deserialize("<white> 出售获利 <gold>» " + String.format("%.2f", displaySell) + " ⛁</gold>"));
        
        lore.add(Component.empty());
        lore.add(MM.deserialize("<gradient:#40E0D0:#00F260><b>左键点击 ➔ 发起采购</b></gradient>"));
        lore.add(MM.deserialize("<gradient:#F2994A:#F2C94C><b>右键点击 ➔ 发起出售</b></gradient>"));
        lore.add(Component.empty());
        lore.add(MM.deserialize("<dark_gray>Serial: " + item.getConfigKey().toUpperCase() + "</dark_gray>"));

        meta.lore(lore);
        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES);
        
        // 3. 修复：名称汉化 (调用 MarketItem 的 Component 渲染逻辑)
        Component localizedName = item.getDisplayNameComponent(plugin.getMarketManager().getCraftEngineHook())
                .decoration(TextDecoration.ITALIC, false);
        
        meta.displayName(Component.text()
                .append(Component.text("✨ ", NamedTextColor.AQUA))
                .append(localizedName)
                .build());

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
}