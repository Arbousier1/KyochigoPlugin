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
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 商业选货菜单 (v8.5 箱子菜单重构版)
 * 职责：展示特定分类的商品，处理左右键快捷交易入口。
 */
public class TradeSelectorMenu implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String INV_TITLE_PREFIX = "§8商业柜台 » §b";
    private static final NamespacedKey MENU_KEY = new NamespacedKey("kyochigo", "trade_menu");
    
    // 布局常量
    private static final int START_SLOT = 10;
    private static final int BORDER_OFFSET = 8;
    private static final int BOTTOM_ROW_OFFSET = 9;

    // 缓存静态背景填充
    private static final ItemStack FILLER_ITEM = createFiller();

    /**
     * 打开商品选择箱子菜单
     * @param player 玩家
     * @param categoryId 柜台分类ID
     */
    public static void openItemSelect(Player player, String categoryId) {
        KyochigoPlugin plugin = KyochigoPlugin.getInstance();
        String categoryName = getCategoryNameRaw(plugin, categoryId).replaceAll("<[^>]*>", "");
        
        List<MarketItem> items = plugin.getMarketManager().getAllItems().stream()
                .filter(i -> i.getCategory().equalsIgnoreCase(categoryId))
                .collect(Collectors.toList());

        // 计算动态行数 (3-6行)
        int rows = Math.max(3, Math.min(6, (items.size() + 7) / 9 + 2));
        int size = rows * 9;
        
        Inventory inv = Bukkit.createInventory(null, size, Component.text(INV_TITLE_PREFIX + categoryName));

        // 1. 填充灰色背景
        for (int i = 0; i < size; i++) inv.setItem(i, FILLER_ITEM);

        // 2. 布局商品图标
        int slot = START_SLOT;
        for (MarketItem item : items) {
            if (slot % 9 == BORDER_OFFSET) slot += 2; // 避开两侧边缘
            if (slot >= size - BOTTOM_ROW_OFFSET) break; // 避开底行
            
            inv.setItem(slot, buildMarketItemStack(item, plugin));
            slot++;
        }

        // 3. 底部功能按钮
        inv.setItem(size - 9, buildUtilityButton(Material.ARROW, "<red>« 返回主柜台", "back"));
        inv.setItem(size - 1, buildUtilityButton(Material.BARRIER, "<gray>关闭菜单", "close"));

        player.openInventory(inv);
    }

    private static ItemStack buildMarketItemStack(MarketItem item, KyochigoPlugin plugin) {
        ItemStack stack = plugin.getMarketManager().getItemIcon(item);
        ItemMeta meta = stack.getItemMeta();

        // 构建详细的行情描述
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(MM.deserialize("<gray>当前行情单价:</gray>"));
        lore.add(MM.deserialize("<white> 采购支付: <green>" + String.format("%.2f", item.getBuyPrice()) + " ⛁</green>"));
        lore.add(MM.deserialize("<white> 出售收益: <gold>" + String.format("%.2f", item.getSellPrice()) + " ⛁</gold>"));
        lore.add(Component.empty());
        lore.add(MM.deserialize("<aqua><b>[ 左键点击 ]</b> <white>开始 <b>购买</b></white>"));
        lore.add(MM.deserialize("<yellow><b>[ 右键点击 ]</b> <white>开始 <b>出售</b></white>"));
        lore.add(Component.empty());
        lore.add(MM.deserialize("<dark_gray>配置编码: " + item.getConfigKey() + "</dark_gray>"));

        meta.lore(lore);
        // 使用 PDC 标记该物品为可交互商品
        meta.getPersistentDataContainer().set(MENU_KEY, PersistentDataType.STRING, "product");
        stack.setItemMeta(meta);
        return stack;
    }

    /**
     * 核心事件处理器：监听箱子菜单内的点击
     */
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        // 通过 PDC 标识判定是否为本插件的菜单，杜绝标题党匹配失败
        String action = clicked.getItemMeta().getPersistentDataContainer().get(MENU_KEY, PersistentDataType.STRING);
        if (action == null) return;

        event.setCancelled(true); // 禁止取走物品
        KyochigoPlugin plugin = KyochigoPlugin.getInstance();

        switch (action) {
            case "back" -> TransactionDialog.openEntryMenu(player, null);
            case "close" -> player.closeInventory();
            case "product" -> {
                // 根据点击项寻找对应的 MarketItem 模型
                MarketItem item = plugin.getMarketManager().findMarketItem(clicked);
                if (item != null) {
                    // 左键进入买入流程，右键进入卖出流程
                    if (event.getClick() == ClickType.LEFT) {
                        TransactionDialog.openActionMenu(player, item, true);
                    } else if (event.getClick() == ClickType.RIGHT) {
                        TransactionDialog.openActionMenu(player, item, false);
                    }
                }
            }
        }
    }

    // --- 内部辅助方法 ---

    private static ItemStack buildUtilityButton(Material mat, String name, String data) {
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(MM.deserialize(name).decoration(TextDecoration.ITALIC, false));
        meta.getPersistentDataContainer().set(MENU_KEY, PersistentDataType.STRING, data);
        stack.setItemMeta(meta);
        return stack;
    }

    private static ItemStack createFiller() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            item.setItemMeta(meta);
        }
        return item;
    }

    private static String getCategoryNameRaw(KyochigoPlugin plugin, String categoryKey) {
        return plugin.getConfiguration().getRaw().getString("categories." + categoryKey + ".name", categoryKey);
    }
}