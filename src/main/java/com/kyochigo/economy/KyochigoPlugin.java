package com.kyochigo.economy;

import com.google.gson.Gson;
import com.kyochigo.economy.commands.KyochigoCommand;
import com.kyochigo.economy.expansions.KyochigoExpansion;
import com.kyochigo.economy.managers.BackendManager;
import com.kyochigo.economy.managers.InventoryManager;
import com.kyochigo.economy.managers.MarketManager;
import com.kyochigo.economy.managers.TransactionManager;
import com.kyochigo.economy.utils.CraftEngineHook;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class KyochigoPlugin extends JavaPlugin {

    private BackendManager backendManager;
    private TransactionManager transactionManager;
    private InventoryManager inventoryManager;
    private MarketManager marketManager;
    private CraftEngineHook craftEngineHook;
    private Economy economy;
    private final Gson gson = new Gson();

    private final Map<UUID, TradeData> tradeCache = new ConcurrentHashMap<>();
    private final Map<UUID, Long> rateLimiter = new ConcurrentHashMap<>();
    private static final long COOLDOWN_MS = 500;

    @Override
    public void onEnable() {
        // 1. 打印新的启动徽标
        sendBanner();

        long startInit = System.currentTimeMillis();

        saveDefaultConfig();
        this.craftEngineHook = new CraftEngineHook();
        this.inventoryManager = new InventoryManager(this.craftEngineHook);

        if (!setupEconomy()) {
            getLogger().severe("Vault dependency not found!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.marketManager = new MarketManager(this, craftEngineHook);
        this.marketManager.loadItems();

        this.backendManager = new BackendManager(this, gson);
        this.backendManager.init();

        this.transactionManager = new TransactionManager(this, inventoryManager, backendManager, economy, tradeCache);

        KyochigoCommand mainCommand = new KyochigoCommand(this, marketManager, transactionManager, inventoryManager, craftEngineHook);
        registerMainCommands(mainCommand);

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new KyochigoExpansion(this, inventoryManager, marketManager, tradeCache).register();
        }

        long endInit = System.currentTimeMillis() - startInit;
        Bukkit.getConsoleSender().sendMessage("§8[§bKyochigo§8] §f系统核心已就绪 §7(" + endInit + "ms)");
        Bukkit.getConsoleSender().sendMessage("§8[§bKyochigo§8] §f当前模式: §ePaper Dialog v21.4+ §a✔");
    }

    private void sendBanner() {
        String[] banner = {
            "§b   §b§l  _  ____     ______   §6§l  _____ _    _ _____  _____  ____  ",
            "§b   §b§l | |/ /\\ \\   / / __ \\  §6§l / ____| |  | |_   _|/ ____|/ __ \\ ",
            "§b   §b§l | ' /  \\ \\_/ / |  | | §6§l| |    | |__| | | | | |  __| |  | |",
            "§b   §b§l |  <    \\   /| |  | | §6§l| |    |  __  | | | | | |_ | |  | |",
            "§b   §b§l | . \\    | | | |__| | §6§l| |____| |  | |_| |_| |__| | |__| |",
            "§b   §b§l |_|\\_\\   |_|  \\____/  §6§l \\_____|_|  |_|_____|\\_____|\\____/ ",
            "§f",
            "§b          [ Kyochigo Economy - High Performance System ]",
            "§7          Running on Paper-API with Backend Support",
            "§f"
        };

        for (String line : banner) {
            Bukkit.getConsoleSender().sendMessage(line);
        }
    }

    private void registerMainCommands(KyochigoCommand executor) {
        String[] labels = {"kyochigo", "market", "sellall"};
        for (String label : labels) {
            var cmd = getCommand(label);
            if (cmd != null) {
                cmd.setExecutor(executor);
                cmd.setTabCompleter(executor);
            }
        }
    }

    public boolean checkRateLimit(UUID uuid) {
        long now = System.currentTimeMillis();
        long last = rateLimiter.getOrDefault(uuid, 0L);
        if (now - last < COOLDOWN_MS) return false;
        rateLimiter.put(uuid, now);
        return true;
    }

    @Override
    public void onDisable() {
        if (this.backendManager != null) this.backendManager.stopProcess();
        tradeCache.clear();
        rateLimiter.clear();
        saveConfig();
        getLogger().info("KyochigoEconomy has been disabled.");
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        return economy != null;
    }

    public BackendManager getBackendManager() { return backendManager; }
    public TransactionManager getTransactionManager() { return transactionManager; }
    public InventoryManager getInventoryManager() { return inventoryManager; }
    public MarketManager getMarketManager() { return marketManager; }
    public Map<UUID, TradeData> getTradeCache() { return tradeCache; }
}