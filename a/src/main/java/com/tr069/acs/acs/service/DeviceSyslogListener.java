package com.tr069.acs.acs.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 设备侧 syslog UDP 采集（FreeACS syslog 模块的精简版）：
 * 设备把 syslog 服务器指向 ACS 的 UDP 端口即可，重启/注册失败/WAN 断开这些设备侧事件
 * 原文落 cpe_device_syslog 表，报障时按 SN 一查就有现场。
 * SN 从报文里尽力识别（SN=xxx / sn:xxx 等常见格式），识别不到就留空记原文，
 * 不丢数据；不做正则规则引擎和触发器——那套等真有运营需求再加。
 */
@Slf4j
@Component
public class DeviceSyslogListener {

    /** 报文里识别 SN 的常见格式 */
    private static final Pattern SN_PATTERN =
            Pattern.compile("(?i)(?:sn|serial[._-]?number|equipment[._-]?id)\\s*[:=]\\s*([A-Za-z0-9._-]{4,64})");

    private final DeviceEventRecorder recorder;

    @Value("${acs.syslog.listen-port:1514}")
    private int listenPort;

    @Value("${acs.syslog.enabled:true}")
    private boolean enabled;

    private java.net.DatagramSocket socket;
    private Thread worker;
    private volatile boolean running = false;

    /** 无 SN 关联的杂散报文限流：每分钟最多记这么多，防止未知设备灌表 */
    private static final int UNMATCHED_LIMIT_PER_MINUTE = 60;
    private final java.util.concurrent.atomic.AtomicLong unmatchedThisMinute =
            new java.util.concurrent.atomic.AtomicLong();
    private volatile long minuteMark = System.currentTimeMillis();

    public DeviceSyslogListener(DeviceEventRecorder recorder) {
        this.recorder = recorder;
    }

    @PostConstruct
    public void start() {
        if (!enabled) {
            log.info("设备 syslog 采集未启用 (acs.syslog.enabled=false)");
            return;
        }
        try {
            socket = new java.net.DatagramSocket(listenPort);
            running = true;
            worker = new Thread(this::loop, "device-syslog-listener");
            worker.setDaemon(true);
            worker.start();
            log.info("设备 syslog UDP 采集已启动: 端口={}（设备侧 syslog 服务器指到本机这个端口即可）", listenPort);
        } catch (Exception e) {
            log.warn("设备 syslog UDP 监听启动失败（不影响其他功能）: 端口={}, err={}", listenPort, e.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }

    private void loop() {
        byte[] buf = new byte[4096];
        java.net.DatagramPacket packet = new java.net.DatagramPacket(buf, buf.length);
        while (running) {
            try {
                socket.receive(packet);
                handle(decode(packet.getData(), packet.getLength()),
                        packet.getAddress().getHostAddress());
            } catch (java.net.SocketException closed) {
                break;
            } catch (Exception e) {
                log.debug("syslog 报文处理异常: {}", e.getMessage());
            }
        }
    }

    private void handle(String message, String sourceIp) {
        if (message == null || message.isBlank()) {
            return;
        }
        String sn = null;
        Matcher m = SN_PATTERN.matcher(message);
        if (m.find()) {
            sn = m.group(1).trim();
        }
        if (sn == null) {
            // 无 SN 的杂散报文限流，防未知来源灌表
            long now = System.currentTimeMillis();
            if (now - minuteMark >= 60_000L) {
                minuteMark = now;
                unmatchedThisMinute.set(0);
            }
            if (unmatchedThisMinute.incrementAndGet() > UNMATCHED_LIMIT_PER_MINUTE) {
                return;
            }
        }
        recorder.recordSyslog(sn, sourceIp, message);
    }

    /** 光猫 syslog 常发 GBK 中文：UTF-8 解出来带替换符就换 GBK 重解 */
    private String decode(byte[] data, int len) {
        String utf8 = new String(data, 0, len, StandardCharsets.UTF_8);
        if (utf8.indexOf('\uFFFD') >= 0) {
            try {
                return new String(data, 0, len, Charset.forName("GBK"));
            } catch (Exception ignored) {
                // GBK 不可用就保留 UTF-8 结果
            }
        }
        return utf8;
    }
}
