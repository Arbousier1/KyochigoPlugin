package com.kyochigo.economy.managers;

import com.kyochigo.economy.model.MarketItem;
import com.kyochigo.economy.utils.CraftEngineHook;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;

/**
 * 背包管理器 (v3.1 修复版)
 * <p>
 * 修复：
 * 1. hasSpaceForItem 不再产生副作用（不再误发物品）。
 * 2. giveItems 实现了标准的“背包满时掉落”逻辑。
 * 3. 统一了匹配逻辑，防止 NBT 不一致导致的扣除失败。
 */
public class InventoryManager {

    private final CraftEngineHook craftEngineHook;

    public InventoryManager(@Nullable CraftEngineHook craftEngineHook) {
        this.craftEngineHook = craftEngineHook;
    }

    /**
     * 统计物品数量
     * 保持手动循环，因为 MarketItem 可能有特殊的匹配逻辑（如忽略耐久、自定义ModelData）
     */
    public int countItems(@NotNull Player player, @NotNull MarketItem item) {
        ItemStack[] allItems = player.getInventory().getContents(); // 包含快捷栏、背包
        int count = 0;

        for (ItemStack stack : allItems) {
            if (stack != null && !stack.getType().isAir() && item.matches(stack, craftEngineHook)) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    /**
     * 验证数量
     * [修改] 弃用 containsAtLeast，改用 countItems >= needed，以确保和 matches 逻辑一致
     */
    public boolean hasEnoughItems(@NotNull Player player, @NotNull MarketItem item, int needed) {
        if (needed <= 0) return true;
        return countItems(player, item) >= needed;
    }

    /**
     * [核心修复] 检查空间 (纯数学计算，无副作用)
     * 原理：计算背包剩余总容积 vs 需要容纳的数量
     */
    public boolean hasSpaceForItem(@NotNull Player player, @NotNull MarketItem item, int amountToGive) {
        if (amountToGive <= 0) return true;

        PlayerInventory inv = player.getInventory();
        ItemStack template = item.getIcon(craftEngineHook);
        int maxStack = template.getMaxStackSize();
        int freeSpace = 0;

        // 遍历主要存储区域 (0-35)
        for (ItemStack slotItem : inv.getStorageContents()) {
            if (slotItem == null || slotItem.getType() == Material.AIR) {
                // 空格子提供最大堆叠数的空间
                freeSpace += maxStack;
            } else if (slotItem.isSimilar(template)) {
                // 相同物品，提供剩余堆叠空间
                freeSpace += Math.max(0, maxStack - slotItem.getAmount());
            }
            
            // 优化：一旦空间足够，立即返回
            if (freeSpace >= amountToGive) return true;
        }

        return freeSpace >= amountToGive;
    }

    /**
     * 安全扣除物品
     * [修改] 手动实现扣除，因为 removeItemAnySlot 是严格匹配，可能无法处理 MarketItem 的模糊匹配需求
     */
    public boolean removeItems(@NotNull Player player, @NotNull MarketItem item, int amountToRemove) {
        if (amountToRemove <= 0) return true;
        if (!hasEnoughItems(player, item, amountToRemove)) return false;

        PlayerInventory inv = player.getInventory();
        ItemStack[] contents = inv.getContents();
        int leftToRemove = amountToRemove;

        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType().isAir()) continue;

            // 使用统一的 matches 逻辑
            if (item.matches(stack, craftEngineHook)) {
                int amount = stack.getAmount();
                if (amount <= leftToRemove) {
                    // 如果这堆不够扣或刚好，直接清除该格
                    inv.setItem(i, null);
                    leftToRemove -= amount;
                } else {
                    // 如果这堆够扣，减少数量
                    stack.setAmount(amount - leftToRemove);
                    leftToRemove = 0;
                }

                if (leftToRemove <= 0) break;
            }
        }
        
        // 更新背包状态（防止客户端显示不同步）
        // player.updateInventory(); // 高版本通常不需要，除非出现灵异现象
        return leftToRemove == 0;
    }

    /**
     * [核心修复] 发放物品
     * 逻辑：尝试放入背包 -> 放不下的丢在脚下
     */
    public void giveItems(@NotNull Player player, @NotNull MarketItem item, int amount) {
        if (amount <= 0) return;

        ItemStack toGive = item.getIcon(craftEngineHook);
        toGive.setAmount(amount);

        // 1. 尝试放入背包
        // addItem 返回无法放入的剩余物品 Map
        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(toGive);

        // 
        // 2. 如果有剩余（背包满了），在玩家位置生成掉落物
        if (!leftover.isEmpty()) {
            for (ItemStack surplus : leftover.values()) {
                player.getWorld().dropItem(player.getLocation(), surplus);
            }
            player.sendMessage("§e[提示] §f背包已满，部分物品已掉落在脚下。");
        }
    }
}