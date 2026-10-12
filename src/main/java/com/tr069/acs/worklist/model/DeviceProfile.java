package com.tr069.acs.worklist.model;

import com.tr069.acs.cwmp.RpcMethod;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 光猫（CPE）适配档案。
 *
 * <p>一台设备启动时上报 Manufacturer / OUI / ProductClass / SoftwareVersion，
 * ACS 据此匹配到唯一的 Profile，之后的工单下发、参数路径、能力判定都以此为准。
 * 新增一款光猫只需要新增一个 yaml 文件即可，无需改动 Java 代码。</p>
 */
@Data
public class DeviceProfile {

    /** 档案唯一标识，建议使用「运营商-版本-型号」。 */
    private String key;

    /** 可读名称。 */
    private String name;

    /** 运营商：CT / CU / CMCC / standard。 */
    private String isp;

    /** 规范版本，如 v3.0。 */
    private String version;

    /**
     * 该型号光猫上报所用的 ACS 端口。
     * 不同厂商出厂固化的 ACS URL 端口并不一致，记录在适配档案里供现场核对；
     * 若该端口不在 acs.cwmp.listen-ports 中，启动时会告警。
     */
    private Integer acsPort;

    /** 匹配规则。 */
    private MatchRule match = new MatchRule();

    /** 设备能力。 */
    private Capability capability = new Capability();

    /** ACS 反向连接（ACS -> CPE）认证，留空则使用全局默认账号。 */
    private Auth auth = new Auth();

    /**
     * 标准参数名 -> 该设备上的真实 TR069 路径。
     * 工单模板中使用标准参数名，由本表翻译为设备实际路径。
     */
    private Map<String, String> parameterAliases = new LinkedHashMap<>();

    /**
     * 设备特有的行为开关与微调项。
     * 值可以是 boolean / int / String，由具体执行器解释。
     */
    private Map<String, Object> quirks = new LinkedHashMap<>();

    /** 首次注册（0 BOOTSTRAP）时要额外下发的参数。 */
    private List<BootstrapAction> bootstrapActions;

    /** 档案来源文件，热加载用于定位。 */
    private String sourceFile;

    /** 文件最后修改时间。 */
    private long lastModified;

    /** 最近一次加载是否成功。 */
    private volatile boolean valid = true;

    /** 解析错误信息。 */
    private String error;

    /** 匹配优先级，数值越小优先级越高；多个 Profile 命中时取最小的。 */
    private int priority = 100;

    // ==================== 内部类 ====================

    /**
     * 设备匹配规则。字段留空表示不参与匹配；支持正则。
     */
    @Data
    public static class MatchRule {
        /** Manufacturer，支持正则。 */
        private String manufacturer;
        /** OUI（序列号前 6 位），支持正则。 */
        private String oui;
        /** ProductClass，支持正则。 */
        private String productClass;
        /** SoftwareVersion，支持正则。 */
        private String softwareVersion;
        /** 序列号，支持正则，用于精确指定个别设备。 */
        private String serialNumber;
    }

    /**
     * 设备能力声明。
     */
    @Data
    public static class Capability {
        /** 设备支持的 RPC 列表，留空表示不校验。 */
        private List<String> supportedRpc;
        /** 单次 GetParameterValues / SetParameterValues 允许的参数个数上限，0 表示不限。 */
        private int maxParametersPerRequest = 0;
        /** 是否支持 SetParameterAttributes。 */
        private boolean supportParameterAttributes = true;
        /** 是否支持 DUStateChangeComplete（TR-157 批量操作）。 */
        private boolean supportDuStateChange = false;
        /** SetParameterValues 后是否需要间隔等待(毫秒)。 */
        private long delayAfterSetMillis = 0;
        /** SetParameterValues 失败时的重试次数。 */
        private int setRetryTimes = 0;
        /** 是否忽略 Inform 中的 ParameterList（部分设备上报残缺）。 */
        private boolean ignoreInformParameterList = false;
    }

    /**
     * ACS 反向连接的认证信息。
     */
    @Data
    public static class Auth {
        /** ACS -> CPE 连接请求用户名。 */
        private String acsToCpeUsername;
        /** ACS -> CPE 连接请求密码。 */
        private String acsToCpePassword;

        /** 是否要求 GetRPCMethods 探测。 */
        private boolean requireRpcMethodsProbe = false;
    }

    /**
     * 首次注册时的额外动作。
     */
    @Data
    public static class BootstrapAction {
        /** 动作说明。 */
        private String comment;
        /** 要设置的参数名（标准名或完整路径）。 */
        private String parameter;
        /** 参数值，支持 ${sn} 等占位符。 */
        private String value;
    }
}
