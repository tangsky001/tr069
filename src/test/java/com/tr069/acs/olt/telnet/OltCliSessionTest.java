package com.tr069.acs.olt.telnet;

import com.tr069.acs.olt.entity.OltBoard;
import com.tr069.acs.olt.entity.OltDevice;
import com.tr069.acs.olt.model.OltOnt;
import com.tr069.acs.olt.model.OltPort;
import com.tr069.acs.olt.model.OltPortVlan;
import com.tr069.acs.olt.model.OltVlanInfo;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用一个本地假 OLT 验证 Telnet 登录与采集链路：
 * 选项协商 → 账号/密码提示符 → 命令回显 → 提示符识别。
 *
 * <p>不依赖真实设备，CI 里也能跑。</p>
 */
class OltCliSessionTest {

    private static final int IAC = 255;
    private static final int WILL = 251;
    private static final int DO = 253;

    @Test
    void shouldLoginAndCollectOverTelnet() throws Exception {
        try (FakeOltServer server = new FakeOltServer()) {
            // H901GPHF 型号表里没有端口数，用来覆盖「只有回显里出现过的端口」这条路径
            String boardType = "H901GPHF";
            server.setBoardType(boardType);
            OltDevice device = new OltDevice();
            device.setHost("127.0.0.1");
            device.setPort(server.getPort());
            device.setProtocol("TELNET");
            device.setUsername("root");
            device.setPassword("admin");
            device.setCharset("AUTO");

            OltSnapshot snapshot;
            try (OltCliSession session = OltCliSession.open(
                    device, StandardCharsets.UTF_8, 3000, 5000, 4000)) {
                snapshot = session.collect(true);
            }
            server.await();

            assertEquals(OltVendor.HUAWEI, snapshot.getVendor());
            assertEquals("MA5600T", snapshot.getModel());
            assertEquals("V800R018C10", snapshot.getSoftwareVersion());
            assertEquals(2, snapshot.getBoards().size());
            assertEquals(boardType, snapshot.getBoards().get(0).getBoardType());
            assertTrue(snapshot.getPrompt().contains("HUAWEI"));
            assertTrue(snapshot.getTranscript().contains("display board 0"));

            // ONT 数量来自 config 视图的 display ont info 0 <槽位> all，一条命令覆盖整块板
            List<OltPort> ports = snapshot.getBoards().get(0).getPorts();
            assertEquals(1, ports.size(), "型号没兜底出端口数时，只列回显里出现过的端口");
            assertEquals(Integer.valueOf(2), ports.get(0).getOnuTotal());
            assertEquals(Integer.valueOf(1), ports.get(0).getOnuOnline());
            // 每条 ONT 的明细也带回来：页面要逐个显示在线/离线，不只是「2 个」这个数
            List<OltOnt> onts = ports.get(0).getOnts();
            assertEquals(2, onts.size());
            assertTrue(onts.get(0).getOnline());
            assertFalse(onts.get(1).getOnline());
            assertTrue(snapshot.getTranscript().contains("display ont info 0 0 all"));
        }
    }

    /**
     * 第三方 OLT（中兴/烽火）用 show 系列：display version 只会回一句 Unknown command，
     * 认出 show 风格后板卡也走 show card，不再发 display board 0 去讨一句报错。
     */
    @Test
    void shouldUseShowCommandsForThirdPartyOlt() throws Exception {
        try (FakeOltServer server = new FakeOltServer()) {
            server.setShowStyle(true);
            OltDevice device = new OltDevice();
            device.setHost("127.0.0.1");
            device.setPort(server.getPort());
            device.setProtocol("TELNET");
            device.setUsername("root");
            device.setPassword("admin");

            OltSnapshot snapshot;
            try (OltCliSession session = OltCliSession.open(
                    device, StandardCharsets.UTF_8, 3000, 5000, 4000)) {
                snapshot = session.collect(true);
            }
            server.await();

            assertEquals(OltVendor.ZTE, snapshot.getVendor());
            assertEquals(OltVendor.CliStyle.SHOW, snapshot.getCliStyle());
            assertEquals(2, snapshot.getBoards().size());
            // 两套版本命令都发了（先 display 后 show），但板卡只发 show 这一套
            assertTrue(snapshot.getTranscript().contains("display version"));
            assertTrue(snapshot.getTranscript().contains("show version"));
            assertTrue(snapshot.getTranscript().contains("show card"));
            assertFalse(snapshot.getTranscript().contains("display board 0"));
        }
    }

    /**
     * 鸿升光被外网扫描后，登录成功会先打一屏 telnet 登录记录表并停在那儿：
     * 客户端要自动补回车才进得了命令行，否则登录超时（现场「账号密码对却连不上」）。
     */
    @Test
    void shouldPushEnterWhenLoginBannerStalls() throws Exception {
        try (FakeOltServer server = new FakeOltServer()) {
            server.setHsgq(true);
            server.setLoginBannerEnters(2);
            OltDevice device = new OltDevice();
            device.setHost("127.0.0.1");
            device.setPort(server.getPort());
            device.setProtocol("TELNET");
            device.setUsername("root");
            device.setPassword("admin");

            OltSnapshot snapshot;
            try (OltCliSession session = OltCliSession.open(
                    device, StandardCharsets.UTF_8, 3000, 8000, 4000)) {
                snapshot = session.collect(true);
            }
            server.await();

            assertEquals(OltVendor.HSGQ, snapshot.getVendor());
            assertTrue(snapshot.getTranscript().contains("info all login users"));
            assertTrue(snapshot.getTranscript().contains("suc login!"));
            assertTrue(snapshot.getTranscript().contains("[补回车]"));
            assertEquals("HSGQ-E04", snapshot.getModel());
        }
    }

    /**
     * 鸿升光 HSGQ-E04（EPON 一体机）：show version 认出厂商后就够了——
     * 现场不需要采集板卡信息（show pon-info 只回命令回显），直接按型号生成整机卡片出图。
     */
    @Test
    void shouldShowImageOnlyForHsgq() throws Exception {
        try (FakeOltServer server = new FakeOltServer()) {
            server.setHsgq(true);
            OltDevice device = new OltDevice();
            device.setHost("127.0.0.1");
            device.setPort(server.getPort());
            device.setProtocol("TELNET");
            device.setUsername("root");
            device.setPassword("admin");

            OltSnapshot snapshot;
            try (OltCliSession session = OltCliSession.open(
                    device, StandardCharsets.UTF_8, 3000, 5000, 4000)) {
                snapshot = session.collect(true);
            }
            server.await();

            assertEquals(OltVendor.HSGQ, snapshot.getVendor());
            assertEquals(OltVendor.CliStyle.SHOW, snapshot.getCliStyle());
            assertEquals("HSGQ-E04", snapshot.getModel());
            assertEquals("HSGQ-E04_I_V3.5.14C_Rel", snapshot.getSoftwareVersion());
            assertEquals("38:3A:21:2C:79:14", snapshot.getMacAddress());

            // 整机没有板卡概念：只发版本命令 + 配置视图下的 PON/ONU 两条，不发板卡/VLAN 命令
            assertTrue(snapshot.getTranscript().contains("show version"));
            assertTrue(snapshot.getTranscript().contains("show pon-info"));
            assertTrue(snapshot.getTranscript().contains("show onu-info all"));
            assertFalse(snapshot.getTranscript().contains("show vlan all"));
            assertTrue(snapshot.getNotes().isEmpty());

            // 整机卡片：型号即板卡型号（页面按它找示意图），4 个 PON 口
            assertEquals(1, snapshot.getBoards().size());
            OltBoard board = snapshot.getBoards().get(0);
            assertEquals("HSGQ-E04", board.getBoardType());
            assertEquals(4, board.getPortCount());
            assertEquals(4, board.getPorts().size());
            assertEquals("PON01", board.getPorts().get(0).getPortName());
            assertEquals("PON04", board.getPorts().get(3).getPortName());
            // 整机就是一个槽位：页面（第三方界面）按 slotCount 画机框，别画成 12 个空槽位
            assertEquals(1, snapshot.getSlotCount());

            // show pon-info：PON01 5/5、PON02 2/3、PON03/PON04 0/0
            assertEquals(Integer.valueOf(5), board.getPorts().get(0).getOnuOnline());
            assertEquals(Integer.valueOf(5), board.getPorts().get(0).getOnuTotal());
            assertEquals(Integer.valueOf(2), board.getPorts().get(1).getOnuOnline());
            assertEquals(Integer.valueOf(3), board.getPorts().get(1).getOnuTotal());
            assertEquals(Integer.valueOf(0), board.getPorts().get(3).getOnuTotal());

            // show onu-info all：1/1、1/2 挂在 PON01 下，MAC 即 EPON 的 SN
            assertEquals(2, board.getPorts().get(0).getOnts().size());
            assertEquals("C8:21:DA:F8:2F:70", board.getPorts().get(0).getOnts().get(0).getSn());
            assertTrue(board.getPorts().get(0).getOnts().get(0).getOnline());
            assertFalse(board.getPorts().get(1).getOnts().get(1).getOnline());
        }
    }

    /**
     * MA5683T 现场行为：display version 先回参数提示 { <cr>|backplane<K>|frameid/slotid<S> }:，
     * 补一个回车才输出 VERSION / PATCH / PRODUCT。客户端必须自己回车，否则只能等到超时。
     */
    @Test
    void shouldSendEnterWhenDeviceAsksForParameters() throws Exception {
        try (FakeOltServer server = new FakeOltServer()) {
            server.setVersionNeedsEnter(true);
            OltDevice device = new OltDevice();
            device.setHost("127.0.0.1");
            device.setPort(server.getPort());
            device.setProtocol("TELNET");
            device.setUsername("root");
            device.setPassword("admin");

            OltSnapshot snapshot;
            try (OltCliSession session = OltCliSession.open(
                    device, StandardCharsets.UTF_8, 3000, 5000, 3000)) {
                snapshot = session.collect(true);
            }
            server.await();

            assertEquals(OltVendor.HUAWEI, snapshot.getVendor());
            assertEquals("MA5683T", snapshot.getModel(), "versionText=" + snapshot.getVersionText());
            assertEquals("MA5600V800R015C00", snapshot.getSoftwareVersion());
            assertEquals("SPC107", snapshot.getPatchVersion());
            assertEquals("59天18小时35分50秒", snapshot.getUptime());
            assertEquals(2, snapshot.getBoards().size());
        }
    }

    /**
     * 现场这台 MA5683T 的真实情况：不发 display port 命令（这一版的 display port 只有
     * desc/mode/statistics/vlan），ONT 数量按板一次查回来，页面靠它显示「23 ONT / 22 在线」，
     * 回显里没出现的端口记 0/0 而不是整段消失。
     */
    @Test
    void shouldCollectOntCountsForEveryPort() throws Exception {
        try (FakeOltServer server = new FakeOltServer()) {
            server.setBoardType("H807GPBD");
            OltDevice device = new OltDevice();
            device.setHost("127.0.0.1");
            device.setPort(server.getPort());
            device.setProtocol("TELNET");
            device.setUsername("root");
            device.setPassword("admin");

            OltSnapshot snapshot;
            try (OltCliSession session = OltCliSession.open(
                    device, StandardCharsets.UTF_8, 3000, 5000, 4000)) {
                snapshot = session.collect(true);
            }
            server.await();

            List<OltPort> ports = snapshot.getBoards().get(0).getPorts();
            // display board 0 没给端口数，H807GPBD 按型号兜底成 8 个端口
            assertEquals(8, ports.size());
            assertEquals(Integer.valueOf(2), ports.get(0).getOnuTotal());
            assertEquals(Integer.valueOf(1), ports.get(0).getOnuOnline());
            // 不发 display port 命令，所以没有光模块状态：页面不画灯，而不是画成「不在位」
            assertNull(ports.get(0).getOpticalPresent());
            assertTrue(snapshot.getNotes().isEmpty(), "不该报错: " + snapshot.getNotes());

            // VLAN 随同一次采集带回来：列表来自 display vlan all
            OltVlanInfo vlan = snapshot.getVlanInfo();
            assertEquals(2, vlan.getVlans().size());
            assertEquals(Integer.valueOf(41), vlan.getVlans().get(1).getVlanId());
            assertEquals(Integer.valueOf(57), vlan.getVlans().get(1).getServPortNum());
            assertEquals(2, vlan.getTotal());
        }
    }

    /**
     * 上行口的 VLAN：display port vlan 0/6/0 → 放通 1/41/45/46，Native VLAN 1。
     * 只问主控口和上联口，PON 口不逐个问。
     */
    @Test
    void shouldCollectPortVlansForUplinkBoards() throws Exception {
        try (FakeOltServer server = new FakeOltServer()) {
            server.setBoardType("H807GPBD");
            server.setUplinkBoard("H801X2CS");
            OltDevice device = new OltDevice();
            device.setHost("127.0.0.1");
            device.setPort(server.getPort());
            device.setProtocol("TELNET");
            device.setUsername("root");
            device.setPassword("admin");

            OltSnapshot snapshot;
            try (OltCliSession session = OltCliSession.open(
                    device, StandardCharsets.UTF_8, 3000, 5000, 4000)) {
                snapshot = session.collect(true);
            }
            server.await();

            OltVlanInfo vlan = snapshot.getVlanInfo();
            assertFalse(vlan.getPortVlans().isEmpty(), "主控口/上联口要采到 VLAN: " + snapshot.getTranscript());
            OltPortVlan portVlan = vlan.getPortVlans().get(0);
            assertEquals(List.of(1, 41, 45, 46), portVlan.getVlans());
            assertEquals(Integer.valueOf(1), portVlan.getNativeVlan());
            assertTrue(portVlan.getIface().startsWith("0/"));

            // 三层接口和静态路由也在同一轮里采回来
            assertEquals(1, vlan.getIpInterfaces().size());
            assertEquals("10.172.10.254", vlan.getIpInterfaces().get(0).getIpAddress());
            assertEquals("255.255.255.0", vlan.getIpInterfaces().get(0).getMask());
            assertEquals(1, vlan.getStaticRoutes().size());
            assertEquals("10.172.10.1", vlan.getStaticRoutes().get(0).getNextHop());
        }
    }

    /**
     * 现场故障复现：display version 卡住不回（设备要选机框/回显特别慢），
     * 板卡必须已经在它之前抓到，不能因为版本命令超时导致「同步成功但 0 块板卡」。
     */
    @Test
    void shouldKeepBoardsWhenVersionCommandHangs() throws Exception {
        try (FakeOltServer server = new FakeOltServer()) {
            server.setSlowVersion(true);
            OltDevice device = new OltDevice();
            device.setHost("127.0.0.1");
            device.setPort(server.getPort());
            device.setProtocol("TELNET");
            device.setUsername("root");
            device.setPassword("admin");

            OltSnapshot snapshot;
            try (OltCliSession session = OltCliSession.open(
                    device, StandardCharsets.UTF_8, 3000, 5000, 800)) {
                snapshot = session.collect(true);
            }

            assertEquals(2, snapshot.getBoards().size());
            assertEquals("H901GPHF", snapshot.getBoards().get(0).getBoardType());
            assertTrue(snapshot.getNotes().stream().anyMatch(note -> note.contains("display version")),
                    "应提示版本命令无回显: " + snapshot.getNotes());
        }
    }

    @Test
    void shouldReportLoginFailure() throws Exception {
        try (FakeOltServer server = new FakeOltServer()) {
            server.setRejectLogin(true);
            OltDevice device = new OltDevice();
            device.setHost("127.0.0.1");
            device.setPort(server.getPort());
            device.setProtocol("TELNET");
            device.setUsername("root");
            device.setPassword("wrong");

            OltTelnetException error = org.junit.jupiter.api.Assertions.assertThrows(
                    OltTelnetException.class,
                    () -> OltCliSession.open(device, StandardCharsets.UTF_8, 3000, 5000, 4000));
            assertTrue(error.getMessage().contains("登录失败"), error.getMessage());
            server.await();
        }
    }

    /**
     * 假 OLT：交替回显提示符与命令结果。
     */
    private static final class FakeOltServer implements AutoCloseable {

        private final ServerSocket serverSocket;

        private final Thread thread;

        private volatile Throwable failure;

        private volatile boolean rejectLogin;

        /** 模拟 display version 迟迟不回：设备在等机框号或回显极慢 */
        private volatile boolean slowVersion;

        /** 模拟 MA5683T：display version 先回参数提示，补一个回车才输出内容 */
        private volatile boolean versionNeedsEnter;

        /** 板卡型号，H807GPBD 这类能在没有 display board 端口数时兜底出 8 个端口 */
        private volatile String boardType = "H901GPHF";

        /** 模拟第三方 OLT（中兴 C320）：只认 show 系列，display 一律 Unknown command */
        private volatile boolean showStyle;

        /** 模拟鸿升光 HSGQ-E04：OLT> 提示符，show version / pon-info / onu-info all */
        private volatile boolean hsgq;

        /**
         * 登录后打印的 telnet 登录记录表需要几次回车才出提示符（模拟被外网扫描：
         * 表很长，打完一屏就停下等人按回车），0 表示不打印登录表。
         */
        private volatile int loginBannerEnters;

        /** 额外插一块上联板（H801X2CS），用来验证上行口 VLAN 采集 */
        private volatile String uplinkBoard;

        FakeOltServer() throws IOException {
            serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            thread = new Thread(this::serve, "fake-olt");
            thread.setDaemon(true);
            thread.start();
        }

        int getPort() {
            return serverSocket.getLocalPort();
        }

        void setRejectLogin(boolean rejectLogin) {
            this.rejectLogin = rejectLogin;
        }

        void setSlowVersion(boolean slowVersion) {
            this.slowVersion = slowVersion;
        }

        void setVersionNeedsEnter(boolean versionNeedsEnter) {
            this.versionNeedsEnter = versionNeedsEnter;
        }

        void setBoardType(String boardType) {
            this.boardType = boardType;
        }

        void setShowStyle(boolean showStyle) {
            this.showStyle = showStyle;
        }

        void setHsgq(boolean hsgq) {
            this.hsgq = hsgq;
        }

        void setLoginBannerEnters(int loginBannerEnters) {
            this.loginBannerEnters = loginBannerEnters;
        }

        void setUplinkBoard(String uplinkBoard) {
            this.uplinkBoard = uplinkBoard;
        }

        void await() throws Exception {
            thread.join(8000);
            if (thread.isAlive()) {
                throw new AssertionError("模拟 OLT 线程未结束");
            }
            if (failure != null) {
                throw new AssertionError("模拟 OLT 异常", failure);
            }
        }

        private void serve() {
            try (Socket socket = serverSocket.accept()) {
                socket.setSoTimeout(8000);
                InputStream in = new BufferedInputStream(socket.getInputStream());
                OutputStream out = socket.getOutputStream();

                // 先发两个选项协商，客户端必须回应，否则很多设备不会继续提示登录
                out.write(new byte[]{(byte) IAC, (byte) WILL, 1});
                out.write(new byte[]{(byte) IAC, (byte) DO, 3});
                out.flush();

                write(out, "Welcome to Fake OLT\r\nUsername:");
                assertEquals("root", readLine(in));
                write(out, "\r\nPassword:");
                String password = readLine(in);
                if (rejectLogin) {
                    assertEquals("wrong", password);
                    write(out, "\r\nLogin invalid\r\nUsername:");
                    return;
                }
                assertEquals("admin", password);
                if (loginBannerEnters > 0) {
                    // 被外网扫描的鸿升光：登录成功先打一屏 telnet 登录记录表，
                    // 打完停在那一屏，客户端补几个回车才给提示符
                    write(out, "\r\n telnet login / info all login users\r\n"
                            + " userName       Source IP        Time                Type     Login time\r\n"
                            + " admin          149.88.64.57     2026/10/25 00:59:26 https    2026/10/25 00:59:26\r\n"
                            + " admin          43.249.31.19     2026/10/26 23:19:43 telnet   2026/10/26 23:19:43\r\n"
                            + " admin          45.128.232.234   2026/10/26 23:22:16 telnet   2026/10/26 23:22:16\r\n");
                    int enters = 0;
                    while (enters < loginBannerEnters) {
                        String line = readLine(in);
                        if (line == null) {
                            return;
                        }
                        if (line.isEmpty()) {
                            enters++;
                        }
                    }
                    write(out, " suc login!\r\nOLT>");
                } else {
                    write(out, showStyle ? "\r\nZXAN#" : hsgq ? "\r\nOLT>" : "\r\n<HUAWEI>");
                }

                // display version 停在参数提示上时，等客户端补回车再吐正文
                boolean pendingVersion = false;
                while (true) {
                    String command = readLine(in);
                    if (command == null) {
                        return;
                    }
                    if (command.isEmpty()) {
                        if (pendingVersion) {
                            pendingVersion = false;
                            write(out, "  VERSION : MA5600V800R015C00\r\n"
                                    + "  PATCH   : SPC107\r\n"
                                    + "  PRODUCT : MA5683T\r\n"
                                    + "  Uptime is 59 day(s), 18 hour(s), 35 minute(s), 50 second(s)\r\n"
                                    + "<HUAWEI>");
                        }
                        continue;
                    }
                    if (hsgq) {
                        if ("show version".equals(command)) {
                            write(out, "Copyright 2016-2025 by HSGQ\r\n"
                                    + " Software Version          : HSGQ-E04_I_V3.5.14C_Rel\r\n"
                                    + " System Version            : HSGQ-E04_I_V1.5.0_Rel\r\n"
                                    + " Hardware Version          : HSGQ-E04-hw-version-v3.0\r\n"
                                    + " Base Mac Address          : 38:3a:21:2c:79:14\r\n"
                                    + " Product                   : HSGQ-E04\r\n"
                                    + "OLT>");
                        } else if ("show pon-info".equals(command)) {
                            write(out, "PON Device 1 :\r\n"
                                    + " Device Status     : UP\r\n"
                                    + " Port    Status    Online/Total ONUs\r\n"
                                    + " PON01   Up        5/5\r\n"
                                    + " PON02   Up        2/3\r\n"
                                    + " PON03   Down      0/0\r\n"
                                    + " PON04   Down      0/0\r\n"
                                    + "OLT>");
                        } else if ("show onu-info all".equals(command)) {
                            write(out, "PON/ONU   Mac-Address          Status    Auth   Cfg"
                                    + "   Reg-time               ONU-Name    ONU-Desc\r\n"
                                    + " 1/1   c8:21:da:f8:2f:70   Online   TRUE   TRUE"
                                    + "   2026/10/25 00:46:10   ONU01/01   NO-DESCRIPTI\r\n"
                                    + " 1/2   c8:21:da:f8:2f:0e   Online   TRUE   TRUE"
                                    + "   2026/10/25 00:46:10   ONU01/02   NO-DESCRIPTI\r\n"
                                    + " 2/1   c8:21:da:f8:30:82   Online   TRUE   TRUE"
                                    + "   2026/10/25 00:46:10   ONU02/01   NO-DESCRIPTI\r\n"
                                    + " 2/2   c8:21:da:f8:30:96   Offline  TRUE   TRUE"
                                    + "   2026/10/25 00:46:11   ONU02/02   NO-DESCRIPTI\r\n"
                                    + " Total: 8  Online:7  Offline:1\r\n"
                                    + "OLT>");
                        } else if ("show vlan all".equals(command)) {
                            write(out, "----------------------------------------\r\n"
                                    + " VLAN ID            : 1\r\n"
                                    + " Name               : VLAN1\r\n"
                                    + " Description        : VLAN1\r\n"
                                    + " Tagged Ports       :\r\n"
                                    + " Untagged Ports     :\r\n"
                                    + " Default Vlan Ports : PON01-PON04,GE01-GE08\r\n"
                                    + "----------------------------------------\r\n"
                                    + "----------------------------------------\r\n"
                                    + " VLAN ID            : 41\r\n"
                                    + " Name               : VLAN41\r\n"
                                    + " Description        : uplink\r\n"
                                    + " Tagged Ports       : GE01-GE02\r\n"
                                    + " Untagged Ports     :\r\n"
                                    + " Default Vlan Ports :\r\n"
                                    + "----------------------------------------\r\n"
                                    + "OLT(config)#");
                        } else if ("enable".equals(command)) {
                            write(out, "OLT#");
                        } else if ("configure".equals(command)) {
                            write(out, "OLT(config)#");
                        } else {
                            write(out, "% Unknown command\r\nOLT>");
                        }
                        continue;
                    }
                    if (showStyle) {
                        // 第三方 OLT：display 系列一律不认，只有 show 系列有内容
                        if ("display version".equals(command) || "display board 0".equals(command)) {
                            write(out, "% Unknown command\r\nZXAN#");
                        } else if ("show version".equals(command)) {
                            write(out, "ZTE ZXA10 C320 Software, Version V2.0.1\r\nZXAN#");
                        } else if ("show card".equals(command)) {
                            write(out, "Rack Shelf Slot Cpu BoardType PortNum Status\r\n"
                                    + "  1    1     1    1   GTXO        8      online\r\n"
                                    + "  1    1     2    1   GTXO        8      online\r\n"
                                    + "ZXAN#");
                        } else {
                            write(out, "ZXAN#");
                        }
                        continue;
                    }
                    if ("display version".equals(command)) {
                        if (slowVersion) {
                            // 拖过客户端命令超时，模拟现场「版本命令卡住」
                            Thread.sleep(2500);
                        }
                        if (versionNeedsEnter) {
                            // MA5683T 真实行为：不给参数就停在 { <cr>|backplane<K>|... }: 上
                            pendingVersion = true;
                            write(out, " { <cr>|backplane<K>|frameid/slotid<S><Length 1-15> }:");
                            continue;
                        }
                        write(out, "Huawei Versatile Routing Platform Software\r\n"
                                + "VRP (R) software, Version 5.180 (MA5600T V800R018C10)\r\n"
                                + "<HUAWEI>");
                    } else if ("display board 0".equals(command)) {
                        write(out, "  SlotID  BoardName     Status\r\n"
                                + "  0       " + boardType + "      Normal\r\n"
                                + (uplinkBoard == null ? "" : "  8       " + uplinkBoard + "      Normal\r\n")
                                + "  1       H901PILA      Normal\r\n"
                                + "<HUAWEI>");
                    } else if ("enable".equals(command)) {
                        write(out, "<HUAWEI>#");
                    } else if ("config".equals(command)) {
                        write(out, "<HUAWEI>(config)#");
                    } else if ("quit".equals(command)) {
                        write(out, "<HUAWEI>#");
                    } else if (command.startsWith("display temperature 0/")) {
                        write(out, "  The temperature of the board:  41C( 105F)\r\n<HUAWEI>(config)#");
                    } else if (command.startsWith("display ont info")) {
                        // 只有 config 视图认这条命令，参数是空格分隔的机框/槽位，一次拿整块板
                        String[] args = command.split("\\s+");
                        String slotId = args[3];
                        // 设备把端口写成 "0/ 0/0"，逗号前还带空格
                        write(out, "  0/ " + slotId + "/0   0     485754431B2CA496   active  online   normal  match\r\n"
                                + "  0/ " + slotId + "/0   1     485754431B2CA497   active  offline  normal  match\r\n"
                                + "  In port 0/ " + slotId + "/0 , the total of ONTs are: 2, online: 1\r\n"
                                + "<HUAWEI>(config)#");
                    } else if ("display vlan all".equals(command)) {
                        write(out, "  ---------------------------------------------------------------\r\n"
                                + "  VLAN  Type   Attribute  STND-Port NUM  SERV-Port NUM  VLAN-Con NUM\r\n"
                                + "  ---------------------------------------------------------------\r\n"
                                + "     1   smart  common          6               0             -\r\n"
                                + "    41   smart  common          6              57             -\r\n"
                                + "  ---------------------------------------------------------------\r\n"
                                + "  Total: 2\r\n"
                                + "<HUAWEI>(config)#");
                    } else if (command.startsWith("display port vlan 0/")) {
                        write(out, "  --------------\r\n"
                                + "   1    41    45   46\r\n"
                                + "  --------------\r\n"
                                + "  Total: 4\r\n"
                                + "  Native VLAN: 1\r\n"
                                + "<HUAWEI>(config)#");
                    } else if ("display ip interface brief".equals(command)) {
                        write(out, "Interface        IP Address/Mask       Physical    Protocol\r\n"
                                + "vlanif46         10.172.10.254/24      up          up\r\n"
                                + "<HUAWEI>(config)#");
                    } else if ("display ip routing-table".equals(command)) {
                        write(out, "Destination/Mask    Proto   Pre  Cost      Flags NextHop         Interface\r\n"
                                + "        0.0.0.0/0   Static  60   0          RD   10.172.10.1     vlanif46\r\n"
                                + "    10.172.10.0/24  Direct  0    0           D   10.172.10.254   vlanif46\r\n"
                                + "<HUAWEI>(config)#");
                    } else {
                        write(out, "<HUAWEI>");
                    }
                }
            } catch (java.net.SocketException e) {
                // 客户端采集完就关连接，假设备这边的写入会失败，属于正常收尾
            } catch (Throwable t) {
                failure = t;
            }
        }

        private static void write(OutputStream out, String text) throws IOException {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        /**
         * 读一行，跳过客户端发来的 IAC 协商字节。
         */
        private static String readLine(InputStream in) throws IOException {
            StringBuilder line = new StringBuilder();
            while (true) {
                int b = in.read();
                if (b < 0) {
                    return line.length() == 0 ? null : line.toString();
                }
                if (b == IAC) {
                    int command = in.read();
                    if (command < 0) {
                        return null;
                    }
                    if (command == 250) {
                        skipSubnegotiation(in);
                    } else if (command >= 251 && command <= 254) {
                        in.read();
                    }
                    continue;
                }
                if (b == '\r') {
                    continue;
                }
                if (b == '\n') {
                    return line.toString();
                }
                line.append((char) b);
            }
        }

        private static void skipSubnegotiation(InputStream in) throws IOException {
            while (true) {
                int b = in.read();
                if (b < 0) {
                    return;
                }
                if (b == IAC) {
                    int next = in.read();
                    if (next == 240 || next < 0) {
                        return;
                    }
                }
            }
        }

        @Override
        public void close() {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // 测试收尾，忽略
            }
            try {
                thread.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
