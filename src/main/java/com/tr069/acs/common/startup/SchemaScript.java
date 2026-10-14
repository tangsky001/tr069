package com.tr069.acs.common.startup;

import org.springframework.core.io.ClassPathResource;
import org.springframework.util.FileCopyUtils;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 建表脚本加载。
 *
 * <p>全库只有一份脚本：{@code sql/schema.sql}（构建时被复制到 classpath，见 pom.xml 的
 * resources 配置）。各模块的补建组件在这里按表名挑出自己那几张表的 CREATE 语句，
 * 不再各自维护一份「同一张表的另一个副本」——副本一定会漂移，最终线上结构取决于运气。</p>
 *
 * <p>之所以按表名挑而不是整份执行：每个模块有自己的 {@code acs.<module>.auto-create-table}
 * 开关，整份执行会让关掉的模块也被别人顺手建出来。</p>
 */
public final class SchemaScript {

    /** classpath 中的脚本位置，对应仓库根目录的 sql/schema.sql。 */
    public static final String RESOURCE = "db/schema.sql";

    /** 从 CREATE TABLE [IF NOT EXISTS] `xxx` 里取表名。 */
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?i)\\bcreate\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?[`\"']?([A-Za-z0-9_]+)[`\"']?");

    private SchemaScript() {
    }

    /**
     * 取这几张表的 CREATE 语句。
     *
     * @param tables 模块负责的表名
     * @throws IllegalStateException 脚本里缺了某张表的定义（脚本与代码不同步，必须响亮地失败）
     */
    public static List<String> createStatements(Collection<String> tables) {
        Map<String, String> byTable = new LinkedHashMap<>();
        for (String statement : loadStatements()) {
            Matcher matcher = CREATE_TABLE.matcher(statement);
            if (matcher.find()) {
                byTable.put(matcher.group(1).toLowerCase(Locale.ROOT), statement);
            }
        }
        List<String> statements = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String table : tables) {
            String sql = byTable.get(table.toLowerCase(Locale.ROOT));
            if (sql == null) {
                missing.add(table);
            } else {
                statements.add(sql);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "建表脚本 " + RESOURCE + " 中缺少这些表的定义: " + missing + "，请检查 sql/schema.sql 是否漏改");
        }
        return statements;
    }

    /**
     * 读脚本并按分号拆成单条语句，跳过注释行。
     */
    private static List<String> loadStatements() {
        String script;
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            script = FileCopyUtils.copyToString(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("读取建表脚本失败: classpath:" + RESOURCE, e);
        }
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : script.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }
            current.append(line).append('\n');
            if (trimmed.endsWith(";")) {
                statements.add(current.toString().trim());
                current.setLength(0);
            }
        }
        return statements;
    }
}
