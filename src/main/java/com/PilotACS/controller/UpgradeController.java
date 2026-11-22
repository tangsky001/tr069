/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.294
 *
 */

package com.PilotACS.controller;

import com.PilotACS.common.log.Logs;
import com.PilotACS.common.result.Result;
import com.PilotACS.common.security.RequiresPermission;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
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

/** 在线升级：检查版本 → 多线程下载 → 确认重启 / 取消回滚 */
@RestController
@RequestMapping("/upgrade")
@Tag(name = "固件升级", description = "OLT/HGU 固件包上传、下发与升级进度")
@RequiresPermission("upgrade:view")
public class UpgradeController {

    /** 升级服务器地址 */
    private static final String UPGRADE_SERVER_BASE = "http://total.i-ddns.com:11236";

    /** 下载体积上限 */
    private static final long MAX_DOWNLOAD_SIZE = 512L * 1024 * 1024;

    /** 服务端标识 */
    private static final String SERVER_ID = "acs";

    /** 升级包文件名 */
    private static final String JAR_NAME = "PilotACS-server.jar";

    /** 多线程下载线程数 */
    private static final int THREAD_COUNT = 4;

    private static final AtomicLong DOWNLOAD_TOTAL = new AtomicLong(0);
    private static final AtomicLong DOWNLOAD_LOADED = new AtomicLong(0);
    private static volatile String DOWNLOAD_STATUS = "idle"; // idle/running/done/error
    private static volatile String DOWNLOAD_ERROR = "";

    /** 期望 SHA-256 缓存 */
    private static final ConcurrentHashMap<String, String> EXPECTED_SHA256 = new ConcurrentHashMap<>();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    /** 检查是否有可升级的版本 */
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
                    String downloadUrl = UPGRADE_SERVER_BASE + "/api/version/download/" + ver.path("id").asLong();
                    data.put("serverVersion", serverVersion);
                    data.put("description", ver.path("description").asText(null));
                    data.put("fileSize", ver.path("fileSize").asLong(0));
                    data.put("versionId", ver.path("id").asLong(0));
                    data.put("hasUpdate", isNewerVersion(serverVersion, currentVersion));
                    data.put("downloadUrl", downloadUrl);
                    String sha = ver.path("sha256").asText(null);
                    if (sha != null && !sha.isBlank()) {
                        EXPECTED_SHA256.put(downloadUrl, sha.trim().toLowerCase());
                        data.put("sha256", sha);
                    }
                    return Result.ok(data);
                }
            }
            data.put("hasUpdate", false);
            data.put("message", "暂无可用版本");
        } catch (Exception e) {
            Logs.warn(Logs.Module.UPGRADE, "版本检查连接失败", "原因", e.getMessage());
            data.put("hasUpdate", false);
            data.put("message", "无法连接升级服务器: " + e.getMessage());
        }
        return Result.ok(data);
    }

    /** 触发后台异步多线程下载升级包 */
    @PostMapping("/download")
    @RequiresPermission("upgrade:update")
    public Result<Map<String, Object>> startDownload(
            @RequestParam("downloadUrl") String downloadUrl,
            @RequestParam(value = "fileSize", defaultValue = "0") long fileSize,
            @RequestParam(value = "sha256", required = false) String sha256) {

        Map<String, Object> data = new LinkedHashMap<>();
        String allowedPrefix = UPGRADE_SERVER_BASE + "/api/version/download/";
        if (downloadUrl == null || !downloadUrl.startsWith(allowedPrefix)) {
            return Result.fail("downloadUrl 非法：仅允许升级服务器地址 " + allowedPrefix);
        }
        if (fileSize < 0 || fileSize > MAX_DOWNLOAD_SIZE) {
            return Result.fail("fileSize 超限：最大支持 " + (MAX_DOWNLOAD_SIZE / 1024 / 1024) + "MB");
        }
        if ("running".equals(DOWNLOAD_STATUS)) {
            data.put("success", false);
            data.put("message", "下载任务正在进行中，请勿重复触发");
            return Result.ok(data);
        }
        String expectedSha = sha256 != null && !sha256.isBlank()
                ? sha256.trim().toLowerCase() : EXPECTED_SHA256.get(downloadUrl);

        DOWNLOAD_STATUS = "running";
        DOWNLOAD_ERROR = "";
        DOWNLOAD_TOTAL.set(fileSize);
        DOWNLOAD_LOADED.set(0);

        CompletableFuture.runAsync(() -> {
            String savePath = jarPath();
            String bakPath = savePath + ".bak";
            try {
                File oldJar = new File(savePath);
                File bakJar = new File(bakPath);
                if (oldJar.exists()) {
                    if (bakJar.exists()) bakJar.delete();
                    oldJar.renameTo(bakJar);
                    Logs.info(Logs.Module.UPGRADE, "旧版本已备份", "路径", bakPath);
                }

                if (fileSize > 0) {
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

                verifyUpgradePackage(new File(savePath), expectedSha);

                DOWNLOAD_STATUS = "done";
                Logs.info(Logs.Module.UPGRADE, "下载完成", "路径", savePath,
                        "sha256", expectedSha == null ? "未提供期望值，仅校验 jar 结构" : "校验通过");
            } catch (Exception e) {
                DOWNLOAD_STATUS = "error";
                DOWNLOAD_ERROR = e.getMessage();
                Logs.error(Logs.Module.UPGRADE, "下载失败", "原因", e.getMessage());
                restoreBackup();
            }
        });

        data.put("success", true);
        data.put("message", "下载任务已启动");
        return Result.ok(data);
    }

    /** 下载一个分片 */
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

    /** 查询后台下载进度 */
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
        return Result.ok(data);
    }

    /** 手动上传 JAR 包升级 */
    @PostMapping("/upload")
    @RequiresPermission("upgrade:update")
    public Result<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                              @RequestParam(value = "sha256", required = false) String sha256) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (file == null || file.isEmpty()) {
            return Result.fail("请选择要上传的JAR文件");
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || !originalName.endsWith(".jar")) {
            return Result.fail("只允许上传 .jar 文件");
        }
        try {
            String savePath = jarPath();
            String bakPath = savePath + ".bak";
            File oldJar = new File(savePath);
            File bakJar = new File(bakPath);
            if (oldJar.exists()) {
                if (bakJar.exists()) bakJar.delete();
                oldJar.renameTo(bakJar);
            }
            file.transferTo(new File(savePath).toPath());
            verifyUpgradePackage(new File(savePath),
                    sha256 == null || sha256.isBlank() ? null : sha256.trim().toLowerCase());
            data.put("success", true);
            data.put("message", "上传成功，文件已保存为 " + JAR_NAME);
            data.put("path", savePath);
            data.put("size", new File(savePath).length());
            data.put("sha256", sha256OfFile(new File(savePath)));
            DOWNLOAD_STATUS = "done";
        } catch (Exception e) {
            Logs.error(Logs.Module.UPGRADE, "上传保存文件失败", e);
            restoreBackup();
            return Result.fail("保存文件失败: " + e.getMessage());
        }
        return Result.ok(data);
    }

    /** 取消升级并回滚备份 */
    @PostMapping("/cancel")
    @RequiresPermission("upgrade:update")
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
        return Result.ok(data);
    }

    /** 确认升级：退出进程 */
    @PostMapping("/restart")
    @RequiresPermission("upgrade:update")
    public Result<Map<String, Object>> restart() {
        String jarPath = jarPath();
        if (!new File(jarPath).exists()) {
            return Result.fail("未找到升级包 " + JAR_NAME + "，请先下载或上传升级包");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("success", true);
        data.put("message", "进程即将退出，请等待守护程序使用新版本重启服务");
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
            }
            String bakPath = jarPath + ".bak";
            File bakJar = new File(bakPath);
            if (bakJar.exists()) {
                bakJar.delete();
                Logs.info(Logs.Module.UPGRADE, "已删除备份文件", "路径", bakPath);
            }
            Logs.info(Logs.Module.UPGRADE, "收到确认升级指令，进程即将退出");
            System.exit(0);
        });
        return Result.ok(data);
    }

    /** 升级包完整性校验 */
    private void verifyUpgradePackage(File jar, String expectedSha256) throws Exception {
        if (!jar.exists() || jar.length() == 0) {
            throw new RuntimeException("升级包为空");
        }
        if (!looksLikeZip(jar)) {
            throw new RuntimeException("升级包不是有效的 JAR（缺 PK 魔数），已回滚");
        }
        String actual = sha256OfFile(jar);
        Logs.info(Logs.Module.UPGRADE, "升级包校验", "大小", jar.length(), "sha256", actual);
        if (expectedSha256 != null && !expectedSha256.isBlank() && !expectedSha256.equalsIgnoreCase(actual)) {
            throw new RuntimeException("升级包 SHA-256 校验失败：期望 " + expectedSha256 + "，实际 " + actual);
        }
    }

    /** JAR/ZIP 魔数检查 */
    private boolean looksLikeZip(File jar) throws Exception {
        try (InputStream in = new FileInputStream(jar)) {
            int b0 = in.read();
            int b1 = in.read();
            return b0 == 0x50 && b1 == 0x4B;
        }
    }

    /** 文件 SHA-256 */
    private String sha256OfFile(File jar) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(jar)) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) != -1) {
                digest.update(buf, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private String jarPath() {
        return System.getProperty("user.dir") + File.separator + JAR_NAME;
    }

    /** 恢复备份 */
    private boolean restoreBackup() {
        String savePath = jarPath();
        String bakPath = savePath + ".bak";
        File newJar = new File(savePath);
        File bakJar = new File(bakPath);
        if (!bakJar.exists()) return false;
        if (newJar.exists()) newJar.delete();
        boolean ok = bakJar.renameTo(newJar);
        if (ok) {
            Logs.info(Logs.Module.UPGRADE, "已恢复备份", "备份", bakPath, "恢复到", savePath);
        } else {
            Logs.warn(Logs.Module.UPGRADE, "恢复备份失败", "路径", bakPath);
        }
        return ok;
    }

    /** 当前版本号 */
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

    /** 比较版本号 */
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
