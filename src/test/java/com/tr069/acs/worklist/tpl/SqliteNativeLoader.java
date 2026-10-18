package com.tr069.acs.worklist.tpl;

import org.sqlite.SQLiteDataSource;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * sqlite-jdbc 在部分 Windows + JDK 21 环境无法自动提取并加载 native DLL，
 * 导致打开 SQLite 连接时报 {@code NativeLibraryNotFoundException}。
 *
 * <p>测试启动前从 sqlite-jdbc jar 里把 dll 抽到临时文件，并通过
 * {@code org.sqlite.lib.path} 告知驱动使用它。非 Windows/x86_64 环境跳过。</p>
 */
public class SqliteNativeLoader {

    private static final String LOADED_FLAG = "sqlite-native-loaded";

    public static synchronized void ensureLoaded() {
        if (System.getProperties().containsKey(LOADED_FLAG)) {
            return;
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (!os.contains("win") || !"amd64".equals(arch)) {
            System.setProperty(LOADED_FLAG, "skipped");
            return;
        }
        try {
            String resource = "/org/sqlite/native/Windows/x86_64/sqlitejdbc.dll";
            URL url = SQLiteDataSource.class.getResource(resource);
            if (url == null) {
                return;
            }
            Path tmp = Files.createTempFile("sqlite-junit-", ".dll");
            tmp.toFile().deleteOnExit();
            try (InputStream in = url.openStream()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            System.setProperty("org.sqlite.lib.path", tmp.toAbsolutePath().toString());
            System.setProperty(LOADED_FLAG, "true");
        } catch (Exception e) {
            // 失败时让驱动走原来的加载逻辑，不阻塞测试
        }
    }
}
