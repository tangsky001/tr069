package com.tr069.acs.worklist.registry;

import com.tr069.acs.worklist.loader.LoadedItem;
import lombok.Getter;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 配置文件的内存注册表基类。
 *
 * <p>内部用不可变快照保存全部配置，热加载时整体替换，
 * 保证读取端不会看到「改了一半」的中间状态。</p>
 *
 * @param <T> 配置对象类型
 */
public abstract class ConfigRegistry<T> {

    private final AtomicReference<Map<String, LoadedItem<T>>> items =
            new AtomicReference<>(Map.of());

    @Getter
    private final AtomicLong version = new AtomicLong();

    @Getter
    private volatile Instant lastLoadTime = Instant.EPOCH;

    /**
     * 按 key 查询。
     */
    public Optional<LoadedItem<T>> get(String key) {
        if (key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(items.get().get(key));
    }

    /**
     * 查询全部条目（含加载失败的）。
     */
    public Collection<LoadedItem<T>> all() {
        return items.get().values();
    }

    /**
     * 仅返回解析成功的配置对象。
     */
    public List<T> validValues() {
        return items.get().values().stream()
                .filter(LoadedItem::isValid)
                .map(LoadedItem::getValue)
                .collect(Collectors.toList());
    }

    /**
     * 返回加载失败的条目，便于管理端提示。
     */
    public List<LoadedItem<T>> invalidItems() {
        return items.get().values().stream()
                .filter(item -> !item.isValid())
                .collect(Collectors.toList());
    }

    /**
     * 增加一个条目。
     */
    public void put(String key, LoadedItem<T> item) {
        Map<String, LoadedItem<T>> next = new LinkedHashMap<>(items.get());
        next.put(key, item);
        commit(next);
    }

    /**
     * 删除一个条目。
     */
    public void remove(String key) {
        Map<String, LoadedItem<T>> next = new LinkedHashMap<>(items.get());
        if (next.remove(key) != null) {
            commit(next);
        }
    }

    /**
     * 整体替换全部配置。
     */
    public void replaceAll(Map<String, LoadedItem<T>> newItems) {
        commit(new ConcurrentHashMap<>(newItems));
    }

    /**
     * 当前条目数。
     */
    public int size() {
        return items.get().size();
    }

    private void commit(Map<String, LoadedItem<T>> next) {
        items.set(Collections.unmodifiableMap(next));
        version.incrementAndGet();
        lastLoadTime = Instant.now();
    }
}
