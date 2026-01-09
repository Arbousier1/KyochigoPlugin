package com.kyochigo.economy.gui;

import com.kyochigo.economy.model.MarketItem;
import com.kyochigo.economy.utils.CraftEngineHook;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.body.ItemDialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class MarketDialog {

    public static void open(Player player, List<MarketItem> items, CraftEngineHook hook) {
        List<DialogBody> bodyParts = new ArrayList<>();

        // 1. 顶部标题
        bodyParts.add(DialogBody.plainMessage(
            Component.text("实时市场行情", NamedTextColor.GOLD, TextDecoration.BOLD)
        ));

        for (MarketItem item : items) {
            ItemStack stack = item.getIcon(hook); 
            
            // 2. 格式化数字：使用 %-7.1f 确保数字部分占据相同的字符宽度（左对齐，占7位）
            // 这能解决因为数字位数不同（如 10.0 和 5000.0）导致的错位
            String sellPriceStr = String.format("%-7.1f", item.getSellPrice());
            String buyPriceStr = String.format("%-7.1f", item.getBuyPrice());

            Component priceRow = Component.text()
                    .append(Component.text("  回收: ", NamedTextColor.RED))
                    .append(Component.text("★" + sellPriceStr, NamedTextColor.GOLD))
                    .append(Component.text("  |  ", NamedTextColor.GRAY)) // 固定间隔
                    .append(Component.text("购买: ", NamedTextColor.GREEN))
                    .append(Component.text("★" + buyPriceStr, NamedTextColor.GOLD))
                    .build();

            // 3. 构建 Body 条目
            // 注意：不传 width 参数。传 width 会导致文字被推到对话框边缘
            ItemDialogBody itemEntry = DialogBody.item(stack)
                    .description(DialogBody.plainMessage(priceRow)) 
                    .showTooltip(true)
                    .build(); 
            
            bodyParts.add(itemEntry);
        }

        // 4. 对话框设置
        DialogBase base = DialogBase.builder(Component.text("价格看板"))
                .body(bodyParts)
                .canCloseWithEscape(true)
                .build();

        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(base)
                .type(DialogType.notice())
        );

        player.showDialog(dialog);
    }
}