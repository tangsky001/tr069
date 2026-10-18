package com.tr069.acs.worklist.tpl;

import com.tr069.acs.worklist.tpl.dao.DeviceDao;
import com.tr069.acs.worklist.tpl.entity.Device;
import com.tr069.acs.worklist.tpl.util.SnowflakeIdGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 模板库阶段 1 烟雾测试。不加载 Spring，直接 sqlite-jdbc + 临时文件，
 * 跑完即丢，避免污染项目的 application-tr069tpl 库。
 */
class TemplatePackSmokeTest {

    static {
        SqliteNativeLoader.ensureLoaded();
    }

    @TempDir(cleanup = CleanupMode.NEVER)
    static Path tmp;

    static DataSource ds;
    static JdbcTemplate jdbc;
    static DeviceDao dao;
    static SnowflakeIdGenerator snowflake;

    @BeforeAll
    static void setup() throws Exception {
        Path db = tmp.resolve("test.pack");
        SQLiteDataSource src = new SQLiteDataSource();
        src.setUrl("jdbc:sqlite:" + db);
        ds = src;
        ScriptUtils.executeSqlScript(ds.getConnection(),
                new ClassPathResource("db/schema-tr069tpl.sql"));
        jdbc = new JdbcTemplate(ds);
        snowflake = new SnowflakeIdGenerator(1L);
        dao = new DeviceDao(jdbc, snowflake);
    }

    private static Device sample(String isp, String modelKey, String version) {
        Device d = new Device();
        d.setIsp(isp);
        d.setModelKey(modelKey);
        d.setSoftwareVersion(version);
        return d;
    }

    @Test
    void eightTablesCreated() {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN " +
                        "('device','wlan_a','wlan_b','sip','device_wan','vlan','device_lan','bridging')",
                Integer.class);
        assertEquals(8, c.intValue(), "8 张业务表都应建好");
    }

    @Test
    void deviceUpsertAndPointQuery() {
        Device d = sample("cmcc", "P22g-CIac", "V1.0.00.488");
        d.setManufacturer("zte");
        d.setProductClass("P22g-CIac");
        d.setCapWifiSpec("2.4G+5.8G");
        dao.upsert(d);
        assertNotNull(d.getId(), "upsert 后应回填 id");

        Optional<Device> found = dao.findByIspModelKey("cmcc", "P22g-CIac");
        assertTrue(found.isPresent());
        assertEquals(d.getId(), found.get().getId());
        assertEquals("zte", found.get().getManufacturer());
        // 申报能力决定各业务表要不要建记录，必须能回读
        assertEquals("2.4G+5.8G", found.get().getCapWifiSpec());
    }

    @Test
    void differentIspSameModelNotConflict() {
        Device cmcc = sample("cmcc", "X", "1");
        Device ct = sample("ct", "X", "1");
        dao.upsert(cmcc);
        dao.upsert(ct);
        assertNotEquals(cmcc.getId(), ct.getId(), "同型号不同 isp 是两条记录");
        assertTrue(dao.findByIspModelKey("cmcc", "X").isPresent());
        assertTrue(dao.findByIspModelKey("ct", "X").isPresent());
    }

    @Test
    void upsertIdempotentOnSecondCall() {
        Device d = sample("cu", "Y", "2");
        dao.upsert(d);
        long id1 = d.getId();
        // 第二次 upsert 改了申报能力，应走 UPDATE 分支更新同一行
        d.setCapWifiSpec("2.4G");
        dao.upsert(d);
        assertEquals(id1, d.getId(), "同 (isp, model_key, version) 二次 upsert 应走 UPDATE，id 不变");
        assertEquals("2.4G", dao.findByIspModelKey("cu", "Y").get().getCapWifiSpec());
    }

    @Test
    void wlanAInsertQueryAndNoPlaintext() {
        Device dev = sample("cmcc", "P22g-CIac", "V1.0.00.488");
        dao.upsert(dev);

        // 写 wlan_a 实例 1：path 是真实路径，val 必须用变量
        long wid = snowflake.nextId();
        jdbc.update("INSERT INTO wlan_a(id, device_id, instance_no, action, verified, " +
                        "path_ssid, val_ssid, path_key, val_key) VALUES(?,?,?,?,?,?,?,?,?)",
                wid, dev.getId(), 1, "SET", "VERIFIED",
                "InternetGatewayDevice.LANDevice.1.WLANConfiguration.1.SSID", "%room%",
                "InternetGatewayDevice.LANDevice.1.WLANConfiguration.1.PreSharedKey.1.KeyPassphrase", "%wifi_pwd%");

        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM wlan_a WHERE device_id=? AND instance_no=?",
                Integer.class, dev.getId(), 1);
        assertEquals(1, c.intValue());

        // 库中无明文口令——val_key 必须是变量格式，绝不能直接放 88888888
        String valKey = jdbc.queryForObject("SELECT val_key FROM wlan_a WHERE id=?", String.class, wid);
        assertEquals("%wifi_pwd%", valKey, "val_key 必须是变量，不能是明文密码");

        String valSsid = jdbc.queryForObject("SELECT val_ssid FROM wlan_a WHERE id=?", String.class, wid);
        assertEquals("%room%", valSsid);
    }

    @Test
    void bridgingDefaultIsAdd() {
        // 桥接实例默认是需要 AddObject 创建的，桥接下发必须能表达这一步
        Device dev = sample("cmcc", "P22g-CIac", "V1.0.00.488");
        dao.upsert(dev);

        long bid = snowflake.nextId();
        jdbc.update("INSERT INTO bridging(id, device_id, bridge_instance, port_instance, bind_lan_no) " +
                "VALUES(?,?,?,?,?)", bid, dev.getId(), 2, 1, 1);

        String action = jdbc.queryForObject("SELECT action FROM bridging WHERE id=?", String.class, bid);
        assertEquals("ADD", action, "bridging 默认 action 应为 ADD（实例需先创建）");

        String verified = jdbc.queryForObject("SELECT verified FROM bridging WHERE id=?", String.class, bid);
        assertEquals("UNVERIFIED", verified, "未探测验证前应保持 UNVERIFIED");
    }
}
