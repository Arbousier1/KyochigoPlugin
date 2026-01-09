package com.kyochigo.economy.utils;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;

// [Fix 1] 优先导入 CraftEngine 的核心类
import net.momirealms.craftengine.core.plugin.CraftEngine;
import net.momirealms.craftengine.core.item.ItemManager;
import net.momirealms.craftengine.core.util.Key; // 导入 CraftEngine Key

import java.lang.reflect.Method;

public class CraftEngineHook {

    @SuppressWarnings("rawtypes")
    private ItemManager itemManager;
    private boolean enabled = false;

    public CraftEngineHook() {
        if (Bukkit.getPluginManager().getPlugin("CraftEngine") == null) {
            return;
        }

        try {
            RegisteredServiceProvider<CraftEngine> provider = Bukkit.getServicesManager().getRegistration(CraftEngine.class);
            if (provider != null) {
                CraftEngine api = provider.getProvider();
                this.itemManager = api.itemManager();
                this.enabled = true;
                Bukkit.getLogger().info("[KyochigoEconomy] Hooked into CraftEngine successfully!");
            }
        } catch (Exception e) {
            Bukkit.getLogger().warning("[KyochigoEconomy] Error hooking CraftEngine: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public ItemStack getItem(String id) {
        if (!enabled || itemManager == null) return null;

        try {
            // 1. 创建 Adventure Key (使用全限定名，避免导入冲突)
            net.kyori.adventure.key.Key adventureKey = net.kyori.adventure.key.Key.key(id);

            // 2. [关键修复] 直接创建 CraftEngine Key，绕过 KeyUtils
            // 既然 Key 是一个 Record，我们可以直接构造它，这比依赖 KeyUtils 更稳定
            // Key(String namespace, String key)
            Key internalKey = new Key(adventureKey.namespace(), adventureKey.value());
            
            // 3. 调用 createWrappedItem
            // 显式转型 null 为 Player 接口
            Object result = itemManager.createWrappedItem(
                internalKey, 
                (net.momirealms.craftengine.core.entity.player.Player) null 
            );

            if (result == null) return null;

            // 4. 提取 Bukkit ItemStack
            if (result instanceof ItemStack) {
                return (ItemStack) result;
            }

            // 反射调用 getItemStack()
            Method getItemStackMethod = result.getClass().getMethod("getItemStack");
            Object itemStackObj = getItemStackMethod.invoke(result);

            if (itemStackObj instanceof ItemStack) {
                return (ItemStack) itemStackObj;
            }

        } catch (Exception e) {
            // 忽略错误
        }
        return null;
    }

    public boolean isCraftEngineItem(ItemStack handItem, String targetId) {
        if (!enabled || handItem == null) return false;

        ItemStack template = getItem(targetId);
        if (template == null) return false;

        return template.isSimilar(handItem);
    }
}