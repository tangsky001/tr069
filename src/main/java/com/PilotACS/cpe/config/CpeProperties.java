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

/**
 * CPE（光猫）CWMP 接入配置，对应 application.yml 的 acs.cpe 段
 */
@Data
@Component
@ConfigurationProperties(prefix = "acs.cpe")
public class CpeProperties {

    /** 是否启用 CWMP 接入端点 */
    private boolean enabled = true;

    /** CPE 上报端点路径（设备侧配置的 ACS URL 指向这里） */
    private String path = "/acs";

    /**
     * CPE 设备直连监听端口列表（独立于 server 主端口）。
     * 主端口上的 CPE 路径会被拒绝，主端口只服务管理端 REST API。
     */
    private List<Integer> ports = List.of(9090, 8080, 8012, 6060, 5481, 9092, 6890, 9095, 7013);

    /** 同设备互斥：是否校验 ACS 用户名密码（Basic，空则不校验） */
    private String authUsername = "";
    private String authPassword = "";

    /** 会话空闲超时（秒），超时未收到 CPE 请求则结束会话并落库 */
    private int sessionTimeoutSeconds = 60;

    /** 单会话最大 RPC 往返数，防失控 */
    private int maxRpcCount = 255;

    /** GetParameterValues 单次批量参数个数（参考 genieacs gpvBatchSize=32） */
    private int gpvBatchSize = 32;

    /**
     * 设备参数树/机型模板 SQLite 库文件（相对运行目录）。
     * 内置包 resources/data/template.pack，首次启动释放到该路径。
     */
    private String sqlitePath = "data/template.pack";

    /** 设备台账/下发任务表是否启动时自动补建（MySQL，DDL 与 sql/mysql.sql 一致） */
    private boolean autoCreateTable = true;

    /** Connection Request（反向连接）账号密码，同时用于 ACS→CPE 的 Digest 认证 */
    private String crUsername = "itsm";
    private String crPassword = "itsm";

    /** 反向连接超时（毫秒） */
    private int crTimeoutMs = 3000;

    /** 采集类同步任务等待设备回参的最大秒数（业务采集/能力测试） */
    private int collectTimeoutSeconds = 120;

    /** 前端轮询节奏建议值，后端统一下发 */
    private int listPollSeconds = 10;
    private int taskPollSeconds = 5;
    private int bizPollSeconds = 15;
    private int rpcPollSeconds = 2;
    private int rpcMaxAttempts = 60;
    private int detailRpcPollSeconds = 3;
    private int detailRpcMaxAttempts = 40;

    /** 能力测试探测的根路径（两个数据模型自动识别，这里只是兜底） */
    private String defaultRootNode = "InternetGatewayDevice.";
}
