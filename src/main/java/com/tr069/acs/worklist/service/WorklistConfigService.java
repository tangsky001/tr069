package com.tr069.acs.worklist.service;

import com.tr069.acs.worklist.config.WorklistProperties;
import com.tr069.acs.worklist.loader.LoadedItem;
import com.tr069.acs.worklist.loader.ProfileLoader;
import com.tr069.acs.worklist.loader.WorklistLoader;
import com.tr069.acs.worklist.model.DeviceProfile;
import com.tr069.acs.worklist.model.ReloadReport;
import com.tr069.acs.worklist.model.WorklistTemplate;
import com.tr069.acs.worklist.registry.ProfileRegistry;
import com.tr069.acs.worklist.registry.WorklistRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.util.FileCopyUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * 光猫适配档案与工单模板的配置中心。
 *
 * <p>职责：
 * <ol>
 *   <li>启动时扫描位于 jar 外部的配置根目录，全量加载；</li>
 *   <li>通过 {@link WatchService} 监听文件变更，去抖后重载；</li>
 *   <li>周期性全量扫描兜底（WatchService 在部分挂载盘上会丢事件）；</li>
 *   <li>对外提供手动重载能力，支持管理端一键刷新。</li>
 * </ol>
 * 新增一款光猫只需要往 {@code profiles/} 丢一个 yaml，无需重启。</p>
 */
@Slf4j
@Service
public class WorklistConfigService implements InitializingBean, DisposableBean {

    private static final String SAMPLE_CLASSPATH = "config-sample/";

    private final WorklistProperties properties;
    private final ProfileRegistry profileRegistry;
    private final WorklistRegistry worklistRegistry;
    private final ProfileLoader profileLoader;
    private final WorklistLoader worklistLoader;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicReference<ScheduledFuture<?>> pendingReload = new AtomicReference<>();
    private final Set<Path> watchedDirs = ConcurrentHashMap.newKeySet();
    private final Map<WatchKey, Path> keyToDir = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            (ThreadFactory) r -> {
                Thread t = new Thread(r, "acs-config-scheduler");
                t.setDaemon(true);
                return t;
            });

    private Path root;
    private Path profilesDir;
    private Path operatorsDir;
    private WatchService watchService;
    private Thread watchThread;

    public WorklistConfigService(WorklistProperties properties,
                                 ProfileRegistry profileRegistry,
                                 WorklistRegistry worklistRegistry,
                                 ProfileLoader profileLoader,
                                 WorklistLoader worklistLoader) {
        this.properties = properties;
        this.profileRegistry = profileRegistry;
        this.worklistRegistry = worklistRegistry;
        this.profileLoader = profileLoader;
        this.worklistLoader = worklistLoader;
    }

    // ==================== 生命周期 ====================

    @Override
    public void afterPropertiesSet() {
        if (!properties.isEnabled()) {
            log.info("acs.worklist.enabled=false，已跳过光猫适配配置加载");
            return;
        }
        root = resolveRoot();
        profilesDir = root.resolve(WorklistLoader.getProfilesDir());
        operatorsDir = root.resolve(WorklistLoader.OPERATORS_DIR);
        try {
            Files.createDirectories(profilesDir);
            Files.createDirectories(operatorsDir);
            if (properties.isInitSampleIfEmpty()) {
                unpackSamples();
            }
        } catch (IOException e) {
            log.error("初始化光猫配置目录失败: {}", root, e);
        }
        log.info("光猫配置根目录: {}", root);

        ReloadReport report = reloadAll();
        log.info("光猫适配配置加载完成: profiles={}/{}  worklists={}/{}",
                report.getProfileTotal() - report.getProfileFailed(), report.getProfileTotal(),
                report.getWorklistTotal() - report.getWorklistFailed(), report.getWorklistTotal());

        startHotReload();
    }

    /**
     * 解析配置根目录。
     *
     * <p>配置值优先；若它指向的目录不存在或为空，则依次回退到 jar 同级的
     * {@code configuration/} 与 {@code target/configuration/}。这样现场无论以哪个
     * 工作目录启动（直接在 target 目录里起 jar 会拼成 target/target/configuration），
     * 都能拿到真实配置，而不是落进空目录重新释放示例。</p>
     */
    private Path resolveRoot() {
        Path configured = Path.of(properties.getRootPath()).toAbsolutePath().normalize();
        if (isUsableDirectory(configured)) {
            return configured;
        }
        Path base = jarDirectory();
        if (base != null) {
            List<Path> candidates = List.of(
                    base.resolve("configuration"),
                    base.resolve("target").resolve("configuration"));
            for (Path candidate : candidates) {
                if (isUsableDirectory(candidate)) {
                    log.warn("配置目录 {} 为空，回退使用 {}", configured, candidate.normalize());
                    return candidate.normalize();
                }
            }
        }
        return configured;
    }

    /**
     * 目录存在且非空。
     */
    private static boolean isUsableDirectory(Path dir) {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> children = Files.list(dir)) {
            return children.findAny().isPresent();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 取 jar 所在目录；开发期（classes 目录）同样返回其父目录。
     */
    private static Path jarDirectory() {
        try {
            java.security.CodeSource source = WorklistConfigService.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            return Path.of(source.getLocation().toURI()).getParent();
        } catch (Exception e) {
            log.debug("无法确定 jar 所在目录: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public void destroy() {
        running.set(false);
        scheduler.shutdownNow();
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ignored) {
                // 关闭时忽略
            }
        }
        if (watchThread != null) {
            watchThread.interrupt();
        }
    }

    // ==================== 对外能力 ====================

    /**
     * 全量重载：重新扫描整个配置根目录并原子替换内存。
     */
    public synchronized ReloadReport reloadAll() {
        long begin = System.currentTimeMillis();
        Map<String, LoadedItem<DeviceProfile>> profiles = loadAllProfiles();
        Map<String, LoadedItem<WorklistTemplate>> worklists = loadAllWorklists();

        profileRegistry.replaceAll(profiles);
        worklistRegistry.replaceAll(worklists);

        List<String> errors = new ArrayList<>();
        profiles.values().stream().filter(i -> !i.isValid())
                .forEach(i -> errors.add(i.getRelativePath() + " : " + i.getError()));
        worklists.values().stream().filter(i -> !i.isValid())
                .forEach(i -> errors.add(i.getRelativePath() + " : " + i.getError()));

        return ReloadReport.builder()
                .fullReload(true)
                .profileTotal(profiles.size())
                .profileFailed((int) profiles.values().stream().filter(i -> !i.isValid()).count())
                .worklistTotal(worklists.size())
                .worklistFailed((int) worklists.values().stream().filter(i -> !i.isValid()).count())
                .durationMs(System.currentTimeMillis() - begin)
                .version(profileRegistry.getVersion().get())
                .errors(errors)
                .build();
    }

    /**
     * 单文件重载，用于 WatchService 的增量更新。
     */
    public synchronized boolean reloadFile(Path file) {
        String rel = relativize(file);
        if (file.toAbsolutePath().normalize().startsWith(profilesDir)) {
            LoadedItem<DeviceProfile> item = profileLoader.load(file, rel);
            if (item.isValid()) {
                profileRegistry.put(item.getValue().getKey(), item);
            } else {
                profileRegistry.put(rel, item);
            }
            return item.isValid();
        }
        if (file.toAbsolutePath().normalize().startsWith(operatorsDir)) {
            LoadedItem<WorklistTemplate> item = worklistLoader.load(file, root);
            if (item.isValid()) {
                worklistRegistry.put(item.getValue().getKey(), item);
            } else {
                worklistRegistry.put(rel, item);
            }
            return item.isValid();
        }
        return false;
    }

    /**
     * 移除被删除文件对应的配置。
     */
    public synchronized void removeFile(Path file) {
        String rel = relativize(file);
        profileRegistry.remove(rel);
        worklistRegistry.remove(rel);
        for (LoadedItem<DeviceProfile> item : profileRegistry.all()) {
            if (file.equals(item.getFile())) {
                profileRegistry.remove(item.getRelativePath());
            }
        }
        for (LoadedItem<WorklistTemplate> item : worklistRegistry.all()) {
            if (file.equals(item.getFile())) {
                worklistRegistry.remove(item.getValue() == null
                        ? item.getRelativePath() : item.getValue().getKey());
            }
        }
    }

    /**
     * 配置根目录。
     */
    public Path getRoot() {
        return root;
    }

    /**
     * 把 classpath 中的示例配置释放到配置根目录，方便现场照着改。
     */
    public int unpackSamples() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            // "classpath*:" 会包含 jar 内部的资源
            var resources = resolver.getResources("classpath*:"
                    + SAMPLE_CLASSPATH + "**/*.yaml");
            int count = 0;
            for (var resource : resources) {
                String rel = sampleRelative(resource.getURL().toString());
                if (rel == null) {
                    continue;
                }
                Path target = root.resolve(rel);
                if (Files.exists(target)) {
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (InputStream in = resource.getInputStream()) {
                    Files.write(target, FileCopyUtils.copyToByteArray(in));
                }
                count++;
            }
            if (count > 0) {
                log.info("已在 {} 生成 {} 个示例配置文件", root, count);
            }
            return count;
        } catch (IOException e) {
            log.warn("释放示例配置失败", e);
            return 0;
        }
    }

    // ==================== 内部实现 ====================

    private Map<String, LoadedItem<DeviceProfile>> loadAllProfiles() {
        Map<String, LoadedItem<DeviceProfile>> result = new LinkedHashMap<>();
        for (Path file : listTemplateFiles(profilesDir)) {
            LoadedItem<DeviceProfile> item = profileLoader.load(file, relativize(file));
            putProfile(result, item);
        }
        return result;
    }

    private void putProfile(Map<String, LoadedItem<DeviceProfile>> result,
                            LoadedItem<DeviceProfile> item) {
        if (!item.isValid()) {
            result.put(item.getRelativePath(), item);
            return;
        }
        String key = item.getValue().getKey();
        if (result.containsKey(key)) {
            LoadedItem<DeviceProfile> dup = LoadedItem.fail(item.getFile(), item.getRelativePath(),
                    "光猫档案 key 重复: " + key, item.getLastModified());
            result.put(item.getRelativePath(), dup);
            return;
        }
        result.put(key, item);
    }

    private Map<String, LoadedItem<WorklistTemplate>> loadAllWorklists() {
        Map<String, LoadedItem<WorklistTemplate>> result = new LinkedHashMap<>();
        for (Path file : listTemplateFiles(operatorsDir)) {
            LoadedItem<WorklistTemplate> item = worklistLoader.load(file, root);
            if (!item.isValid()) {
                result.put(item.getRelativePath(), item);
            } else {
                result.put(item.getValue().getKey(), item);
            }
        }
        return result;
    }

    /**
     * 递归列出目录下的工单模板文件。
     */
    private List<Path> listTemplateFiles(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(this::isTemplateFile)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("扫描目录失败: {}", dir, e);
            return List.of();
        }
    }

    /**
     * 工单模板文件：xml 为原 Python 版 data.py 批量转换的存量模板，yaml 用于手工新增。
     */
    private boolean isTemplateFile(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".xml");
    }

    private String relativize(Path file) {
        try {
            return root.relativize(file.toAbsolutePath().normalize()).toString();
        } catch (IllegalArgumentException e) {
            return file.getFileName().toString();
        }
    }

    /**
     * classpath resource URL -> 相对配置根目录的路径。
     */
    private String sampleRelative(String url) {
        int idx = url.indexOf(SAMPLE_CLASSPATH);
        if (idx < 0) {
            return null;
        }
        String tail = url.substring(idx + SAMPLE_CLASSPATH.length());
        return tail.replace("!/", "").replace("jar:", "").split("\\?")[0];
    }

    // ==================== 热加载 ====================

    private void startHotReload() {
        WorklistProperties.HotReload cfg = properties.getHotReload();
        if (!cfg.isEnabled()) {
            log.info("光猫配置热加载已关闭");
            return;
        }
        if (cfg.getRescanIntervalSeconds() > 0) {
            scheduler.scheduleWithFixedDelay(this::safeReloadSafely,
                    cfg.getRescanIntervalSeconds(),
                    cfg.getRescanIntervalSeconds(),
                    TimeUnit.SECONDS);
        }
        try {
            watchService = FileSystems.getDefault().newWatchService();
            watchThread = new Thread(this::watchLoop, "acs-config-watcher");
            watchThread.setDaemon(true);
            watchThread.start();
            log.info("光猫配置热加载已启动: root={}, rescan={}s",
                    root, cfg.getRescanIntervalSeconds());
        } catch (Exception e) {
            log.warn("启动文件监听失败，仅依赖周期扫描兜底: {}", e.getMessage());
        }
    }

    private void watchLoop() {
        try {
            registerRecursive(root);
            while (running.get()) {
                WatchKey key;
                try {
                    key = watchService.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                Path dir = keyToDir.get(key);
                boolean dirty = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    Path child = (dir == null) ? null : dir.resolve(String.valueOf(event.context()));
                    if (child == null) {
                        continue;
                    }
                    if (Files.isDirectory(child)) {
                        try {
                            registerRecursive(child);
                        } catch (IOException ignored) {
                            // 目录可能已被删除
                        }
                        continue;
                    }
                    if (isTemplateFile(child)) {
                        if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                            removeFile(child);
                            log.info("已移除配置: {}", relativize(child));
                        }
                        dirty = true;
                    }
                }
                if (!key.reset()) {
                    keyToDir.remove(key);
                }
                if (dirty) {
                    scheduleDebouncedReload();
                }
            }
        } catch (Exception e) {
            if (running.get()) {
                log.error("文件监听线程异常退出", e);
            }
        }
    }

    private void registerRecursive(Path dir) throws IOException {
        Path normalized = dir.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized) || !watchedDirs.add(normalized)) {
            return;
        }
        keyToDir.put(normalized.register(watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE), normalized);
        try (var children = Files.newDirectoryStream(normalized)) {
            for (Path child : children) {
                if (Files.isDirectory(child)) {
                    registerRecursive(child);
                }
            }
        }
    }

    /**
     * 去抖：编辑器一次保存可能触发多个事件，合并后再统一重载。
     */
    private void scheduleDebouncedReload() {
        ScheduledFuture<?> next = scheduler.schedule(this::reloadChangedFiles,
                properties.getHotReload().getDebounceMillis(), TimeUnit.MILLISECONDS);
        ScheduledFuture<?> prev = pendingReload.getAndSet(next);
        if (prev != null) {
            prev.cancel(false);
        }
    }

    /**
     * 只重载变化过的文件，避免每次全目录扫描。
     */
    private void reloadChangedFiles() {
        int changed = 0;
        for (LoadedItem<DeviceProfile> item : profileRegistry.all()) {
            if (stale(item.getFile(), item.getLastModified())) {
                reloadFile(item.getFile());
                changed++;
            }
        }
        for (LoadedItem<WorklistTemplate> item : worklistRegistry.all()) {
            if (stale(item.getFile(), item.getLastModified())) {
                reloadFile(item.getFile());
                changed++;
            }
        }
        changed += loadNewFiles();
        if (changed > 0) {
            log.info("热加载完成，本次变更 {} 个配置文件", changed);
        }
    }

    /**
     * 扫描新出现的文件。
     */
    private int loadNewFiles() {
        int added = 0;
        for (Path file : listTemplateFiles(profilesDir)) {
            if (!isTracked(file)) {
                reloadFile(file);
                added++;
            }
        }
        for (Path file : listTemplateFiles(operatorsDir)) {
            if (!isTracked(file)) {
                reloadFile(file);
                added++;
            }
        }
        return added;
    }

    private boolean isTracked(Path file) {
        for (LoadedItem<DeviceProfile> item : profileRegistry.all()) {
            if (file.equals(item.getFile())) {
                return true;
            }
        }
        for (LoadedItem<WorklistTemplate> item : worklistRegistry.all()) {
            if (file.equals(item.getFile())) {
                return true;
            }
        }
        return false;
    }

    private boolean stale(Path file, long loadedMillis) {
        if (file == null || !Files.isRegularFile(file)) {
            return false;
        }
        try {
            return Files.getLastModifiedTime(file).toMillis() != loadedMillis;
        } catch (IOException e) {
            return false;
        }
    }

    private void safeReloadSafely() {
        try {
            ReloadReport report = reloadAll();
            if (!report.isSuccess()) {
                log.warn("周期扫描发现配置异常: {}", report.getErrors());
            }
        } catch (Exception e) {
            log.error("周期重载配置失败", e);
        }
    }
}
