package com.kyochigo.economy.utils;

import com.kyochigo.economy.KyochigoPlugin;
import com.kyochigo.economy.gui.TransactionDialog;
import com.kyochigo.economy.model.MarketItem;
import de.oliver.fancynpcs.api.FancyNpcsPlugin;
import de.oliver.fancynpcs.api.actions.NpcAction;
import de.oliver.fancynpcs.api.actions.executor.ActionExecutionContext;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;

/**
 * FancyNpcs 动作挂钩 (v5.0 交互升级版)
 * 逻辑变更：
 * 1. 空手点击 NPC -> 打开该分类的交易入口菜单 (TransactionDialog.openEntryMenu)。
 * 2. 手持物品点击 NPC -> 尝试直接出售该物品 (保持原有快捷出售逻辑)。
 */
public class FancyNpcsHook extends NpcAction {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String ACTION_ID = "kyochigo_trade";
    
    // 路由匹配集合
    private static final Set<String> ANALYZER_TRIGGERS = Set.of("analyzer", "看板", "market");

    // 静态消息组件
    private static final Component ERR_UNREGISTERED = MM.deserialize("<red>错误：手中物品不属于任何已登记的贸易物资。</red>");
    private static final String ERR_CATEGORY = "<red>错误：本专员不负责回收 [<white><cat></white>] 类物资。</red>";

    public FancyNpcsHook() {
        super(ACTION_ID, true);
    }

    @Override
    public void execute(@NotNull ActionExecutionContext context, @Nullable String value) {
        Player player = context.getPlayer();
        if (player == null || value == null || value.isEmpty()) return;

        KyochigoPlugin plugin = KyochigoPlugin.getInstance();

        // 1. 特殊路由：如果 value 是 "analyzer" 等，打开全分类看板
        if (ANALYZER_TRIGGERS.contains(value.toLowerCase())) {
            plugin.getMarketManager().fetchMarketPricesAndOpenGui(player, true);
            return;
        }

        // 2. 常规逻辑：处理特定分类的 NPC 交互 (value 即为 categoryId，如 "ores")
        processPhysicalTrade(plugin, player, value);
    }

    private void processPhysicalTrade(KyochigoPlugin plugin, Player player, String targetCategory) {
        ItemStack hand = player.getInventory().getItemInMainHand();

        // --- 分支 A：空手点击 ---
        // 打开针对该分类的“购买/出售”选择菜单
        if (hand.getType() == Material.AIR) {
            TransactionDialog.openEntryMenu(player, targetCategory);
            return;
        }

        // --- 分支 B：手持物品点击 (保留快捷出售逻辑) ---
        
        // 1. 物品匹配判定
        MarketItem item = plugin.getMarketManager().findMarketItem(hand);
        if (item == null) {
            player.sendMessage(ERR_UNREGISTERED);
            return;
        }

        // 2. 分类准入判定 (防止玩家去矿工NPC卖农作物)
        if (!item.getCategory().equalsIgnoreCase(targetCategory)) {
            sendTemplatedMessage(player, ERR_CATEGORY, Placeholder.parsed("cat", getCategoryDisplayName(targetCategory)));
            return;
        }

        // 3. 执行核心交易 (直接弹出出售确认框)
        // 这里直接调用 TransactionDialog 的桥接方法，或者通过 Manager 调用
        // 为了逻辑统一，这里我们直接请求计算并打开出售确认
        plugin.getTransactionManager().openSellConfirmDialog(player, item, hand.getAmount());
    }

    private void sendTemplatedMessage(Player player, String template, TagResolver... tags) {
        player.sendMessage(MM.deserialize(template, tags));
    }

    private String getCategoryDisplayName(String id) {
        return Map.of(
            "ores", "矿产资源", "food", "烹饪美食", "crops", "农耕作物",
            "animal_husbandry", "畜牧产品", "weapons", "神兵利器", "misc", "综合杂项"
        ).getOrDefault(id.toLowerCase(), id);
    }

    public void register() {
        FancyNpcsPlugin.get().getActionManager().registerAction(this);
        KyochigoPlugin.getInstance().getLogger().info("✅ FancyNpcs 动作 [" + ACTION_ID + "] 已就绪。");
    }
}