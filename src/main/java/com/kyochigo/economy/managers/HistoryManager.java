package com.kyochigo.economy.managers;

import com.kyochigo.economy.KyochigoPlugin;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;

/**
 * 玩家交易历史/计数管理器 (v3.0 高并发读写分离版)
 * 职责：负责 player_counter.yml 的高性能读写，支持多线程并发查询。
 */
public class HistoryManager {

    private final KyochigoPlugin plugin;
    private final File dataFile;
    private FileConfiguration dataConfig;
    
    // 引入读写锁：允许多个线程同时读取，但写入时独占
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    
    // 日期键缓存，减少 LocalDate.now() 的开销
    private volatile String currentDateKey;

    public HistoryManager(KyochigoPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "player_counter.yml");
        this.currentDateKey = LocalDate.now().toString();
        this.init();
    }

    private void init() {
        if (!dataFile.exists()) {
            try {
                if (plugin.getResource("player_counter.yml") != null) {
                    plugin.saveResource("player_counter.yml", false);
                } else {
                    File parent = dataFile.getParentFile();
                    if (parent != null) parent.mkdirs();
                    dataFile.createNewFile();
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "无法创建 player_counter.yml!", e);
            }
        }
        reload();
    }

    public void reload() {
        lock.writeLock().lock(); // 重载需要独占写锁
        try {
            this.dataConfig = YamlConfiguration.loadConfiguration(dataFile);
            this.currentDateKey = LocalDate.now().toString();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void save() {
        lock.readLock().lock(); // 保存时只需读锁（防止保存过程中配置被替换）
        try {
            if (dataConfig != null) {
                dataConfig.save(dataFile);
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "无法保存玩家交易计数数据!", e);
        } finally {
            lock.readLock().unlock();
        }
    }

    public void saveAsync() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::save);
    }

    // --- 内部逻辑辅助 ---

    private String getHistoryPath(String uuid, String itemKey) {
        return "players." + uuid + ".items." + itemKey;
    }

    /**
     * 获取当前日期 Key，若日期已变更则自动更新缓存
     */
    private String getDateKey() {
        String today = LocalDate.now().toString();
        if (!today.equals(currentDateKey)) {
            lock.writeLock().lock();
            try {
                currentDateKey = today;
            } finally {
                lock.writeLock().unlock();
            }
        }
        return currentDateKey;
    }

    // --- 业务操作 ---

    /**
     * 获取玩家特定物品的累计交易量 (支持多线程并发读)
     */
    public int getTradeCount(String uuid, String itemKey) {
        lock.readLock().lock();
        try {
            return dataConfig.getInt(getHistoryPath(uuid, itemKey) + ".total", 0);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 获取玩家今日特定物品的交易量 (支持多线程并发读)
     */
    public int getDailyTradeCount(String uuid, String itemKey) {
        String dateKey = getDateKey();
        lock.readLock().lock();
        try {
            String path = getHistoryPath(uuid, itemKey) + ".daily." + dateKey;
            return dataConfig.getInt(path, 0);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 增加交易计数 (写独占锁)
     */
    public void incrementTradeCount(String uuid, String itemKey, int amount) {
        String dateKey = getDateKey();
        String basePath = getHistoryPath(uuid, itemKey);
        int absAmount = Math.abs(amount);

        lock.writeLock().lock();
        try {
            // 1. 更新总数 (直接从 config 读取，避免重入锁开销)
            int currentTotal = dataConfig.getInt(basePath + ".total", 0);
            dataConfig.set(basePath + ".total", currentTotal + absAmount);

            // 2. 更新每日计数
            String dailyPath = basePath + ".daily." + dateKey;
            int currentDaily = dataConfig.getInt(dailyPath, 0);
            dataConfig.set(dailyPath, currentDaily + absAmount);
            
            // 3. 记录时间戳
            dataConfig.set("players." + uuid + ".last_update", System.currentTimeMillis());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 获取配置对象副本 (读锁保护)
     */
    public FileConfiguration getData() {
        lock.readLock().lock();
        try {
            return dataConfig;
        } finally {
            lock.readLock().unlock();
        }
    }
}