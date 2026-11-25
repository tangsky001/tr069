/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.cpe.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/** CPE（光猫）CWMP 接入配置，对应 application.yml 的 acs.cpe 段 */
@Data
@Component
@ConfigurationProperties(prefix = "acs.cpe")
public class CpeProperties {

    /** 设备直连 ACS 路径列表 */
    private List<String> paths = List.of("/acs");

    /** 设备直连监听端口列表 */
    private List<Integer> ports = List.of(9090, 8080, 8012, 6060, 5481, 9092, 6890, 9095, 7013);

    private String authUsername = "";
    private String authPassword = "";

    /** 会话空闲超时（秒） */
    private int sessionTimeoutSeconds = 60;

    /**
     * 空队列捎带等待（毫秒，master piggyback-wait-ms 同值）：
     * 会话内任务跑完且队列空时，ACS 不立刻收尾，短等下发引擎串行塞入下一步任务，
     * 避免每步 RPC 都经历"会话结束→CR 唤醒→Inform→新会话"的重建（当前分支 8 分钟/台的主因）
     */
    private int piggybackWaitMs = 1500;

    /** 单会话最大 RPC 往返数 */
    private int maxRpcCount = 255;

    private int gpvBatchSize = 32;

    /** SPV 后是否回读校验 */
    private boolean spvReadback = true;

    /** 参数树 SQLite 库文件路径 */
    private String sqlitePath = "data/template.pack";

    /** 启动时自动补建台账/任务表 */
    private boolean autoCreateTable = true;

    /** 反向连接账密 */
    private String crUsername = "itsm";
    private String crPassword = "itsm";

    private int crTimeoutMs = 3000;

    /** 首次下发周期 Inform 间隔（秒） */
    private int initPeriodicInformSeconds = 300;

    /** 采集任务等待设备回参秒数 */
    private int collectTimeoutSeconds = 120;

    /** Redis 会话 TTL（秒） */
    private int sessionTtlSeconds = 180;

    /** CWMP 报文体积上限（字节） */
    private int maxBodyBytes = 1024 * 1024;

    /** SN 粒度令牌桶限流开关 */
    private boolean rateLimitEnabled = true;
    private int rateLimitCapacity = 10;
    private int rateLimitRefillPerMinute = 12;

    /** 设备档案缓存 TTL（秒） */
    private int deviceInfoTtlSeconds = 600;

    /** 参数树缓存 TTL（秒） */
    private int paramTreeTtlSeconds = 600;

    /** Redis 派发队列长度上限 */
    private int taskQueueMaxSize = 10000;

    /** 前端轮询节奏（秒） */
    private int listPollSeconds = 10;
    private int taskPollSeconds = 5;
    private int bizPollSeconds = 15;
    private int rpcPollSeconds = 2;
    private int rpcMaxAttempts = 60;
    private int detailRpcPollSeconds = 3;
    private int detailRpcMaxAttempts = 40;

    /** 能力探测兜底根路径 */
    private String defaultRootNode = "InternetGatewayDevice.";
}
