package com.kyochigo.economy.utils;

import com.kyochigo.economy.KyochigoPlugin;
import com.kyochigo.economy.gui.TradeSelectorMenu;
import com.kyochigo.economy.model.MarketItem;
import de.oliver.fancynpcs.api.FancyNpcsPlugin;
import de.oliver.fancynpcs.api.actions.NpcAction;
import de.oliver.fancynpcs.api.actions.executor.ActionExecutionContext;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;

/**
 * FancyNpcs 动作挂钩 (v5.6 同步修复版)
 * 职责：连接物理 NPC 与插件贸易系统，已修复异步线程打开 GUI 导致的 IllegalStateException。
 */
public class FancyNpcsHook extends NpcAction {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String ACTION_ID = "kyochigo_trade";
    
    // 特殊功能触发词
    private static final Set<String> ANALYZER_TRIGGERS = Set.of("analyzer", "看板", "market");

    // 预定义消息
    private static final Component ERR_UNREGISTERED = MM.deserialize("<red>错误：您手中的物品不在贸易名录中。</red>");
    private static final String ERR_CATEGORY = "<red>错误：本专员不负责回收 [<white><cat></white>] 类物资。</red>";

    public FancyNpcsHook() {
        super(ACTION_ID, true);
    }

    @Override
    public void execute(@NotNull ActionExecutionContext context, @Nullable String value) {
        Player player = context.getPlayer();
        if (player == null || value == null || value.isEmpty()) return;

        KyochigoPlugin plugin = KyochigoPlugin.getInstance();

        // 使用调度器切换回主线程执行，防止报错
        Bukkit.getScheduler().runTask(plugin, () -> {
            // 1. 特殊逻辑：打开全分类看板
            if (ANALYZER_TRIGGERS.contains(value.toLowerCase())) {
                plugin.getMarketManager().fetchMarketPricesAndOpenGui(player, true);
                return;
            }

            // 2. 核心逻辑：处理特定柜台交互 (value 为 categoryId)
            processPhysicalTrade(plugin, player, value);
        });
    }

    /**
     * 处理 NPC 物理交互分发
     */
    private void processPhysicalTrade(KyochigoPlugin plugin, Player player, String targetCategory) {
        ItemStack hand = player.getInventory().getItemInMainHand();

        // --- 分支 A：空手点击 NPC ---
        // 动作：直接打开该分类的箱子选货菜单
        if (hand.getType() == Material.AIR) {
            TradeSelectorMenu.openItemSelect(player, targetCategory);
            return;
        }

        // --- 分支 B：手持物品点击 NPC ---
        // 动作：尝试直接出售该物品 (快捷贸易)
        
        // 1. 查找物品模型
        MarketItem item = plugin.getMarketManager().findMarketItem(hand);
        if (item == null) {
            player.sendMessage(ERR_UNREGISTERED);
            return;
        }

        // 2. 准入判定：检查物品分类是否与 NPC 职能匹配
        if (!item.getCategory().equalsIgnoreCase(targetCategory)) {
            sendTemplatedMessage(player, ERR_CATEGORY, Placeholder.parsed("cat", getCategoryDisplayName(targetCategory)));
            return;
        }

        // 3. 执行快捷出售：先请求计价，计价成功后会自动弹出确认对话框
        plugin.getTransactionManager().openSellConfirmDialog(player, item, hand.getAmount());
    }

    /**
     * 带占位符的消息发送辅助
     */
    private void sendTemplatedMessage(Player player, String template, TagResolver... tags) {
        player.sendMessage(MM.deserialize(template, tags));
    }

    /**
     * 获取分类的友好中文显示名
     */
    private String getCategoryDisplayName(String id) {
        return Map.of(
            "ores", "矿产资源", 
            "food", "烹饪物资", 
            "crops", "农耕作物",
            "animal_husbandry", "畜牧产品", 
            "weapons", "神兵利器", 
            "misc", "综合杂项"
        ).getOrDefault(id.toLowerCase(), id);
    }

    /**
     * 注册动作到 FancyNpcs 核心
     */
    public void register() {
        FancyNpcsPlugin.get().getActionManager().registerAction(this);
        KyochigoPlugin.getInstance().getLogger().info("✅ FancyNpcs 贸易协议 [" + ACTION_ID + "] 已注册。");
    }
}