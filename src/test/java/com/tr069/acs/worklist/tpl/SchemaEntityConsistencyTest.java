package com.tr069.acs.worklist.tpl;

import com.tr069.acs.worklist.tpl.entity.Bridging;
import com.tr069.acs.worklist.tpl.entity.Device;
import com.tr069.acs.worklist.tpl.entity.DeviceLan;
import com.tr069.acs.worklist.tpl.entity.DeviceWan;
import com.tr069.acs.worklist.tpl.entity.Sip;
import com.tr069.acs.worklist.tpl.entity.Vlan;
import com.tr069.acs.worklist.tpl.entity.WlanA;
import com.tr069.acs.worklist.tpl.entity.WlanB;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 表结构与实体字段的一致性。
 *
 * <p>存在意义：DAO 是手写 SQL，漏写某个列的读写时编译不会报错，只有跑起来才发现
 * 「库里有这列、实体没这字段」（或反之），数据就静默丢了一部分。这里用
 * {@code PRAGMA table_info} 直接跟反射出来的实体字段对账，任何一边多出来都让测试变红。</p>
 *
 * <p>已捕获过的真实缺陷：{@code bridging} 表有 path_conn_type / val_conn_type /
 * path_nat_enabled / val_nat_enabled 四列，实体里却漏了，导致桥接下发的类型与
 * NAT 开关写不进去。</p>
 */
class SchemaEntityConsistencyTest {

    @TempDir(cleanup = CleanupMode.NEVER)
    static Path tmp;

    static JdbcTemplate jdbc;

    private static final Map<String, Class<?>> TABLE_ENTITY = new LinkedHashMap<>();

    static {
        TABLE_ENTITY.put("device", Device.class);
        TABLE_ENTITY.put("wlan_a", WlanA.class);
        TABLE_ENTITY.put("wlan_b", WlanB.class);
        TABLE_ENTITY.put("sip", Sip.class);
        TABLE_ENTITY.put("device_wan", DeviceWan.class);
        TABLE_ENTITY.put("vlan", Vlan.class);
        TABLE_ENTITY.put("device_lan", DeviceLan.class);
        TABLE_ENTITY.put("bridging", Bridging.class);
    }

    @BeforeAll
    static void setup() throws Exception {
        Path db = tmp.resolve("consistency.pack");
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + db);
        DataSource source = ds;
        ScriptUtils.executeSqlScript(source.getConnection(),
                new ClassPathResource("db/schema-tr069tpl.sql"));
        jdbc = new JdbcTemplate(source);
    }

    @Test
    void everyTableColumnHasMatchingEntityField() {
        List<String> missing = new ArrayList<>();
        TABLE_ENTITY.forEach((table, clazz) -> {
            Set<String> fields = entityFields(clazz);
            List<String> columns = columnsOf(table);
            for (String col : columns) {
                String camel = toCamel(col);
                if (!fields.contains(camel)) {
                    missing.add(String.format("%-12s 列 %-22s 在 %s 里没有对应字段（应有 %s）",
                            table, col, clazz.getSimpleName(), camel));
                }
            }
        });
        assertTrue(missing.isEmpty(),
                "下列表列在实体里缺失，DAO 读写会把它们漏掉：\n" + String.join("\n", missing));
    }

    @Test
    void everyEntityFieldHasMatchingTableColumn() {
        List<String> orphans = new ArrayList<>();
        TABLE_ENTITY.forEach((table, clazz) -> {
            List<String> columns = columnsOf(table);
            for (String field : entityFields(clazz)) {
                // id 在两张 vlan / bridging 里参与判重，实体必然有，跳过无意义的静态常量
                String col = toSnake(field);
                if (!columns.contains(col)) {
                    orphans.add(String.format("%-12s 实体字段 %-22s 在表里没有对应列（应有 %s）",
                            table, field, col));
                }
            }
        });
        assertTrue(orphans.isEmpty(),
                "下列实体字段在表里没有对应列，写了也存不进去：\n" + String.join("\n", orphans));
    }

    private static List<String> columnsOf(String table) {
        return jdbc.queryForList("SELECT name FROM pragma_table_info(?) ORDER BY cid", String.class, table);
    }

    private static Set<String> entityFields(Class<?> clazz) {
        return Arrays.stream(clazz.getDeclaredFields())
                .filter(f -> !java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                .map(Field::getName)
                .collect(Collectors.toSet());
    }

    /** device_id → deviceId；path_8021p → path8021p（数字前的下划线也要吃掉） */
    private static String toCamel(String snake) {
        StringBuilder sb = new StringBuilder();
        boolean upperNext = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                upperNext = true;
            } else if (upperNext) {
                sb.append(Character.toUpperCase(c));
                upperNext = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** bridgeInstance → bridge_instance；path8021p → path_8021p */
    private static String toSnake(String camel) {
        StringBuilder sb = new StringBuilder();
        for (char c : camel.toCharArray()) {
            if (Character.isUpperCase(c)) {
                sb.append('_').append(Character.toLowerCase(c));
            } else if (Character.isDigit(c) && !sb.isEmpty()
                    && Character.isLetter(sb.charAt(sb.length() - 1))) {
                // path8021p → path_8021p：字母紧跟数字时补下划线
                sb.append('_').append(c);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
