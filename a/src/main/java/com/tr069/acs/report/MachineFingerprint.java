package com.tr069.acs.report;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 本机唯一标识，上报时用它区分是哪一套 ACS。指纹只算一次并缓存 */
public final class MachineFingerprint {

    private static final Logger log = LoggerFactory.getLogger(MachineFingerprint.class);

    /** deviceId 前缀 */
    public static final String DEVICE_ID_PREFIX = "ACS-";

    /** Linux 的机器号文件 */
    private static final List<String> LINUX_MACHINE_ID_FILES =
            List.of("/var/lib/dbus/machine-id", "/etc/machine-id");

    private static volatile String cached;

    private MachineFingerprint() {
    }

    /** 本实例 deviceId，形如 ACS-<32位十六进制> */
    public static String deviceId() {
        if (cached == null) {
            synchronized (MachineFingerprint.class) {
                if (cached == null) {
                    cached = DEVICE_ID_PREFIX + fingerprint();
                }
            }
        }
        return cached;
    }

    /** 每项因子各自 MD5，拼接后再做一次 MD5 转大写 */
    private static String fingerprint() {
        String combined = md5(systemId()) + md5(macAddress()) + md5(osFingerprint());
        String value = md5(combined).toUpperCase();
        log.debug("机器指纹已生成: {}", value);
        return value;
    }

    /** 系统机器号：Linux 读 machine-id 文件，Windows 读注册表 MachineGuid */
    private static String systemId() {
        for (String path : LINUX_MACHINE_ID_FILES) {
            File file = new File(path);
            if (!file.canRead()) {
                continue;
            }
            try {
                String line = Files.readAllLines(file.toPath()).stream()
                        .filter(s -> !s.isBlank())
                        .findFirst()
                        .orElse(null);
                if (line != null) {
                    return line.trim();
                }
            } catch (IOException ignored) {
            }
        }
        if (isWindows()) {
            String guid = readWindowsMachineGuid();
            if (guid != null) {
                return guid;
            }
        }
        return "";
    }

    /** Windows 注册表 MachineGuid，读不到返回 null */
    private static String readWindowsMachineGuid() {
        try {
            Process process = new ProcessBuilder("reg", "query",
                    "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid")
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            try (java.io.InputStream in = process.getInputStream()) {
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                for (String line : text.split("\\R")) {
                    if (line.contains("MachineGuid")) {
                        String[] parts = line.trim().split("\\s+");
                        return parts.length == 0 ? null : parts[parts.length - 1].trim();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 第一块 MAC 非全零的网卡地址 */
    private static String macAddress() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces == null) {
                return "";
            }
            List<NetworkInterface> list = new ArrayList<>();
            while (ifaces.hasMoreElements()) {
                list.add(ifaces.nextElement());
            }
            Collections.sort(list, Comparator.comparing(NetworkInterface::getName));
            for (NetworkInterface iface : list) {
                byte[] mac = iface.getHardwareAddress();
                if (mac == null || mac.length == 0) {
                    continue;
                }
                boolean allZero = true;
                StringBuilder sb = new StringBuilder();
                for (byte b : mac) {
                    allZero = allZero && b == 0;
                    sb.append(String.format("%02x", b));
                }
                if (!allZero) {
                    return sb.toString();
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /** 操作系统名称、版本、架构与 CPU 核数 */
    private static String osFingerprint() {
        return System.getProperty("os.name", "")
                + "|" + System.getProperty("os.version", "")
                + "|" + System.getProperty("os.arch", "")
                + "|" + Runtime.getRuntime().availableProcessors();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static String md5(String text) {
        String source = text == null ? "" : text;
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.format("%08x", source.hashCode());
        }
    }
}
