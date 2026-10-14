package com.tr069.acs.common.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JWT 密钥文件的读写。
 *
 * <p>关键是「同一份文件两进程拿到同一个密钥」，否则重启后已签发的令牌全部失效。</p>
 */
class JwtSecretProviderTest {

    @TempDir
    Path tempDir;

    /**
     * 首次生成、后续复用：两次实例化必须拿到同一个密钥。
     */
    @Test
    void generatesOnceAndReusesFile() throws Exception {
        Path file = tempDir.resolve("target").resolve(JwtSecretProvider.SECRET_FILE);

        JwtSecretProvider first = new JwtSecretProvider(file, "");
        JwtSecretProvider second = new JwtSecretProvider(file, "");

        assertThat(Files.exists(file)).isTrue();
        assertThat(Files.readString(file, StandardCharsets.UTF_8))
                .contains("<secret>").contains("</jwt>");
        assertThat(second.getKey().getEncoded()).isEqualTo(first.getKey().getEncoded());
        // HS256 要求密钥不短于 32 字节
        assertThat(first.getKey().getEncoded()).hasSizeGreaterThanOrEqualTo(32);
    }

    /**
     * 显式给出密钥时不碰文件，也不生成校验不到的东西。
     */
    @Test
    void prefersExplicitSecret() {
        String explicit = "abcdefghijklmnopqrstuvwxyz012345";

        JwtSecretProvider provider = new JwtSecretProvider(
                tempDir.resolve("secret.xml"), explicit);

        assertThat(provider.getKey().getEncoded()).isEqualTo(explicit.getBytes(StandardCharsets.UTF_8));
        assertThat(Files.exists(tempDir.resolve("secret.xml"))).isFalse();
    }
}
