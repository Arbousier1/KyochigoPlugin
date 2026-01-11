package com.kyochigo.economy.managers;

import com.kyochigo.economy.KyochigoPlugin;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Objects;

/**
 * 核心配置管理器 (v3.0 模块化版)
 * 职责：封装 config.yml 读写逻辑，采用配置对象模式提高类型安全性。
 */
public class ConfigManager {

    private final KyochigoPlugin plugin;
    private FileConfiguration config;
    private ConfigData configData;

    public ConfigManager(KyochigoPlugin plugin) {
        this.plugin = plugin;
        this.reload();
    }

    public void reload() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        this.config = plugin.getConfig();
        
        // 核心优化：一次性将配置映射到不可变对象
        this.configData = new ConfigData(config);

        if (configData.debug) {
            plugin.getLogger().info("ConfigManager: 配置已重载，当前后端地址: " + configData.backendUrl);
        }
    }

    /**
     * 判断玩家是否处于交易所区域
     */
    public boolean isAtExchange(Player player) {
        return configData.exchange.isAtExchange(player);
    }

    // --- [ 动态物品参数查询 ] ---

    public int getItemDailyLimit(String itemKey) {
        return config.getInt("items." + itemKey + ".daily_limit", 0);
    }

    public double getItemDouble(String itemKey, String path, double defaultValue) {
        return config.getDouble("items." + itemKey + "." + path, defaultValue);
    }

    public ConfigurationSection getItemsSection() {
        return config.getConfigurationSection("items");
    }

    public void save() { plugin.saveConfig(); }

    // --- [ 委托给内部配置对象 ] ---

    public long getCooldownMs() { return configData.cooldownMs; }
    public boolean isDebug() { return configData.debug; }
    public List<String> getEnabledWorlds() { return configData.enabledWorlds; }
    public String getBackendUrl() { return configData.backendUrl; }
    public int getConnectTimeout() { return configData.connectTimeout; }
    public int getRequestTimeout() { return configData.requestTimeout; }
    public String getCurrencySymbol() { return configData.currencySymbol; }
    public FileConfiguration getRaw() { return config; }

    /**
     * 配置数据容器 (Immutable-like Data Object)
     */
    private static class ConfigData {
        final long cooldownMs;
        final boolean debug;
        final List<String> enabledWorlds;
        final String backendUrl;
        final int connectTimeout;
        final int requestTimeout;
        final String currencySymbol;
        final ExchangeLocation exchange;

        ConfigData(FileConfiguration config) {
            // 系统设置
            this.cooldownMs = config.getLong("settings.rate-limit-ms", 500L);
            this.debug = config.getBoolean("settings.debug", false);
            this.enabledWorlds = Objects.requireNonNullElse(
                    config.getStringList("settings.enabled-worlds"), List.of());

            // 后端设置与 URL 尾部斜杠修正
            String rawUrl = config.getString("backend.url", "http://127.0.0.1:9981");
            this.backendUrl = rawUrl.endsWith("/") ? rawUrl.substring(0, rawUrl.length() - 1) : rawUrl;
            this.connectTimeout = config.getInt("backend.connect-timeout", 3);
            this.requestTimeout = config.getInt("backend.request-timeout", 10);

            // 显示设置
            this.currencySymbol = config.getString("display.currency-symbol", "⛁");

            // 模块化子配置：交易所
            this.exchange = new ExchangeLocation(config.getConfigurationSection("exchange"));
        }
    }

    /**
     * 交易所坐标逻辑封装
     */
    private static class ExchangeLocation {
        private final String world;
        private final double x, y, z, radius;

        ExchangeLocation(ConfigurationSection section) {
            if (section != null) {
                this.world = section.getString("world", "world");
                this.x = section.getDouble("x", 0.0);
                this.y = section.getDouble("y", 64.0);
                this.z = section.getDouble("z", 0.0);
                this.radius = section.getDouble("radius", 8.0);
            } else {
                // 默认降级方案
                this.world = "world"; this.x = 0; this.y = 64; this.z = 0; this.radius = 8.0;
            }
        }

        boolean isAtExchange(Player player) {
            if (!player.getWorld().getName().equalsIgnoreCase(world)) return false;
            Location exLoc = new Location(player.getWorld(), x, y, z);
            // distanceSquared 比 distance 性能更高，但在 radius 较小时可忽略
            return player.getLocation().distance(exLoc) <= radius;
        }
    }
}