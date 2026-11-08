package com.tr069.acs.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tr069.acs.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 在线升级：检查版本 → 多线程下载 → 确认重启 / 取消回滚
 *
 * <p>升级服务器复用统计上报的 hserver（total.i-ddns.com:11236），
 * 查询时带 serverId=acs 区分体系。
 *
 * <p>JAR 包名 pilotacs-server.jar，与 pom.xml finalName 一致。
 */
@Slf4j
@RestController
@RequestMapping("/api/upgrade")
public class UpgradeController {

    /** hserver 地址（与统计上报一致） */
    private static final String UPGRADE_SERVER_BASE = "http://total.i-ddns.com:11236";

    /** ACS 的服务端标识 */
    private static final String SERVER_ID = "acs";

    /** 升级后保存的 JAR 文件名（与 pom.xml finalName 一致） */
    private static final String JAR_NAME = "pilotacs-server.jar";

    /** 多线程下载线程数 */
    private static final int THREAD_COUNT = 4;

    // ===== 下载状态（单例，同时只允许一个下载任务） =====
    private static final AtomicLong DOWNLOAD_TOTAL = new AtomicLong(0);
    private static final AtomicLong DOWNLOAD_LOADED = new AtomicLong(0);
    private static volatile String DOWNLOAD_STATUS = "idle"; // idle/running/done/error
    private static volatile String DOWNLOAD_ERROR = "";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    /**
     * 检查是否有可升级的版本
     */
    @GetMapping("/check")
    public Result<Map<String, Object>> check() {
        Map<String, Object> data = new LinkedHashMap<>();
        String currentVersion = currentVersion();
        data.put("currentVersion", currentVersion);

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(UPGRADE_SERVER_BASE + "/api/version/latest?serverId=" + SERVER_ID))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(resp.body());
                if (root.path("code").asInt() == 200 && root.has("data")) {
                    JsonNode ver = root.get("data");
                    String serverVersion = ver.path("version").asText();
                    data.put("serverVersion", serverVersion);
                    data.put("description", ver.path("description").asText(null));
                    data.put("fileSize", ver.path("fileSize").asLong(0));
                    data.put("versionId", ver.path("id").asLong(0));
                    data.put("hasUpdate", isNewerVersion(serverVersion, currentVersion));
                    data.put("downloadUrl", UPGRADE_SERVER_BASE + "/api/version/download/" + ver.path("id").asLong());
                    return Result.success(data);
                }
            }
            data.put("hasUpdate", false);
            data.put("message", "暂无可用版本");
        } catch (Exception e) {
            log.warn("[升级检查] 连接失败: {}", e.getMessage());
            data.put("hasUpdate", false);
            data.put("message", "无法连接升级服务器: " + e.getMessage());
        }
        return Result.success(data);
    }

    /**
     * 触发后台异步多线程下载升级包
     */
    @PostMapping("/download")
    public Result<Map<String, Object>> startDownload(
            @RequestParam("downloadUrl") String downloadUrl,
            @RequestParam(value = "fileSize", defaultValue = "0") long fileSize) {

        Map<String, Object> data = new LinkedHashMap<>();
        if ("running".equals(DOWNLOAD_STATUS)) {
            data.put("success", false);
            data.put("message", "下载任务正在进行中，请勿重复触发");
            return Result.success(data);
        }

        DOWNLOAD_STATUS = "running";
        DOWNLOAD_ERROR = "";
        DOWNLOAD_TOTAL.set(fileSize);
        DOWNLOAD_LOADED.set(0);

        CompletableFuture.runAsync(() -> {
            String savePath = jarPath();
            String bakPath = savePath + ".bak";
            try {
                // 备份旧 jar
                File oldJar = new File(savePath);
                File bakJar = new File(bakPath);
                if (oldJar.exists()) {
                    if (bakJar.exists()) bakJar.delete();
                    oldJar.renameTo(bakJar);
                    log.info("[升级下载] 旧版本已备份: {}", bakPath);
                }

                if (fileSize > 0) {
                    // 多线程分片下载
                    long chunkSize = (fileSize + THREAD_COUNT - 1) / THREAD_COUNT;
                    byte[][] chunks = new byte[THREAD_COUNT][];
                    ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT);
                    java.util.List<Future<byte[]>> futures = new java.util.ArrayList<>();

                    for (int i = 0; i < THREAD_COUNT; i++) {
                        final int idx = i;
                        final long start = (long) idx * chunkSize;
                        final long end = Math.min(start + chunkSize - 1, fileSize - 1);
                        futures.add(pool.submit(() -> downloadChunk(downloadUrl, start, end)));
                    }
                    for (int i = 0; i < THREAD_COUNT; i++) {
                        chunks[i] = futures.get(i).get();
                    }
                    pool.shutdown();

                    try (FileOutputStream fos = new FileOutputStream(savePath)) {
                        for (byte[] chunk : chunks) {
                            fos.write(chunk);
                        }
                    }
                } else {
                    // 单线程下载（未知文件大小）
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(downloadUrl))
                            .timeout(Duration.ofMinutes(5))
                            .GET()
                            .build();
                    HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
                    if (resp.statusCode() != 200) {
                        throw new RuntimeException("HTTP " + resp.statusCode());
                    }
                    long contentLen = resp.headers().firstValueAsLong("content-length").orElse(0);
                    if (contentLen > 0) DOWNLOAD_TOTAL.set(contentLen);
                    try (InputStream in = resp.body();
                         FileOutputStream fos = new FileOutputStream(savePath)) {
                        byte[] buf = new byte[8192];
                        int read;
                        while ((read = in.read(buf)) != -1) {
                            fos.write(buf, 0, read);
                            DOWNLOAD_LOADED.addAndGet(read);
                        }
                    }
                }

                DOWNLOAD_STATUS = "done";
                log.info("[升级下载] 完成，保存路径: {}", savePath);
            } catch (Exception e) {
                DOWNLOAD_STATUS = "error";
                DOWNLOAD_ERROR = e.getMessage();
                log.error("[升级下载] 失败: {}", e.getMessage());
                restoreBackup();
            }
        });

        data.put("success", true);
        data.put("message", "下载任务已启动");
        return Result.success(data);
    }

    /**
     * 下载一个分片
     */
    private byte[] downloadChunk(String url, long start, long end) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header("Range", "bytes=" + start + "-" + end)
                .GET()
                .build();
        HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 206 && resp.statusCode() != 200) {
            throw new RuntimeException("HTTP " + resp.statusCode());
        }
        try (InputStream in = resp.body()) {
            byte[] buf = new byte[8192];
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            int read;
            while ((read = in.read(buf)) != -1) {
                bos.write(buf, 0, read);
                DOWNLOAD_LOADED.addAndGet(read);
            }
            return bos.toByteArray();
        }
    }

    /**
     * 查询后台下载进度
     */
    @GetMapping("/progress")
    public Result<Map<String, Object>> progress() {
        Map<String, Object> data = new LinkedHashMap<>();
        long loaded = DOWNLOAD_LOADED.get();
        long total = DOWNLOAD_TOTAL.get();
        int percent = (total > 0) ? (int) Math.min(100, loaded * 100 / total) : 0;
        data.put("status", DOWNLOAD_STATUS);
        data.put("loaded", loaded);
        data.put("total", total);
        data.put("percent", percent);
        if ("error".equals(DOWNLOAD_STATUS)) {
            data.put("error", DOWNLOAD_ERROR);
        }
        return Result.success(data);
    }

    /**
     * 手动上传 JAR 包升级
     */
    @PostMapping("/upload")
    public Result<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (file == null || file.isEmpty()) {
            return Result.failure("请选择要上传的JAR文件");
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || !originalName.endsWith(".jar")) {
            return Result.failure("只允许上传 .jar 文件");
        }
        try {
            String savePath = jarPath();
            String bakPath = savePath + ".bak";
            // 备份旧 jar
            File oldJar = new File(savePath);
            File bakJar = new File(bakPath);
            if (oldJar.exists()) {
                if (bakJar.exists()) bakJar.delete();
                oldJar.renameTo(bakJar);
            }
            file.transferTo(new File(savePath).toPath());
            data.put("success", true);
            data.put("message", "上传成功，文件已保存为 " + JAR_NAME);
            data.put("path", savePath);
            data.put("size", new File(savePath).length());
            // 标记为已就绪
            DOWNLOAD_STATUS = "done";
        } catch (Exception e) {
            log.error("[升级上传] 保存文件失败", e);
            return Result.failure("保存文件失败: " + e.getMessage());
        }
        return Result.success(data);
    }

    /**
     * 取消升级并回滚备份
     */
    @PostMapping("/cancel")
    public Result<Map<String, Object>> cancel() {
        DOWNLOAD_STATUS = "idle";
        DOWNLOAD_ERROR = "";
        DOWNLOAD_TOTAL.set(0);
        DOWNLOAD_LOADED.set(0);
        boolean restored = restoreBackup();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("success", true);
        data.put("restored", restored);
        data.put("message", restored ? "已恢复备份文件" : "无备份文件需要恢复");
        return Result.success(data);
    }

    /**
     * 确认升级：退出进程（由外部守护进程/脚本用新 jar 重启）
     */
    @PostMapping("/restart")
    public Result<Map<String, Object>> restart() {
        String jarPath = jarPath();
        if (!new File(jarPath).exists()) {
            return Result.failure("未找到升级包 " + JAR_NAME + "，请先下载或上传升级包");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("success", true);
        data.put("message", "进程即将退出，请等待守护程序使用新版本重启服务");
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
            }
            // 删备份
            String bakPath = jarPath + ".bak";
            File bakJar = new File(bakPath);
            if (bakJar.exists()) {
                bakJar.delete();
                log.info("[升级] 已删除备份文件: {}", bakPath);
            }
            log.info("[升级] 收到确认升级指令，进程即将退出 ...");
            System.exit(0);
        });
        return Result.success(data);
    }

    // ===== 工具方法 =====

    private String jarPath() {
        return System.getProperty("user.dir") + File.separator + JAR_NAME;
    }

    /** 删除新文件，将 .bak 恢复为原文件 */
    private boolean restoreBackup() {
        String savePath = jarPath();
        String bakPath = savePath + ".bak";
        File newJar = new File(savePath);
        File bakJar = new File(bakPath);
        if (!bakJar.exists()) return false;
        if (newJar.exists()) newJar.delete();
        boolean ok = bakJar.renameTo(newJar);
        if (ok) {
            log.info("[升级] 已恢复备份: {} -> {}", bakPath, savePath);
        } else {
            log.warn("[升级] 恢复备份失败: {}", bakPath);
        }
        return ok;
    }

    /** 当前版本号：优先 git.properties 的 git.build.version */
    private String currentVersion() {
        try (InputStream in = UpgradeController.class.getClassLoader()
                .getResourceAsStream("git.properties")) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                String v = props.getProperty("git.build.version");
                if (v != null && !v.isBlank()) return v;
            }
        } catch (Exception ignored) {
        }
        String v = UpgradeController.class.getPackage().getImplementationVersion();
        return (v == null || v.isBlank()) ? "0.0.0" : v;
    }

    /** 比较版本号，判断 serverVer 是否比 localVer 新 */
    private boolean isNewerVersion(String serverVer, String localVer) {
        if (serverVer == null || localVer == null) return false;
        try {
            String[] sv = serverVer.trim().split("\\.");
            String[] lv = localVer.trim().split("\\.");
            int len = Math.max(sv.length, lv.length);
            for (int i = 0; i < len; i++) {
                int s = i < sv.length ? Integer.parseInt(sv[i].replaceAll("[^0-9]", "")) : 0;
                int l = i < lv.length ? Integer.parseInt(lv[i].replaceAll("[^0-9]", "")) : 0;
                if (s > l) return true;
                if (s < l) return false;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
