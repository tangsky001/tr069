package com.tr069.acs.common.security;

import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.crypto.SecretKey;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

/** 管理端 JWT 密钥 */
@Slf4j
@Component
public class JwtSecretProvider {

    /** 缺省密钥文件名 */
    public static final String SECRET_FILE = "secret.xml";

    /** 密钥文件中承载密钥的元素名 */
    private static final String SECRET_ELEMENT = "secret";

    /** 允许用启动参数换一个密钥文件位置 */
    private static final String FILE_PROPERTY = "jwt.secret.file";

    /** HS256 要求密钥不短于 32 字节 */
    private static final int SECRET_BYTES = 32;

    private final SecretKey key;

    /** Spring 唯一应使用的构造函数 */
    @Autowired
    public JwtSecretProvider(@Value("${admin.jwt.secret:}") String configured) {
        this(resolveSecretFile(), configured);
    }

    /** 测试专用：指定密钥文件位置，避免污染部署目录 */
    JwtSecretProvider(Path file, String configured) {
        this.key = Keys.hmacShaKeyFor(secretOf(file.toAbsolutePath().normalize(), configured)
                .getBytes(StandardCharsets.UTF_8));
    }

    /** 签名密钥 */
    public SecretKey getKey() {
        return key;
    }

    /** 缺省位置：进程工作目录下的 {@code target/secret.xml}，与 target/configuration 同一层 */
    private static Path resolveSecretFile() {
        String explicit = System.getProperty(FILE_PROPERTY);
        if (explicit != null && !explicit.isBlank()) {
            return Path.of(explicit.trim());
        }
        return Path.of("target").resolve(SECRET_FILE);
    }

    private static String secretOf(Path file, String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        String existing = read(file);
        if (existing != null) {
            return existing;
        }
        String generated = generate();
        write(file, generated);
        log.warn("未找到 {}，已随机生成管理端 JWT 密钥写入该文件；请纳入备份，丢失后已签发的令牌会失效",
                file);
        return generated;
    }

    private static String generate() {
        byte[] bytes = new byte[SECRET_BYTES];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** 读取密钥文件；文件缺失、为空或格式不对都返回 null，由调用方重新生成 */
    private static String read(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            Document doc = safeBuilder().parse(file.toFile());
            NodeList nodes = doc.getElementsByTagName(SECRET_ELEMENT);
            if (nodes.getLength() == 0) {
                log.warn("密钥文件 {} 中没有 <{}> 元素", file, SECRET_ELEMENT);
                return null;
            }
            String secret = ((Element) nodes.item(0)).getTextContent();
            return secret == null || secret.trim().isEmpty() ? null : secret.trim();
        } catch (Exception e) {
            log.warn("读取密钥文件 {} 失败: {}", file, e.getMessage());
            return null;
        }
    }

    private static void write(Path file, String secret) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // 密钥是 Base64，不含需要转义的字符，直接拼接即可
            Files.writeString(file, "<jwt>\n  <" + SECRET_ELEMENT + ">" + secret
                    + "</" + SECRET_ELEMENT + ">\n</jwt>\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("写入密钥文件 {} 失败: {}", file, e.getMessage());
        }
    }

    /** 关掉外部实体解析：密钥文件虽小，也不能给 XXE 留口子 */
    private static DocumentBuilder safeBuilder() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        return builder;
    }
}
