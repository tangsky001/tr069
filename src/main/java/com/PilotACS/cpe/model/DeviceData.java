/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.cpe.model;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 会话内存参数树（对应 genieacs DeviceData），会话结束时 diff 落 SQLite
 */
public class DeviceData {

    private final Map<String, CpeParam> params = new ConcurrentHashMap<>();
    /** 本次会话有变更的路径 */
    private final Set<String> dirty = new CopyOnWriteArraySet<>();
    /** 是否做过全树刷新（GPN 全量）→ 落库时整树重写，删掉库里多余的行 */
    private volatile boolean fullSync = false;

    public void put(CpeParam p) {
        if (p == null || p.path() == null || p.path().isEmpty()) {
            return;
        }
        params.put(p.path(), p);
        dirty.add(p.path());
    }

    /** 会话开始时从库里灌入存量参数树：不算脏，不参与落库 diff */
    public void loadAll(Map<String, CpeParam> loaded) {
        if (loaded != null) {
            params.putAll(loaded);
        }
    }

    /** 更新值与时间戳；路径未登记过（直接 GPV 未先 GPN）时按叶子补录 */
    public void putValue(String path, String value, String type, long ts) {
        CpeParam old = params.get(path);
        if (old != null) {
            params.put(path, new CpeParam(path, value, type, old.object(), old.writable(), ts));
        } else {
            params.put(path, new CpeParam(path, value, type, false, true, ts));
        }
        dirty.add(path);
    }

    public CpeParam get(String path) {
        return params.get(path);
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

    /** 某个对象前缀下的直接子对象实例数，pattern 如 InternetGatewayDevice.LANDevice.{i}.LANEthernetInterface. */
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

    /** 找到 objectPrefix 下的实例号列表，如 ...LANWLANConfiguration. → [1, 5] */
    public List<Integer> instances(String objectPrefix) {
        String pre = objectPrefix.endsWith(".") ? objectPrefix : objectPrefix + ".";
        return params.keySet().stream()
                .filter(p -> p.startsWith(pre))
                .map(p -> p.substring(pre.length()))
                .filter(rest -> rest.matches("\\d+.*"))
                .map(rest -> rest.substring(0, rest.indexOf('.')))
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
