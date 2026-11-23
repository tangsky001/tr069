/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.sip.h248;

import com.PilotACS.common.log.Logs;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** H.248 网关注册表：ServiceChange Restart 即登记 */
@Component
public class GatewayRegistry {

    /** 网关条目 */
    public static class Gateway {
        public final String mgId;
        public volatile InetSocketAddress address;
        public volatile long registeredAt;
        public volatile String version = "";

        public Gateway(String mgId) {
            this.mgId = mgId;
        }

        public boolean isAlive() {
            return address != null;
        }
    }

    /** 网关地址（ip:port）→ 网关 */
    private final Map<String, Gateway> gateways = new ConcurrentHashMap<>();

    /** ServiceChange（Restart/Handoff）登记或刷新 */
    public Gateway register(InetSocketAddress address, String version) {
        String key = address.getAddress().getHostAddress() + ":" + address.getPort();
        Gateway gw = gateways.computeIfAbsent(key, Gateway::new);
        gw.address = address;
        gw.registeredAt = System.currentTimeMillis();
        if (version != null && !version.isBlank()) {
            gw.version = version;
        }
        Logs.info(Logs.Module.SIP, "H.248 网关注册", "网关", key, "版本", version == null ? "" : version);
        return gw;
    }

    public Gateway findAlive(String hostPort) {
        Gateway gw = gateways.get(hostPort);
        return gw != null && gw.isAlive() ? gw : null;
    }

    /** 任一存活网关（终结点未指定网关 IP 时使用） */
    public Gateway firstAlive() {
        for (Gateway gw : gateways.values()) {
            if (gw.isAlive()) {
                return gw;
            }
        }
        return null;
    }

    /** 按网关 IP 查（H.248 术语端口可能与注册端口不同，按 IP 匹配第一个存活网关） */
    public Gateway findByIp(String ip) {
        for (Gateway gw : gateways.values()) {
            if (gw.isAlive() && gw.address.getAddress().getHostAddress().equals(ip)) {
                return gw;
            }
        }
        return null;
    }

    public List<Gateway> all() {
        return new ArrayList<>(gateways.values());
    }
}
