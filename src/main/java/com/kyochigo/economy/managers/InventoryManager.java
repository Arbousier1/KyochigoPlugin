package com.kyochigo.economy.managers;

import com.kyochigo.economy.model.MarketItem;
import com.kyochigo.economy.utils.CraftEngineHook;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.logging.Logger;

public class InventoryManager {
    private final CraftEngineHook craftEngineHook;
    private final Logger logger;

    public InventoryManager(@Nullable CraftEngineHook craftEngineHook) {
        this.craftEngineHook = craftEngineHook;
        this.logger = Logger.getLogger("KyochigoEconomy");
    }

    /**
     * 高性能统计：直接访问底层数组，减少方法调用
     */
    public int countItems(@NotNull Player player, @NotNull MarketItem item) {
        PlayerInventory inv = player.getInventory();
        // 仅获取存储栏位（0-35），避开装备栏（36-40）以提升性能并保护玩家装备
        ItemStack[] storage = inv.getStorageContents();
        int count = 0;

        for (ItemStack stack : storage) {
            // 快速失败检查：最廉价的判断放在最前面
            if (stack == null || stack.getAmount() <= 0 || stack.getType() == Material.AIR) {
                continue;
            }
            
            // matches 是最耗时的操作（涉及 NBT 或 Hook 逻辑），放到最后执行
            if (item.matches(stack, craftEngineHook)) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    /**
     * 极速检查：只要达到阈值立即返回，不再遍历剩余格子
     */
    public boolean hasEnoughItems(@NotNull Player player, @NotNull MarketItem item, int needed) {
        if (needed <= 0) return true;
        
        PlayerInventory inv = player.getInventory();
        ItemStack[] storage = inv.getStorageContents();
        int found = 0;

        for (ItemStack stack : storage) {
            if (stack == null || stack.getType() == Material.AIR) continue;

            if (item.matches(stack, craftEngineHook)) {
                found += stack.getAmount();
                if (found >= needed) return true; // 性能优化：达标即刻跳出
            }
        }
        return false;
    }

    /**
     * 原子级安全移除：防止交易过程中的物品变动异常
     */
    public boolean removeItemSafe(@NotNull Player player, @NotNull MarketItem item, int amountToRemove) {
        if (amountToRemove <= 0) return true;

        PlayerInventory inv = player.getInventory();
        ItemStack[] storage = inv.getStorageContents();
        
        // 第一遍扫描：验证当前刻下物品是否依然充足（防止在异步回调期间背包被修改）
        int currentFound = 0;
        for (ItemStack stack : storage) {
            if (stack != null && stack.getType() != Material.AIR && item.matches(stack, craftEngineHook)) {
                currentFound += stack.getAmount();
            }
        }
        if (currentFound < amountToRemove) return false;

        // 第二遍扫描：实际执行移除
        int leftToRemove = amountToRemove;
        for (int i = 0; i < storage.length; i++) {
            ItemStack stack = storage[i];
            if (stack == null || stack.getType() == Material.AIR) continue;

            if (item.matches(stack, craftEngineHook)) {
                int stackAmount = stack.getAmount();
                
                if (stackAmount <= leftToRemove) {
                    leftToRemove -= stackAmount;
                    inv.setItem(i, null); // 清空格子
                } else {
                    stack.setAmount(stackAmount - leftToRemove);
                    inv.setItem(i, stack); // 更新数量
                    leftToRemove = 0;
                }
            }
            if (leftToRemove <= 0) break;
        }

        return leftToRemove == 0;
    }
}