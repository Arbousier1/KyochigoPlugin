package com.kyochigo.economy.managers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;


public class BackendManager {
    private final String RUST_BASE_URL = "http://127.0.0.1:9981";
    private final JavaPlugin plugin;
    private final Gson gson;
    private final HttpClient httpClient;
    
    private File historyFile;
    private FileConfiguration historyConfig;
    private Process rustProcess;

    public BackendManager(JavaPlugin plugin, Gson gson) {
        this.plugin = plugin;
        this.gson = gson;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    public void init() {
        // 初始化历史记录文件
        historyFile = new File(plugin.getDataFolder(), "player_counter.yml");
        if (!historyFile.exists()) {
            try {
                historyFile.getParentFile().mkdirs();
                historyFile.createNewFile();
            } catch (IOException e) {}
        }
        historyConfig = YamlConfiguration.loadConfiguration(historyFile);

        // 提取并启动后端
        extractResources();
        startProcess();
    }

    public void saveHistory() {
        try { historyConfig.save(historyFile); } catch (IOException e) {}
    }

    public FileConfiguration getHistoryConfig() {
        return historyConfig;
    }

    public String getHistoryKey(String uuid, String itemKey) {
        return uuid + "." + itemKey;
    }

    private void extractResources() {
        try {
            plugin.saveResource("backend/economy-core.exe", true);
            plugin.saveResource("backend/static/index.html", true);
        } catch (Exception e) {}
    }
    
    private void startProcess() {
        try {
            File f = new File(plugin.getDataFolder(), "backend/economy-core.exe");
            if (!f.exists()) {
                plugin.getLogger().severe("后端核心文件不存在！");
                return;
            }

            ProcessBuilder pb = new ProcessBuilder(f.getAbsolutePath());
            pb.directory(f.getParentFile());
            pb.redirectErrorStream(true);

            rustProcess = pb.start();
            plugin.getLogger().info("✅ Rust 后端进程已启动 (PID: " + rustProcess.pid() + ")，正在等待初始化...");

            InputStream inputStream = rustProcess.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));

            new Thread(() -> {
                String line;
                try {
                    while ((line = reader.readLine()) != null) {
                        plugin.getLogger().info("[Rust Core] " + line);
                    }
                } catch (IOException e) {
                    if (rustProcess.isAlive()) {
                        plugin.getLogger().warning("读取 Rust 日志流中断: " + e.getMessage());
                    }
                }
            }).start();

        } catch (IOException e) {
            plugin.getLogger().severe("无法启动后端进程: " + e.getMessage());
        }
    }

    public void stopProcess() {
        if (this.rustProcess != null && this.rustProcess.isAlive()) {
            plugin.getLogger().info("正在关闭 Rust 后端...");
            this.rustProcess.destroy();
            try {
                boolean exited = this.rustProcess.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (!exited) {
                    plugin.getLogger().warning("Rust 后端未响应，强制结束进程。");
                    this.rustProcess.destroyForcibly();
                }
            } catch (InterruptedException e) {
                this.rustProcess.destroyForcibly();
            }
        }
    }

    public void sendCalculateRequest(JsonObject requestBody, java.util.function.Consumer<JsonObject> callback) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(RUST_BASE_URL + "/calculate_sell"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(requestBody)))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(res -> {
                    if (res.statusCode() == 200) {
                        JsonObject json = gson.fromJson(res.body(), JsonObject.class);
                        callback.accept(json);
                    } else {
                        // 失败回调传 null
                        callback.accept(null);
                    }
                })
                .exceptionally(ex -> {
                    callback.accept(null);
                    return null;
                });
    }
}