package com.tr069.acs.olt.vendor;

import com.tr069.acs.olt.telnet.OltVendor;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 华为 MA5600T / MA5683T / MA5800 / EA5800。
 *
 * <p>命令是 {@code display} 那一套，关分页是 {@code scroll}（{@code screen-length}
 * 是 VRP 交换机的命令，OLT 不认）；只跑带机框号的 {@code display board 0}——
 * 不带参数时设备会回 "Parameter: FrameID/SlotID" 停下来等输入，命令就一直等到超时。</p>
 */
public class HuaweiOltProfile extends AbstractOltVendorProfile {

    /** MA5683T / MA5600T / MA5800-X7；注意别把版本号 MA5600V800R015C00 当成型号 */
    private static final Pattern MODEL = Pattern.compile(
            "(?i)\\b(MA5800-[A-Z0-9]+|EA5800-[A-Z0-9]+|MA6800T|MA5\\d{3,4}T?)\\b");

    @Override
    public OltVendor vendor() {
        return OltVendor.HUAWEI;
    }

    @Override
    public OltVendor.CliStyle cliStyle() {
        return OltVendor.CliStyle.DISPLAY;
    }

    @Override
    public boolean matches(String output) {
        return containsAny(output, "huawei", "quidway", "versatile routing", "smartax",
                "ma5600", "ma5603", "ma5608", "ma5680", "ma5683", "ma5800", "ea5800", "ma6800");
    }

    @Override
    public List<String> pagingCommands() {
        // undo smart 在部分版本会弹交互确认，反而拖慢采集，不发
        return List.of("scroll", "screen-length 0 temporary");
    }

    @Override
    public List<String> versionCommands() {
        return List.of("display version");
    }

    @Override
    public List<String> boardCommands() {
        return List.of("display board 0");
    }

    @Override
    protected List<Pattern> modelPatterns() {
        return List.of(MODEL, MODEL_LABEL);
    }

    @Override
    public boolean enterConfigView(OltCli cli, int timeoutMs) {
        if (cli == null) {
            return false;
        }
        // enable 在已是特权视图时会回一句报错，忽略即可
        cli.run("enable", timeoutMs);
        String out = cli.run("config", timeoutMs);
        if (out == null || !out.contains("(config)#")) {
            return false;
        }
        // scroll 在特权视图才生效（登录后的用户视图发了也不认），进 config 后补一次，
        // 后面 display ont info / service-port 这类长回显就不分页了
        cli.run("scroll", timeoutMs);
        return true;
    }
}
