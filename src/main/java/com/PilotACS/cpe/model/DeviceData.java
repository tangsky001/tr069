/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.cpe.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 会话内存参数树，会话结束时 diff 落 SQLite */
public class DeviceData {

    private final Map<String, CpeParam> params = new ConcurrentHashMap<>();
    /** 本次会话有变更的路径 */
    private final Set<String> dirty = new CopyOnWriteArraySet<>();
    /** 本会话被删除的路径 */
    private final Set<String> tombstones = new CopyOnWriteArraySet<>();
    /** 是否做过全树刷新 */
    private volatile boolean fullSync = false;

    public void put(CpeParam p) {
        if (p == null || p.path() == null || p.path().isEmpty()) {
            return;
        }
        params.put(p.path(), p);
        dirty.add(p.path());
        tombstones.remove(p.path());
    }

    /** 会话开始时灌入存量参数树 */
    public void loadAll(Map<String, CpeParam> loaded) {
        if (loaded != null) {
            params.putAll(loaded);
        }
    }

    /** Inform 参数合并（不覆盖结构信息） */
    public void putInform(CpeParam p) {
        if (p == null || p.path() == null || p.path().isEmpty()) {
            return;
        }
        if (params.containsKey(p.path())) {
            putValue(p.path(), p.value(), p.type(), p.ts());
        } else {
            put(new CpeParam(p.path(), p.value(), p.type(), p.object(), true, p.ts()));
        }
    }

    /** 更新值与时间戳 */
    public void putValue(String path, String value, String type, long ts) {
        CpeParam old = params.get(path);
        if (old != null) {
            params.put(path, new CpeParam(path, value, type, old.object(), old.writable(), ts));
            if (!java.util.Objects.equals(old.value(), value)
                    || !java.util.Objects.equals(old.type(), type)) {
                dirty.add(path);
            }
        } else {
            params.put(path, new CpeParam(path, value, type, false, true, ts));
            dirty.add(path);
        }
        tombstones.remove(path);
    }

    public CpeParam get(String path) {
        return params.get(path);
    }

    /** 删除对象子树并记录墓碑 */
    public List<String> removeSubtree(String objectPath) {
        if (objectPath == null || objectPath.isBlank()) {
            return List.of();
        }
        String prefix = objectPath.endsWith(".") ? objectPath : objectPath + ".";
        List<String> removed = new ArrayList<>();
        params.keySet().removeIf(p -> {
            boolean hit = p.equals(objectPath) || p.startsWith(prefix);
            if (hit) {
                removed.add(p);
            }
            return hit;
        });
        if (!removed.isEmpty()) {
            dirty.removeAll(removed);
            tombstones.addAll(removed);
            dirty.add(objectPath.endsWith(".") ? objectPath : objectPath + ".");
        }
        return removed;
    }

    public Set<String> tombstones() {
        return tombstones;
    }

    public String value(String path) {
        CpeParam p = params.get(path);
        return p == null ? null : p.value();
    }

    public boolean has(String path) {
        return params.containsKey(path);
    }

    public Map<String, CpeParam> all() {
        return params;
    }

    public Set<String> dirtyPaths() {
        return dirty;
    }

    public boolean isFullSync() {
        return fullSync;
    }

    public void markFullSync() {
        this.fullSync = true;
    }

    /** 对象前缀下的实例数 */
    public int countObjects(String objectPrefix) {
        Pattern pat = Pattern.compile(Pattern.quote(objectPrefix) + "\\d+\\.");
        return (int) params.keySet().stream().filter(p -> pat.matcher(p).lookingAt()).count();
    }

    /** 匹配前缀下全部叶子路径 */
    public List<String> leaves(String prefix) {
        return params.keySet().stream()
                .filter(p -> p.startsWith(prefix) && !p.endsWith("."))
                .sorted()
                .collect(Collectors.toList());
    }

    /** 前缀下所有路径 */
    public List<String> allPaths(String prefix) {
        return params.keySet().stream().filter(p -> p.startsWith(prefix)).sorted().collect(Collectors.toList());
    }

    /** 前缀下实例号列表 */
    public List<Integer> instances(String objectPrefix) {
        String pre = objectPrefix.endsWith(".") ? objectPrefix : objectPrefix + ".";
        return params.keySet().stream()
                .filter(p -> p.startsWith(pre))
                .map(p -> p.substring(pre.length()))
                .map(rest -> {
                    int end = 0;
                    while (end < rest.length() && Character.isDigit(rest.charAt(end))) {
                        end++;
                    }
                    return rest.substring(0, end);
                })
                .filter(s -> !s.isEmpty() && s.length() <= 9)
                .map(Integer::parseInt)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    public List<CpeParam> sortedAll() {
        return params.values().stream()
                .sorted(Comparator.comparing(CpeParam::path))
                .collect(Collectors.toList());
    }
}
