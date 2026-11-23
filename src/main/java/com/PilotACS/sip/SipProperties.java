/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.sip;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** SIP 软交换配置（acs.sip 前缀） */
@Data
@Component
@ConfigurationProperties(prefix = "acs.sip")
public class SipProperties {

    /** 是否启用 SIP 软交换（默认关闭） */
    private boolean enabled = false;
    /** SIP 信令监听端口 */
    private int udpPort = 5060;
    /** 对外公布的 RTP/SIP 主机地址，留空自动取本机非回环地址 */
    private String publicIp = "";
    /** Digest 鉴权域 */
    private String realm = "pilotacs";
    /** 是否启用 401 Digest 鉴权 */
    private boolean authEnabled = true;
    /** 回铃背景音乐 wav 路径（8k/16bit/单声道 PCM），留空用合成 450Hz 回铃音 */
    private String ringbackWav = "";
    /** 是否录音（通话结束写 WAV） */
    private boolean recordEnabled = true;
    /** 录音输出目录 */
    private String recordDir = "data/sip-records";
    /** 抖动缓冲深度（毫秒） */
    private int jitterMs = 60;
    /** 振铃超时（秒），超时取消呼叫 */
    private int ringTimeoutSeconds = 60;
    /** 单通通话最大时长（秒），0 不限制 */
    private int maxCallSeconds = 3600;
    /** RTP 空闲超时（秒）：ACTIVE 期间连续无 RTP 即拆线，0 关闭；用于清理客户端异常退出产生的僵尸会话 */
    private int rtpIdleSeconds = 30;
    /** 忙线提示音（秒）：主叫拨正在通话的分机时先应答并循环播放提示音，N 秒后由服务端挂断主叫；0 = 维持直接回 486 */
    private int busyPromptSeconds = 10;
    /** 忙线提示音 wav（8k/16bit/单声道 PCM，如"您拨打的用户正在通话中"），留空用合成忙音（嘟嘟嘟） */
    private String busyPromptWav = "";
    /** 挂断忙音时长（秒）：通话中任意一方挂机，另一方听忙音（嘟嘟嘟）N 秒后由服务端拆线，0 = 立即拆线 */
    private int busyToneSeconds = 10;
    /** 注册过期清理周期（秒） */
    private int registrationSweepSeconds = 30;
    /** H.248 网关控制开关（MGC 角色，UDP 文本编码） */
    private boolean h248Enabled = false;
    /** H.248 监听端口（文本编码默认 2944） */
    private int h248Port = 2944;
    /** H.248 分机号 → 终结点 ID（如 "2001" → "AALN/1"，网关按 ServiceChange 来源 IP 匹配） */
    private Map<String, String> h248Terminals = new LinkedHashMap<>();
}
