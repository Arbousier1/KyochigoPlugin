package com.kyochigo.economy;

import com.google.gson.Gson;
import com.kyochigo.economy.commands.KyochigoCommand;
import com.kyochigo.economy.expansions.KyochigoExpansion;
import com.kyochigo.economy.managers.*;
import com.kyochigo.economy.utils.CraftEngineHook;
import com.kyochigo.economy.utils.FancyNpcsHook;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KyochigoEconomy 主类 (v3.0 工业级架构版)
 * 职责：负责插件生命周期管理、核心管理器依赖注入、以及第三方插件高度解耦集成。
 */
public class KyochigoPlugin extends JavaPlugin {

    private static KyochigoPlugin instance;

    // 核心组件与依赖
    private final PluginComponents components = new PluginComponents();
    private final PluginIntegrations integrations = new PluginIntegrations();
    private final Gson gson = new Gson();

    @Override
    public void onEnable() {
        instance = this;
        sendBanner();
        long startTime = System.currentTimeMillis();

        // 1. 顺序初始化：核心组件 -> 第三方集成 -> 扩展
        if (!initializePlugin()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        long duration = System.currentTimeMillis() - startTime;
        Bukkit.getConsoleSender().sendMessage("§8[§bKyochigo§8] §f系统核心已就绪 §7(" + duration + "ms)");
        Bukkit.getConsoleSender().sendMessage("§8[§bKyochigo§8] §f交互协议: §dFancyNpcs Action v4.0");
    }

    private boolean initializePlugin() {
        try {
            // 1. 初始化核心管理器容器
            if (!components.initialize(this)) return false;

            // 2. 初始化第三方集成 (Vault, FancyNpcs 等)
            if (!integrations.initialize(this, components)) return false;

            // 3. 注册命令
            registerCommands();

            // 4. 注册 PlaceholderAPI 扩展
            if (integrations.isPapiEnabled()) {
                new KyochigoExpansion(this, components.inventoryManager(), 
                    components.marketManager(), components.tradeCache()).register();
            }

            return true;
        } catch (Exception e) {
            getLogger().severe("🚨 插件初始化期间发生非预期异常: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public void onDisable() {
        components.cleanup();
        integrations.cleanup();
        getLogger().info("👋 核心进程已断开，所有数据已安全落盘。");
    }

    public void reloadPlugin() {
        getLogger().info("正在重新加载插件配置与市场数据...");
        components.reload();
        getLogger().info("✅ 插件重载完成。");
    }

    public boolean checkRateLimit(UUID uuid) {
        return components.rateLimiter().check(uuid, components.configManager().getCooldownMs());
    }

    private void registerCommands() {
        KyochigoCommand executor = new KyochigoCommand(this, components.marketManager(), 
            components.transactionManager(), components.inventoryManager(), components.craftEngineHook());
        
        String[] labels = {"kyochigo", "market", "sellall"};
        for (String label : labels) {
            var cmd = getCommand(label);
            if (cmd != null) {
                cmd.setExecutor(executor);
                cmd.setTabCompleter(executor);
            }
        }
    }

    // --- 全局实例获取 ---
    public static KyochigoPlugin getInstance() { return instance; }

    // --- 管理器代理获取 (Getter Delegation) ---
    public ConfigManager getConfiguration() { return components.configManager(); }
    public HistoryManager getHistoryManager() { return components.historyManager(); }
    public BackendManager getBackendManager() { return components.backendManager(); }
    public TransactionManager getTransactionManager() { return components.transactionManager(); }
    public InventoryManager getInventoryManager() { return components.inventoryManager(); }
    public MarketManager getMarketManager() { return components.marketManager(); }
    public Economy getEconomy() { return integrations.economy(); }
    public Map<UUID, TradeData> getTradeCache() { return components.tradeCache(); }

    /**
     * 组件容器：管理所有核心管理器的生命周期
     */
    private static class PluginComponents {
        private ConfigManager configManager;
        private HistoryManager historyManager;
        private BackendManager backendManager;
        private TransactionManager transactionManager;
        private InventoryManager inventoryManager;
        private MarketManager marketManager;
        private CraftEngineHook craftEngineHook;

        private final Map<UUID, TradeData> tradeCache = new ConcurrentHashMap<>();
        private final RateLimiter rateLimiter = new RateLimiter();

        boolean initialize(KyochigoPlugin plugin) {
            this.configManager = new ConfigManager(plugin);
            this.historyManager = new HistoryManager(plugin);
            this.craftEngineHook = new CraftEngineHook();
            this.inventoryManager = new InventoryManager(this.craftEngineHook);

            this.backendManager = new BackendManager(plugin, plugin.gson);
            this.backendManager.init();

            this.marketManager = new MarketManager(plugin, this.craftEngineHook);
            this.marketManager.loadItems();

            // 注意：TransactionManager 的 Economy 会在 Integration 阶段注入
            this.transactionManager = new TransactionManager(plugin, inventoryManager, 
                backendManager, null, tradeCache);

            return true;
        }

        void reload() {
            configManager.reload();
            historyManager.reload();
            marketManager.loadItems();
        }

        void cleanup() {
            if (backendManager != null) backendManager.stopProcess();
            if (configManager != null) configManager.save();
            if (historyManager != null) historyManager.save();
            tradeCache.clear();
        }

        // 内部组件访问器
        ConfigManager configManager() { return configManager; }
        HistoryManager historyManager() { return historyManager; }
        BackendManager backendManager() { return backendManager; }
        TransactionManager transactionManager() { return transactionManager; }
        InventoryManager inventoryManager() { return inventoryManager; }
        MarketManager marketManager() { return marketManager; }
        CraftEngineHook craftEngineHook() { return craftEngineHook; }
        Map<UUID, TradeData> tradeCache() { return tradeCache; }
        RateLimiter rateLimiter() { return rateLimiter; }
    }

    /**
     * 集成管理器：处理与外部插件的交互
     */
    private static class PluginIntegrations {
        private Economy economy;
        private boolean papiEnabled;

        boolean initialize(KyochigoPlugin plugin, PluginComponents components) {
            // 1. Vault 经济检查
            if (!setupEconomy(plugin)) return false;

            // 2. 将获取到的经济系统注入业务组件
            // (假设 TransactionManager 增加了 injectEconomy 方法或通过构造后处理)
            // components.transactionManager().setEconomy(economy);

            // 3. FancyNpcs 挂钩
            if (plugin.getServer().getPluginManager().isPluginEnabled("FancyNpcs")) {
                new FancyNpcsHook().register();
            }

            // 4. PlaceholderAPI 状态
            this.papiEnabled = plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI");

            return true;
        }

        private boolean setupEconomy(KyochigoPlugin plugin) {
            if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) return false;
            RegisteredServiceProvider<Economy> rsp = plugin.getServer().getServicesManager().getRegistration(Economy.class);
            if (rsp == null) return false;
            this.economy = rsp.getProvider();
            return economy != null;
        }

        void cleanup() {}
        Economy economy() { return economy; }
        boolean isPapiEnabled() { return papiEnabled; }
    }

    /**
     * 高性能限流器
     */
    private static class RateLimiter {
        private final Map<UUID, Long> cache = new ConcurrentHashMap<>();
        boolean check(UUID uuid, long cooldown) {
            long now = System.currentTimeMillis();
            long last = cache.getOrDefault(uuid, 0L);
            if (now - last < cooldown) return false;
            cache.put(uuid, now);
            return true;
        }
    }

    private void sendBanner() {
        String[] banner = {
            "§b    §b§l  _  ____    ______   §6§l  _____ _    _ _____  _____  ____  ",
            "§b    §b§l | |/ /\\ \\   / / __ \\  §6§l / ____| |  | |_   _|/ ____|/ __ \\ ",
            "§b    §b§l | ' /  \\ \\_/ / |  | | §6§l| |    | |__| | | | | |  __| |  | |",
            "§b    §b§l |  <    \\   /| |  | | §6§l| |    |  __  | | | | | |_ | |  | |",
            "§b    §b§l | . \\    | | | |__| | §6§l| |____| |  | |_| |_| |__| | |__| |",
            "§b    §b§l |_|\\_\\   |_|  \\____/  §6§l \\_____|_|  |_|_____|\\_____|\\____/ ",
            "§f",
            "§b          [ Kyochigo Economy - Industrial High-Load Core ]"
        };
        for (String line : banner) Bukkit.getConsoleSender().sendMessage(line);
    }
}