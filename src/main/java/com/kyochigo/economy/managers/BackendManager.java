package com.kyochigo.economy.managers;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.kyochigo.economy.KyochigoPlugin;
import com.kyochigo.economy.TradeData;
import com.kyochigo.economy.model.MarketItem;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 后端服务管理器 (v3.2 最终适配版)
 * 职责：管理 Rust 进程生命周期，提供标准化的 REST API 调用接口。
 * 更新：适配了汇率锁定参数 (manualEnvIndex)。
 */
public class BackendManager {

    private final KyochigoPlugin plugin;
    private final Gson gson;
    private final HttpClient httpClient;
    private Process rustProcess;
    private final String binaryName;

    public BackendManager(KyochigoPlugin plugin, Gson gson) {
        this.plugin = plugin;
        this.gson = gson;
        
        // 动态识别系统环境
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        this.binaryName = isWindows ? "economy-core.exe" : "economy-core";

        // Java 11+ HttpClient
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                // [优化] 稍微放宽超时时间，防止 Windows 进程冷启动时的握手延迟
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public void init() {
        extractResources();
        startProcess();
    }

    private <T> void syncCallback(Consumer<T> callback, T result) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(result));
        }
    }

    // =========================================================================
    // 1. 市场同步接口
    // =========================================================================
    
    public void syncMarketData(JsonArray items, Consumer<Boolean> callback) {
        JsonObject requestBody = new JsonObject();
        requestBody.add("items", items);

        String url = plugin.getConfiguration().getBackendUrl() + "/api/market/sync";
        
        sendPostRequest(url, requestBody, res -> {
            boolean success = res.statusCode() == 200;
            syncCallback(callback, success);
        }, err -> {
            plugin.getLogger().warning("❌ 市场同步网络错误: " + err.getMessage());
            syncCallback(callback, false);
        });
    }

    // =========================================================================
    // 2. 单品交易请求 (核心计价)
    // =========================================================================

    /**
     * 发送计价/交易请求
     * @param type "buy" 或 "sell"，决定调用后端哪个接口
     * @param manualEnvIndex 如果为 null，后端使用实时环境指数；如果不为 null，后端强制使用该值（防滑点）。
     */
    public void sendCalculateRequest(Player player, String type, String itemId, double amount, 
                                     double basePrice, double decayLambda, Double manualEnvIndex, 
                                     boolean isPreview, Consumer<JsonObject> callback) {
        
        // [关键] 路由分流：根据操作类型选择后端接口
        String endpoint = type.equalsIgnoreCase("buy") ? "/calculate_buy" : "/calculate_sell";
        String url = plugin.getConfiguration().getBackendUrl() + endpoint;

        JsonObject body = new JsonObject();
        body.addProperty("playerId", player.getUniqueId().toString());
        body.addProperty("playerName", player.getName());
        body.addProperty("itemId", itemId);
        body.addProperty("amount", amount);
        body.addProperty("basePrice", basePrice);
        body.addProperty("decayLambda", decayLambda);
        body.addProperty("isPreview", isPreview);

        // [核心适配] 传递锁定的环境指数
        if (manualEnvIndex != null) {
            body.addProperty("manualEnvIndex", manualEnvIndex);
        }

        sendPostRequest(url, body, res -> {
            if (res.statusCode() == 200) {
                syncCallback(callback, gson.fromJson(res.body(), JsonObject.class));
            } else {
                plugin.getLogger().severe("交易请求被拒绝 (HTTP " + res.statusCode() + "): " + res.body());
                syncCallback(callback, null);
            }
        }, ex -> {
            plugin.getLogger().severe("交易请求通讯失败: " + ex.getMessage());
            syncCallback(callback, null);
        });
    }

    // =========================================================================
    // 3. 批量交易接口 (Batch Sell)
    // =========================================================================

    public void sendBatchSellRequest(Player player, List<TradeData> trades, Consumer<JsonObject> callback) {
        String url = plugin.getConfiguration().getBackendUrl() + "/batch_sell";

        JsonObject root = new JsonObject();
        JsonArray requestsArray = new JsonArray();

        for (TradeData trade : trades) {
            MarketItem item = plugin.getMarketManager().getItem(trade.configKey);
            if (item == null) continue;

            // 批量交易通常是直接执行，isPreview = false
            requestsArray.add(trade.toJsonForBackend(player, item, false));
        }

        root.add("requests", requestsArray);
        // 批量交易也需要附带玩家信息，虽然具体由内部请求决定，但为了日志方便可加
        root.addProperty("playerId", player.getUniqueId().toString());
        root.addProperty("playerName", player.getName());

        sendPostRequest(url, root, res -> {
            if (res.statusCode() == 200) {
                syncCallback(callback, gson.fromJson(res.body(), JsonObject.class));
            } else {
                plugin.getLogger().severe("批量交易失败 (HTTP " + res.statusCode() + "): " + res.body());
                syncCallback(callback, null);
            }
        }, ex -> {
            plugin.getLogger().severe("批量交易通讯异常: " + ex.getMessage());
            syncCallback(callback, null);
        });
    }

    // =========================================================================
    // 4. 行情获取接口
    // =========================================================================

    public void fetchBulkPrices(List<String> itemIds, Consumer<JsonObject> callback) {
        String url = plugin.getConfiguration().getBackendUrl() + "/api/market/prices";
        JsonObject body = new JsonObject();
        JsonArray idsArray = new JsonArray();
        for (String id : itemIds) idsArray.add(id);
        body.add("itemIds", idsArray);

        sendPostRequest(url, body, res -> {
            if (res.statusCode() == 200) {
                syncCallback(callback, gson.fromJson(res.body(), JsonObject.class));
            } else {
                // 静默失败，通常是因为后端还没准备好
                syncCallback(callback, null);
            }
        }, ex -> syncCallback(callback, null));
    }

    // =========================================================================
    // 5. 底层网络与进程管理
    // =========================================================================

    private void sendPostRequest(String url, JsonObject jsonBody, 
                                 Consumer<HttpResponse<String>> onSuccess, 
                                 Consumer<Throwable> onError) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(jsonBody)))
                    .timeout(Duration.ofSeconds(10)) 
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(onSuccess)
                    .exceptionally(ex -> {
                        onError.accept(ex);
                        return null;
                    });
        } catch (Exception e) {
            onError.accept(e);
        }
    }

    private void extractResources() {
        try {
            File backendDir = new File(plugin.getDataFolder(), "backend/static");
            if (!backendDir.exists()) backendDir.mkdirs();
            
            File coreFile = new File(plugin.getDataFolder(), "backend/" + binaryName);
            if (!coreFile.exists()) {
                plugin.saveResource("backend/" + binaryName, false);
            }
            
            if (!System.getProperty("os.name").toLowerCase().contains("win")) {
                boolean chmod = coreFile.setExecutable(true);
                if (!chmod) plugin.getLogger().warning("无法自动设置后端可执行权限，请手动执行 chmod +x");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("资源释放提示: " + e.getMessage());
        }
    }
    
    private void startProcess() {
        if (rustProcess != null && rustProcess.isAlive()) return;
        try {
            File executable = new File(plugin.getDataFolder(), "backend/" + binaryName);
            if (!executable.exists()) {
                plugin.getLogger().severe("找不到后端核心文件: " + executable.getAbsolutePath());
                return;
            }

            ProcessBuilder pb = new ProcessBuilder(executable.getAbsolutePath());
            pb.directory(executable.getParentFile());
            pb.redirectErrorStream(true);
            rustProcess = pb.start();
            
            // 异步日志转发
            new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(rustProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (plugin.getConfiguration().isDebug()) {
                            plugin.getLogger().info("[Rust] " + line);
                        }
                    }
                } catch (IOException ignored) {}
            }, "Kyochigo-Backend-Logger").start();
            
            plugin.getLogger().info("🚀 后端进程 (" + binaryName + ") 已启动");
        } catch (IOException e) {
            plugin.getLogger().severe("无法启动后端进程: " + e.getMessage());
        }
    }

    public void stopProcess() {
        if (this.rustProcess != null && this.rustProcess.isAlive()) {
            this.rustProcess.destroy(); // 发送 SIGTERM
            try {
                if (!this.rustProcess.waitFor(5, TimeUnit.SECONDS)) {
                    this.rustProcess.destroyForcibly(); // 强杀
                }
            } catch (InterruptedException e) {
                this.rustProcess.destroyForcibly();
            }
        }
    }
}