package com.kyochigo.economy.gui;

import com.kyochigo.economy.managers.TransactionManager;
import com.kyochigo.economy.model.MarketItem;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class TransactionDialog {

    /**
     * 打开出售确认对话框
     */
    public static void openConfirm(Player player, MarketItem item, int amount, double totalPrice, double unitPrice, TransactionManager manager) {
        // 1. 获取图标
        ItemStack icon = item.getIcon(null);

        // 2. 解析名称 (处理 MiniMessage)
        Component nameComponent = MiniMessage.miniMessage().deserialize(item.getDisplayName())
                .color(NamedTextColor.WHITE).decorate(TextDecoration.BOLD);

        // 3. 构建主体文本内容
        List<Component> messages = new ArrayList<>();
        messages.add(Component.text("正在出售: ", NamedTextColor.GRAY).append(nameComponent));
        messages.add(Component.empty());
        messages.add(Component.text("数量: ", NamedTextColor.GRAY)
                .append(Component.text(amount, NamedTextColor.GOLD)));
        messages.add(Component.text("单价: ", NamedTextColor.GRAY)
                .append(Component.text(String.format("★%.2f", unitPrice), NamedTextColor.AQUA)));
        messages.add(Component.empty());
        messages.add(Component.text("预计总收益: ", NamedTextColor.GRAY)
                .append(Component.text(String.format("★%.2f", totalPrice), NamedTextColor.GREEN, TextDecoration.BOLD)));

        Component fullBodyText = Component.join(JoinConfiguration.newlines(), messages);

        // 4. 定义按钮动作回调
        DialogActionCallback confirmCallback = (view, audience) -> {
            if (audience instanceof Player p) {
                manager.executeTransaction(p, item, amount);
            }
        };

        DialogActionCallback cancelCallback = (view, audience) -> {
            if (audience instanceof Player p) {
                p.sendMessage(Component.text("已取消交易。", NamedTextColor.RED));
            }
        };

        // 5. 构建 Dialog 实例
        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Component.text("交易确认", NamedTextColor.YELLOW, TextDecoration.BOLD))
                        .canCloseWithEscape(true)
                        .body(List.of(
                                DialogBody.item(icon).build(),
                                DialogBody.plainMessage(fullBodyText)
                        ))
                        .build()
                )
                .type(DialogType.confirmation(
                        ActionButton.builder(Component.text("✔ 确认出售", NamedTextColor.GREEN))
                                .tooltip(Component.text("点击锁定价格并出售"))
                                .action(DialogAction.customClick(confirmCallback, ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(2)).build()))
                                .build(),
                        ActionButton.builder(Component.text("✘ 取消", NamedTextColor.RED))
                                .tooltip(Component.text("放弃交易"))
                                .action(DialogAction.customClick(cancelCallback, ClickCallback.Options.builder().uses(1).build()))
                                .build()
                ))
        );

        player.showDialog(dialog);
    }
}