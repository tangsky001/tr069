package com.tr069.acs.acs.service;

import com.tr069.acs.domain.entity.CpeFile;
import com.tr069.acs.mapper.CpeFileMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 文件存储：落盘 + 算摘要 + 登记台账，设备从 {@code /files/{id}} 取。
 *
 * <p>存的路径不给设备，设备只认 {@code /files/{id}} 这一个地址，
 * 这样文件挪位置、换目录都不影响已经下发的下载任务。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CpeFileService {

    /** 文件存放根目录，相对工作目录。 */
    @Value("${acs.file.dir:files}")
    private String fileDir;

    /**
     * 设备取文件用的地址前缀。
     *
     * <p>留空时按 {@code public-host} 与上报端口拼。<b>ACS 的 public-host 与设备在不在同一个网段
     * 是两回事</b>，现场地址不同就把这里配成设备真正能访问到的那个地址。</p>
     */
    @Value("${acs.file.base-url:}")
    private String baseUrl;

    @Value("${acs.public-host:127.0.0.1}")
    private String publicHost;

    @Value("${acs.cwmp.listen-ports:9090}")
    private String listenPorts;

    private final CpeFileMapper fileMapper;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final int BUFFER = 8192;

    /**
     * 保存文件并登记台账。
     *
     * @return 台账记录，含落盘路径与内容摘要
     */
    public CpeFile save(String originalName, String fileType, String version, String remark,
                        InputStream in) throws IOException {
        Path dir = Paths.get(fileDir).resolve(LocalDate.now().format(DAY));
        Files.createDirectories(dir);
        String name = safeName(originalName);
        Path target = dir.resolve(UUID.randomUUID().toString().replace("-", "") + "_" + name);

        MessageDigest digest = digest();
        long size = 0;
        try (InputStream input = in; OutputStream out = Files.newOutputStream(target)) {
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = input.read(buf)) > 0) {
                digest.update(buf, 0, n);
                out.write(buf, 0, n);
                size += n;
            }
        }

        CpeFile file = new CpeFile();
        file.setName(name);
        file.setFileType(normalizeType(fileType));
        file.setVersion(blankToNull(version));
        file.setSize(size);
        file.setSha256(hex(digest.digest()));
        file.setStorePath(relativize(target));
        file.setRemark(blankToNull(remark));
        fileMapper.insert(file);
        log.info("文件已保存: id={}, name={}, size={}", file.getId(), name, size);
        return file;
    }

    /** 台账记录对应的磁盘文件；文件不在时返回 null。 */
    public Path pathOf(CpeFile file) {
        if (file == null || file.getStorePath() == null) {
            return null;
        }
        return Paths.get(fileDir).resolve(file.getStorePath()).normalize();
    }

    /**
     * 设备取这个文件用的完整地址。
     */
    public String urlOf(Long id) {
        return publicUrl() + "/files/" + id;
    }

    /** 删除台账并清掉磁盘文件；文件已经不在了也照样删台账。 */
    public void delete(CpeFile file) {
        if (file == null) {
            return;
        }
        Path path = pathOf(file);
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                log.warn("删除磁盘文件失败: id={}, err={}", file.getId(), e.getMessage());
            }
        }
        fileMapper.deleteById(file.getId());
    }

    /** 设备可达的地址前缀：优先用现场配的 base-url。 */
    private String publicUrl() {
        if (baseUrl != null && !baseUrl.isBlank()) {
            return trimEnd(baseUrl.trim());
        }
        String host = publicHost == null || publicHost.isBlank() ? "127.0.0.1" : publicHost.trim();
        String prefix = host.contains("://") ? host : "http://" + host;
        // 文件口跟着上报口走：设备必然能连上上报口，另开端口反而可能不通
        return trimEnd(prefix + ":" + firstPort());
    }

    private String firstPort() {
        if (listenPorts == null || listenPorts.isBlank()) {
            return "9090";
        }
        for (String part : listenPorts.split("[,;\\s]+")) {
            if (!part.isBlank()) {
                return part.trim();
            }
        }
        return "9090";
    }

    /** 文件名只留可用字符：路径分隔符会把文件写到别的目录去。 */
    private static String safeName(String original) {
        String name = original == null || original.isBlank() ? "unnamed" : original.trim();
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        return name.length() > 128 ? name.substring(name.length() - 128) : name;
    }

    private static String normalizeType(String fileType) {
        if (fileType == null || fileType.isBlank()) {
            return "FIRMWARE";
        }
        return fileType.trim().toUpperCase();
    }

    private String relativize(Path target) {
        String root = Paths.get(fileDir).toAbsolutePath().normalize().toString();
        String full = target.toAbsolutePath().normalize().toString();
        return full.startsWith(root) ? full.substring(root.length()).replace('\\', '/') : full;
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static String trimEnd(String url) {
        String text = url;
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("没有 SHA-256 实现", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
