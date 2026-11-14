package com.tr069.acs.acs.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 连接请求（ACS 反连 CPE）安全防护：*/
@Slf4j
@Component
public class ConnectionRequestGuard {

    /** 派生密码的最短主密钥长度（字符） */
    private static final int MIN_SECRET_LENGTH = 16;

    /** 派生密码字节长度（截断到 18 字节再 Base64URL，约 24 个字符，主流光猫都能收） */
    private static final int DERIVED_BYTES = 18;

    /** 连接请求目标 CIDR 白名单，逗号分隔；留空 = 不限网段（仍拒绝受限地址） */
    @Value("${acs.device.capability.conn-req-allowed-cidrs:}")
    private String allowedCidrs;

    /** 派生密码主密钥：配置 ≥16 字符即启用一台一密；留空 = 沿用常规凭据链 */
    @Value("${acs.device.capability.conn-req-master-secret:}")
    private String masterSecret;

    /** 外呼前校验连接请求地址，不合规直接拒绝（抛 IllegalArgumentException） */
    public void validate(String url) {
        URI uri = URI.create(url == null ? "" : url.trim());
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("连接请求地址必须是 http/https: " + url);
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("连接请求地址不允许内嵌账号密码: " + url);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("连接请求地址缺少主机名: " + url);
        }
        if (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535)) {
            throw new IllegalArgumentException("连接请求地址端口非法: " + url);
        }
        List<InetAddress> addresses;
        try {
            addresses = List.of(InetAddress.getAllByName(host));
        } catch (Exception e) {
            throw new IllegalArgumentException("连接请求地址无法解析: " + url + ", " + e.getMessage());
        }
        List<Cidr> allowlist = parseCidrs(allowedCidrs);
        for (InetAddress ip : addresses) {
            // 回环/未指定(0.0.0.0)/组播/链路本地(169.254.)一律拒绝：这些目标只可能是 ACS 自己或内网基础设施
            if (ip.isLoopbackAddress() || ip.isAnyLocalAddress() || ip.isMulticastAddress()
                    || ip.isLinkLocalAddress()) {
                throw new IllegalArgumentException("连接请求地址指向受限地址: " + url + " -> " + ip);
            }
            if (!allowlist.isEmpty() && allowlist.stream().noneMatch(c -> c.matches(ip))) {
                throw new IllegalArgumentException("连接请求地址不在白名单网段内: " + url + " -> " + ip);
            }
        }
    }

    /** 派生模式是否启用（主密钥已配置且长度达标） */
    public boolean derivedCredentialsEnabled() {
        return masterSecret != null && masterSecret.trim().length() >= MIN_SECRET_LENGTH;
    }

    /** 派生模式下连接请求的用户名：即设备序列号 */
    public String deriveConnReqUsername(String sn) {
        return sn;
    }

    /**
     * 按序列号派生连接请求密码：Base64URL(HMAC-SHA256(masterSecret, sn)) 截断 18 字节。
     * 纳管下发时把该值写进设备 ManagementServer.ConnectionRequestPassword，
     * 反连时按同一算法算出，即实现一台一密。
     */
    public String deriveConnReqPassword(String sn) {
        if (!derivedCredentialsEnabled()) {
            throw new IllegalStateException("未配置连接请求主密钥"
                    + "（acs.device.capability.conn-req-master-secret），无法派生密码");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(masterSecret.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    "HmacSHA256"));
            byte[] digest = mac.doFinal(sn.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] truncated = new byte[DERIVED_BYTES];
            System.arraycopy(digest, 0, truncated, 0, DERIVED_BYTES);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(truncated);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 不可用", e);
        }
    }

    // ==================== CIDR 白名单 ====================

    /** 解析逗号分隔的 CIDR（也接受裸 IP），非法项记日志跳过 */
    private static List<Cidr> parseCidrs(String raw) {
        List<Cidr> result = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String item : raw.split(",")) {
            item = item.trim();
            if (item.isEmpty()) {
                continue;
            }
            try {
                result.add(Cidr.parse(item));
            } catch (Exception e) {
                log.warn("忽略非法的连接请求白名单网段: {}", item);
            }
        }
        return result;
    }

    /** CIDR 网段：同时支持 IPv4/IPv6，按字节前缀比较 */
    private record Cidr(byte[] network, int prefixBits) {

        static Cidr parse(String raw) throws java.net.UnknownHostException {
            int slash = raw.indexOf('/');
            String ipPart = slash < 0 ? raw : raw.substring(0, slash);
            byte[] network = InetAddress.getByName(ipPart).getAddress();
            int prefix = slash < 0 ? network.length * 8 : Integer.parseInt(raw.substring(slash + 1));
            if (prefix < 0 || prefix > network.length * 8) {
                throw new IllegalArgumentException("CIDR 前缀长度非法: " + raw);
            }
            // 主机位清零，统一成网络地址再比较
            int fullBytes = prefix / 8;
            int rem = prefix % 8;
            if (rem > 0 && fullBytes < network.length) {
                int mask = 0xFF << (8 - rem);
                network[fullBytes] = (byte) (network[fullBytes] & mask);
            }
            for (int i = fullBytes + (rem > 0 ? 1 : 0); i < network.length; i++) {
                network[i] = 0;
            }
            return new Cidr(network, prefix);
        }

        boolean matches(InetAddress ip) {
            byte[] bytes = ip.getAddress();
            if (bytes.length != network.length) {
                return false;
            }
            int fullBytes = prefixBits / 8;
            int rem = prefixBits % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (bytes[i] != network[i]) {
                    return false;
                }
            }
            if (rem > 0 && fullBytes < network.length) {
                int mask = 0xFF << (8 - rem);
                if ((bytes[fullBytes] & mask) != (network[fullBytes] & mask)) {
                    return false;
                }
            }
            return true;
        }
    }
}
