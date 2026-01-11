package com.kyochigo.economy.commands;

import com.kyochigo.economy.KyochigoPlugin;
import com.kyochigo.economy.managers.InventoryManager;
import com.kyochigo.economy.managers.MarketManager;
import com.kyochigo.economy.managers.TransactionManager;
import com.kyochigo.economy.model.MarketItem;
import com.kyochigo.economy.utils.CraftEngineHook;
import de.oliver.fancynpcs.api.FancyNpcsPlugin;
import de.oliver.fancynpcs.api.Npc;
import de.oliver.fancynpcs.api.NpcData;
import de.oliver.fancynpcs.api.actions.ActionTrigger;
import de.oliver.fancynpcs.api.actions.NpcAction;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 插件主指令处理器 (v3.2 精简版)
 * 职责：处理指令交互，物理召唤并自动绑定交易 Action。
 */
public class KyochigoCommand implements CommandExecutor, TabCompleter {
    private final KyochigoPlugin plugin;
    private final MarketManager marketManager;
    private final InventoryManager inventoryManager;

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private static final List<String> CATEGORIES = Arrays.asList(
            "ores", "food", "crops", "animal_husbandry", "weapons", "misc"
    );

    public KyochigoCommand(KyochigoPlugin plugin,
                           MarketManager marketManager,
                           TransactionManager transactionManager, // 保留参数以兼容主类初始化，但不存为变量
                           InventoryManager inventoryManager,
                           CraftEngineHook hook) {
        this.plugin = plugin;
        this.marketManager = marketManager;
        this.inventoryManager = inventoryManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (sender.hasPermission("kyochigo.admin")) {
                plugin.reloadPlugin();
                sender.sendMessage(MM.deserialize("<dark_gray>[</dark_gray><aqua>Kyochigo</aqua><dark_gray>]</dark_gray> <green>配置已重载，后端协议握手成功。</green>"));
            } else {
                sender.sendMessage(MM.deserialize("<red>错误：权限不足。</red>"));
            }
            return true;
        }

        if (!(sender instanceof Player p)) {
            sender.sendMessage("§c控制台无法执行此操作。");
            return true;
        }

        if (args.length == 0) {
            sendHelp(p);
            return true;
        }

        String subCommand = args[0].toLowerCase();
        switch (subCommand) {
            case "market" -> handleMarket(p);
            case "spawn" -> handleSpawn(p, args);
            case "clear" -> handleClear(p, label, args);
            default -> sendHelp(p);
        }

        return true;
    }

    private void handleSpawn(Player p, String[] args) {
        if (!p.hasPermission("kyochigo.admin")) {
            p.sendMessage(MM.deserialize("<red>权限不足。</red>"));
            return;
        }

        if (args.length < 2) {
            p.sendMessage(MM.deserialize("<red>用法: /" + labelOrPluginName() + " spawn <分类></red>"));
            return;
        }

        String category = args[1].toLowerCase();
        if (!CATEGORIES.contains(category)) {
            p.sendMessage(MM.deserialize("<red>无效分类。可选: " + CATEGORIES + "</red>"));
            return;
        }

        // 定义 NPC 视觉属性
        String displayName = switch (category) {
            case "ores" -> "<gradient:#00FFFF:#0080FF><b>矿产资源专员</b></gradient>";
            case "food" -> "<gradient:#FFA500:#FF4500><b>烹饪物资商贩</b></gradient>";
            case "crops" -> "<gradient:#55FF55:#FFD700><b>农耕作物农夫</b></gradient>";
            case "animal_husbandry" -> "<gradient:#FFB6C1:#FF69B4><b>畜牧产品专员</b></gradient>";
            case "weapons" -> "<gradient:#FF3333:#8B0000><b>神兵利器铁匠</b></gradient>";
            default -> "<gradient:#E0E0E0:#808080><b>综合杂项收购</b></gradient>";
        };

        String skinName = switch (category) {
            case "ores" -> "MHF_Golem";
            case "food" -> "MHF_Cake";
            case "crops" -> "MHF_Villager";
            case "animal_husbandry" -> "MHF_Cow";
            case "weapons" -> "MHF_Enderman";
            default -> "MHF_Chest";
        };

        Location loc = p.getLocation();
        String npcId = "kyochigo_" + category + "_" + UUID.randomUUID().toString().substring(0, 5);

        // 1. 构建 NpcData
        NpcData data = new NpcData(npcId, p.getUniqueId(), loc);
        data.setDisplayName(displayName);
        data.setSkin(skinName);
        data.setTurnToPlayer(true);

        // 2. 注入 Action 协议 (修复 Order 参数)
        NpcAction myAction = FancyNpcsPlugin.get().getActionManager().getActionByName("kyochigo_trade");
        if (myAction != null) {
            NpcAction.NpcActionData actionData = new NpcAction.NpcActionData(1, myAction, category);
            List<NpcAction.NpcActionData> actions = data.getActions(ActionTrigger.RIGHT_CLICK);
            actions.add(actionData);
            data.setActions(ActionTrigger.RIGHT_CLICK, actions);
        }

        // 3. 激活 NPC
        Npc npc = FancyNpcsPlugin.get().getNpcAdapter().apply(data);
        FancyNpcsPlugin.get().getNpcManager().registerNpc(npc);
        npc.create();
        npc.spawnForAll();

        p.sendMessage(MM.deserialize("<dark_gray>[</dark_gray><aqua>Kyochigo</aqua><dark_gray>]</dark_gray> <green>已召唤 </green>" + displayName + " <gray>(协议已绑定)</gray>"));
    }

    private void handleMarket(Player p) {
        if (marketManager.getAllItems().isEmpty()) {
            p.sendMessage(MM.deserialize("<red>错误：市场行情中心尚未准备就绪。</red>"));
            return;
        }
        boolean viewOnly = !p.hasPermission("kyochigo.admin");
        marketManager.fetchMarketPricesAndOpenGui(p, viewOnly);
    }

    private void handleClear(Player p, String label, String[] args) {
        if (!p.hasPermission("kyochigo.admin") || args.length < 4) return;
        Player target = Bukkit.getPlayer(args[1]);
        MarketItem item = marketManager.findMarketItemByKey(args[2]);
        int amount = tryParseInt(args[3]);

        if (target != null && item != null && amount > 0) {
            if (inventoryManager.removeItems(target, item, amount)) {
                p.sendMessage(MM.deserialize("<green>操作成功：已强制清退目标资产。</green>"));
            }
        }
    }

    private void sendHelp(Player p) {
        p.sendMessage(MM.deserialize("<dark_gray>──────────</dark_gray> <aqua><b>Kyochigo Economy</b></aqua> <dark_gray>──────────</dark_gray>"));
        p.sendMessage(MM.deserialize("<gray>/market</gray> <dark_gray>─</dark_gray> <white>访问行情看板</white>"));
        if (p.hasPermission("kyochigo.admin")) {
            p.sendMessage(MM.deserialize("<gray>/" + labelOrPluginName() + " spawn <分类></gray> <dark_gray>─</dark_gray> <white>召唤贸易专员</white>"));
            p.sendMessage(MM.deserialize("<gray>/" + labelOrPluginName() + " reload</gray> <dark_gray>─</dark_gray> <white>强制同步数据</white>"));
        }
        p.sendMessage(MM.deserialize("<dark_gray>───────────────────────────────────</dark_gray>"));
    }

    private int tryParseInt(String val) {
        try { return Integer.parseInt(val); } catch (Exception e) { return -1; }
    }

    private String labelOrPluginName() {
        return plugin.getName().toLowerCase();
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("market"));
            if (sender.hasPermission("kyochigo.admin")) {
                subs.addAll(Arrays.asList("spawn", "reload", "clear"));
            }
            return filter(subs, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("spawn") && sender.hasPermission("kyochigo.admin")) {
            return filter(CATEGORIES, args[1]);
        }
        return new ArrayList<>();
    }

    private List<String> filter(List<String> list, String input) {
        return list.stream()
                .filter(s -> s.toLowerCase().startsWith(input.toLowerCase()))
                .collect(Collectors.toList());
    }
}