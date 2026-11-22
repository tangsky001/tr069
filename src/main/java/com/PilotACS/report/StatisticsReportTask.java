/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.736
 *
 */

package com.PilotACS.report;

import com.PilotACS.common.log.Logs;
import com.PilotACS.report.model.StatisticsInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

/** 定时把本实例的信息上报到 服务端统计有多少ACS */
@Component
public class StatisticsReportTask {

    /** 统计上报接口 */
    private static final String REPORT_URL = "http://total.i-ddns.com:11236/api/statistics/ping";

    /** 服务端归组标识 */
    private static final String SERVER_ID = "acs";

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 请求超时 */
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;
    private final DataSource dataSource;
    private final GitInfo gitInfo;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public StatisticsReportTask(ObjectMapper objectMapper,
                                StringRedisTemplate redis,
                                @Qualifier("dataSource") DataSource dataSource,
                                GitInfo gitInfo) {
        this.objectMapper = objectMapper;
        this.redis = redis;
        this.dataSource = dataSource;
        this.gitInfo = gitInfo;
    }

    /** 每 15 分钟上报一次，启动 5 秒后发第一次 */
    @Scheduled(initialDelay = 5000, fixedRate = 900000)
    public void scheduledSendStatistics() {
        try {
            sendPost(collect());
        } catch (Exception e) {
            Logs.debug(Logs.Module.SYSTEM, "统计上报失败（下个周期重试）", "原因", e.getMessage());
        }
    }

    private StatisticsInfo collect() {
        StatisticsInfo info = new StatisticsInfo();
        info.setServerId(SERVER_ID);
        info.setDeviceId(MachineFingerprint.deviceId());
        info.setBranch(gitInfo.getBranch());
        info.setGitCommitId(gitInfo.getCommitId());
        info.setGitUrl(gitInfo.getRemoteUrl());
        info.setVersion(resolveVersion());
        info.setOsName(System.getProperty("os.name"));
        info.setArch(System.getProperty("os.arch"));
        info.setJdkVersion(System.getProperty("java.version"));
        info.setDocker(new File("/.dockerenv").exists());
        try {
            info.setRedisVersion(redisVersion());
        } catch (Exception ignored) {
        }
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            info.setSqlVersion(metaData.getDatabaseProductVersion());
            info.setSqlType(metaData.getDriverName());
        } catch (Exception ignored) {
        }
        info.setTime(LocalDateTime.now().format(TIME_FORMAT));
        return info;
    }

    /** Redis 版本，取不到返回 null */
    private String redisVersion() {
        if (redis == null || redis.getConnectionFactory() == null) {
            return null;
        }
        try (RedisConnection connection = redis.getConnectionFactory().getConnection()) {
            Properties info = connection.info();
            return info == null ? null : info.getProperty("redis_version");
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析版本号 */
    private String resolveVersion() {
        String buildVersion = gitInfo.getBuildVersion();
        if (buildVersion != null && !buildVersion.isBlank()) {
            return buildVersion;
        }
        String version = StatisticsReportTask.class.getPackage().getImplementationVersion();
        return (version == null || version.isBlank()) ? StatisticsInfo.FALLBACK_VERSION : version;
    }

    private void sendPost(StatisticsInfo info) throws Exception {
        String body = objectMapper.writeValueAsString(info);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(REPORT_URL))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
