package com.kyochigo.economy.utils;

import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.core.item.ItemManager;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;

/**
 * CraftEngine 深度挂钩工具 (v4.0 极致性能版)
 * 优化：使用 computeIfAbsent 简化缓存、LRU 自动内存回收、强化 1.20.5+ 组件识别。
 */
public class CraftEngineHook {

    private static final String PLUGIN_NAME = "CraftEngine";
    private static final String LOG_PREFIX = "[KyochigoEconomy] ";
    private static final int MAX_CACHE_SIZE = 100;

    private final ItemManager<ItemStack> itemManager;
    private final boolean enabled;

    // LRU 缓存：自动清理最久未使用的模板
    private final Map<String, ItemStack> itemCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ItemStack> eldest) {
            return size() > MAX_CACHE_SIZE;
        }
    };

    public CraftEngineHook() {
        // 使用 isPluginEnabled 确保插件不仅存在且已加载完毕
        if (Bukkit.getPluginManager().isPluginEnabled(PLUGIN_NAME)) {
            ItemManager<ItemStack> manager = null;
            try {
                manager = BukkitItemManager.instance();
                Bukkit.getLogger().info(LOG_PREFIX + "成功连接 CraftEngine (Bukkit 强类型管理器)");
            } catch (Throwable t) {
                Bukkit.getLogger().log(Level.WARNING, LOG_PREFIX + "获取 CraftEngine 实例时发生错误", t);
            }
            this.itemManager = manager;
            this.enabled = (manager != null);
        } else {
            this.itemManager = null;
            this.enabled = false;
        }
    }

    /**
     * 获取自定义物品实例
     * 使用 computeIfAbsent 实现线程安全（在 Bukkit 主线程下）的单次构建逻辑
     */
    @Nullable
    public ItemStack getItem(@Nullable String id) {
        if (!enabled || id == null) return null;

        // 利用 computeIfAbsent 简化 "检查-构建-存入" 的三步操作
        ItemStack template = itemCache.computeIfAbsent(id, keyStr -> {
            try {
                return itemManager.buildItemStack(Key.of(keyStr), null);
            } catch (Exception e) {
                Bukkit.getLogger().log(Level.WARNING, LOG_PREFIX + "无法构建物品 [" + keyStr + "]", e);
                return null;
            }
        });

        return template != null ? template.clone() : null;
    }

    /**
     * 识别物品指纹 (1.20.5+ 推荐方式)
     */
    public boolean isCraftEngineItem(@Nullable ItemStack item, @Nullable String targetId) {
        if (!enabled || item == null || item.getType().isAir() || targetId == null) {
            return false;
        }

        return itemManager.wrap(item)
                .customId()
                .map(key -> key.toString().equalsIgnoreCase(targetId))
                .orElse(false);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void clearCache() {
        itemCache.clear();
    }
}