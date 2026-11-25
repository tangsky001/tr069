/**
 * @author: tangsky001
 * @date:   2026年11月26日
 *
 */

package com.PilotACS.cpe.service;

import com.PilotACS.cpe.config.CpeProperties;
import com.PilotACS.cpe.model.DeviceData;
import com.PilotACS.cpe.store.CpeStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 设备申报 LAN 网口目录：LAN 口数量/清单的唯一口径。
 * <p>
 * 数据来源是 SQLite 参数树里设备<b>申报</b>的接口实例（TR-098 的
 * LANEthernetInterface / LANEthernetInterfaceConfig 两套子树取并集，
 * TR-181 的 Device.Ethernet.Interface），而不是前端猜的固定口数——
 * 设备申报几个口，前端就渲染几个口。
 * <p>
 * 消费方：业务快照 LAN 卡片（BusinessSnapshotService）、机型能力探测
 * （CapabilityDetector）、适配测试弹窗（DeviceController）。
 */
@Service
public class LanPortCatalog {

    private final CpeStore store;
    private final CpeProperties props;

    public LanPortCatalog(CpeStore store, CpeProperties props) {
        this.store = store;
        this.props = props;
    }

    /** 某台设备申报的网口清单（含实例号/名称/连接状态/MAC/速率/双工） */
    public List<Map<String, Object>> ports(String sn) {
        DeviceData data = new DeviceData();
        data.loadAll(store.loadParams(sn));
        Object rootObj = store.findDevice(sn).get("root_node");
        String root = rootObj == null || String.valueOf(rootObj).isBlank()
                ? BusinessPaths.detectRoot(data, props.getDefaultRootNode())
                : String.valueOf(rootObj);
        return enumerate(data, root);
    }

    /** 某台设备申报的网口数量 */
    public int count(String sn) {
        return ports(sn).size();
    }

    /**
     * 从参数树枚举申报网口（静态方法：能力探测/快照持有现成 DeviceData 时直接用，
     * 避免重复加载参数树）。
     */
    public static List<Map<String, Object>> enumerate(DeviceData data, String root) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (BusinessPaths.isTr181(root)) {
            String base = "Device.Ethernet.Interface.";
            for (int i : data.instances(base)) {
                String p = base + i + ".";
                String up = data.value(p + "Upstream");
                if (up != null && up.matches("(?i)1|true")) {
                    continue; // 上联口不算 LAN 口
                }
                out.add(entry(p, i, data));
            }
            return out;
        }
        for (int lanDev : data.instances(root + "LANDevice.")) {
            String statusBase = root + "LANDevice." + lanDev + ".LANEthernetInterface.";
            String cfgBase = root + "LANDevice." + lanDev + ".LANEthernetInterfaceConfig.";
            Set<Integer> ids = new LinkedHashSet<>(data.instances(statusBase));
            ids.addAll(data.instances(cfgBase));
            for (int i : ids) {
                out.add(entry(statusBase + i + ".", i, data));
            }
        }
        return out;
    }

    /** 申报网口数量（CapabilityDetector 等共用口径） */
    public static int declaredCount(DeviceData data, String root) {
        return enumerate(data, root).size();
    }

    /** 组装单个网口条目：状态/MAC/速率在两套子树里都可能存在，依次取值 */
    private static Map<String, Object> entry(String statusPrefix, int instance, DeviceData data) {
        String cfgPrefix = statusPrefix.contains("LANEthernetInterfaceConfig.")
                ? statusPrefix
                : statusPrefix.replace("LANEthernetInterface.", "LANEthernetInterfaceConfig.");
        String status = firstNonBlank(data.value(statusPrefix + "EthernetLinkStatus"),
                data.value(cfgPrefix + "EthernetLinkStatus"));
        String mac = firstNonBlank(data.value(cfgPrefix + "MACAddress"),
                data.value(statusPrefix + "MACAddress"));
        String speed = firstNonBlank(data.value(cfgPrefix + "MaxBitRate"),
                data.value(statusPrefix + "MaxBitRate"));
        String duplex = firstNonBlank(data.value(cfgPrefix + "DuplexMode"),
                data.value(statusPrefix + "DuplexMode"));
        String name = firstNonBlank(data.value(cfgPrefix + "Name"),
                data.value(statusPrefix + "Name"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", statusPrefix);
        out.put("instance", instance);
        out.put("name", name != null && !name.isBlank() ? name : "LAN" + instance);
        out.put("connected", "Up".equalsIgnoreCase(status));
        out.put("mac", mac);
        out.put("maxSpeedMbps", speed);
        out.put("duplex", duplex);
        return out;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b != null && !b.isBlank() ? b : null;
    }
}
