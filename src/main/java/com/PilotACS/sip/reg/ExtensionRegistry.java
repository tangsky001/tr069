/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.sip.reg;

import com.PilotACS.common.log.Logs;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 分机注册表：分机配置缓存（来自 sys_sip_extension 表）+ 注册登记/过期清理 */
@Component
public class ExtensionRegistry {

    private final SipExtensionMapper mapper;
    /** 分机配置缓存（分机号 → 配置） */
    private final Map<String, SipExtension> config = new ConcurrentHashMap<>();
    private final Map<String, Registration> registrations = new ConcurrentHashMap<>();

    public ExtensionRegistry(SipExtensionMapper mapper) {
        this.mapper = mapper;
    }

    /** 启动时从库加载分机 */
    @PostConstruct
    public void load() {
        reload();
    }

    /** 重新加载分机配置（增删改后调用） */
    public void reload() {
        Map<String, SipExtension> fresh = new ConcurrentHashMap<>();
        try {
            for (SipExtension e : mapper.selectList(null)) {
                fresh.put(e.getExtension(), e);
            }
        } catch (Exception e) {
            Logs.error(Logs.Module.SIP, "分机配置加载失败", "原因", e.getMessage());
        }
        synchronized (config) {
            config.clear();
            config.putAll(fresh);
        }
        Logs.info(Logs.Module.SIP, "分机配置已加载", "数量", fresh.size());
    }

    /** 分机号是否存在配置 */
    public boolean isConfigured(String ext) {
        return ext != null && config.containsKey(ext);
    }

    /** 分机密码（鉴权用） */
    public String passwordOf(String ext) {
        SipExtension e = ext == null ? null : config.get(ext);
        return e == null ? null : e.getPassword();
    }

    /** 配置的分机号列表 */
    public List<String> configuredExtensions() {
        synchronized (config) {
            return new ArrayList<>(config.keySet());
        }
    }

    /** 全部分机配置 */
    public Collection<SipExtension> allConfig() {
        synchronized (config) {
            return new ArrayList<>(config.values());
        }
    }

    public void register(String ext, InetSocketAddress address, String contact, String userAgent, int expiresSeconds) {
        Registration reg = registrations.computeIfAbsent(ext, Registration::new);
        reg.setAddress(address);
        reg.setContact(contact);
        reg.setUserAgent(userAgent == null ? "" : userAgent);
        reg.setExpiresAt(System.currentTimeMillis() + expiresSeconds * 1000L);
        Logs.info(Logs.Module.SIP, "分机注册", "分机", ext, "地址", address, "有效期", expiresSeconds + "s");
    }

    public void unregister(String ext) {
        Registration reg = registrations.remove(ext);
        if (reg != null) {
            Logs.info(Logs.Module.SIP, "分机注销", "分机", ext);
        }
    }

    /** 存活注册条目 */
    public Registration findAlive(String ext) {
        Registration reg = registrations.get(ext);
        return reg != null && reg.isAlive() ? reg : null;
    }

    public List<Registration> all() {
        return new ArrayList<>(registrations.values());
    }

    /** 清理过期注册 */
    public void sweepExpired() {
        long now = System.currentTimeMillis();
        registrations.entrySet().removeIf(e -> e.getValue().getExpiresAt() < now);
    }
}
