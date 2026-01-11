package com.kyochigo.economy.utils;

import com.kyochigo.economy.KyochigoPlugin;
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
 * FancyNpcs 动作挂钩 (v4.0 极致精简版)
 * 优化点：$O(1)$ 路由查找、逻辑扁平化、全模板化消息渲染。
 */
public class FancyNpcsHook extends NpcAction {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String ACTION_ID = "kyochigo_trade";
    
    // 使用 Set 进行 O(1) 复杂度的路由匹配
    private static final Set<String> ANALYZER_TRIGGERS = Set.of("analyzer", "看板");

    // 静态消息组件与模板
    private static final Component ERR_UNREGISTERED = MM.deserialize("<red>错误：手中物品不属于任何已登记的贸易物资。</red>");
    private static final String MSG_GUIDE = "<dark_gray>─</dark_gray> <gray>当前柜台：<white>[<display>]</white></gray><newline><gray>提示：请手持对应的物资点击我进行结算。</gray>";
    private static final String ERR_CATEGORY = "<red>错误：本专员不负责回收 [<white><cat></white>] 类物资。</red>";

    public FancyNpcsHook() {
        super(ACTION_ID, true);
    }

    @Override
    public void execute(@NotNull ActionExecutionContext context, @Nullable String value) {
        Player player = context.getPlayer();
        if (player == null || value == null || value.isEmpty()) return;

        KyochigoPlugin plugin = KyochigoPlugin.getInstance();

        // 1. 路由分发：使用 Set 包含判定，逻辑更纯粹
        if (ANALYZER_TRIGGERS.contains(value.toLowerCase())) {
            plugin.getMarketManager().fetchMarketPricesAndOpenGui(player, true);
            return;
        }

        processPhysicalTrade(plugin, player, value);
    }

    private void processPhysicalTrade(KyochigoPlugin plugin, Player player, String targetCategory) {
        ItemStack hand = player.getInventory().getItemInMainHand();

        // 2a. 状态判定预检 (卫语句)
        if (hand.getType() == Material.AIR) {
            sendTemplatedMessage(player, MSG_GUIDE, Placeholder.parsed("display", getCategoryDisplayName(targetCategory)));
            return;
        }

        // 2b. 物品匹配判定
        MarketItem item = plugin.getMarketManager().findMarketItem(hand);
        if (item == null) {
            player.sendMessage(ERR_UNREGISTERED);
            return;
        }

        // 2c. 分类准入判定 (逻辑扁平化)
        if (!item.getCategory().equalsIgnoreCase(targetCategory)) {
            sendTemplatedMessage(player, ERR_CATEGORY, Placeholder.parsed("cat", item.getCategory()));
            return;
        }

        // 3. 执行核心交易
        plugin.getTransactionManager().openSellConfirmDialog(player, item, hand.getAmount());
    }

    private void sendTemplatedMessage(Player player, String template, TagResolver... tags) {
        player.sendMessage(MM.deserialize(template, tags));
    }

    private String getCategoryDisplayName(String id) {
        // 建议：此处未来可改为从 plugin.getConfigManager() 获取 Map
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