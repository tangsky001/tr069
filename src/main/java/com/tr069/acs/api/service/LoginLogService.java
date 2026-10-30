package com.tr069.acs.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tr069.acs.domain.entity.SysLoginLog;
import com.tr069.acs.mapper.SysLoginLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 登录日志。
 *
 * <p>写的时候顺手丢最旧的：这是个只增不查的表，太久远的行留着没意义，
 * 一张几百万行的日志表会让查询变慢。按用户各留最近 50 条。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginLogService {

    /** 每个用户保留的条数，超出的把最旧的删掉。 */
    private static final int KEEP_PER_USER = 50;

    private final SysLoginLogMapper loginLogMapper;

    /** 记一次成功的登录。 */
    public void succeed(Long userId, String username, String ip, String source, String userAgent) {
        write(userId, username, ip, source, userAgent, true, null);
    }

    /** 记一次失败的登录。用户名不存在时 userId 传 null。 */
    public void fail(Long userId, String username, String ip, String source,
                     String userAgent, String reason) {
        write(userId, username, ip, source, userAgent, false, reason);
    }

    /** 最近的登录记录，按时间倒序。 */
    public List<Map<String, Object>> recent(int limit) {
        List<SysLoginLog> rows = loginLogMapper.selectList(
                new LambdaQueryWrapper<SysLoginLog>()
                        .orderByDesc(SysLoginLog::getCreatedAt)
                        .last("LIMIT " + Math.min(Math.max(limit, 1), 200)));
        return toData(rows);
    }

    /** 某个人最近的登录记录。 */
    public List<Map<String, Object>> ofUser(Long userId, int limit) {
        List<SysLoginLog> rows = loginLogMapper.selectList(
                new LambdaQueryWrapper<SysLoginLog>()
                        .eq(SysLoginLog::getUserId, userId)
                        .orderByDesc(SysLoginLog::getCreatedAt)
                        .last("LIMIT " + Math.min(Math.max(limit, 1), 200)));
        return toData(rows);
    }

    private void write(Long userId, String username, String ip, String source,
                       String userAgent, boolean success, String reason) {
        SysLoginLog row = new SysLoginLog();
        row.setUserId(userId);
        row.setUsername(username);
        row.setIp(ip);
        row.setSource(source);
        // UA 可能很长，超出列宽会写不进去，截断一下
        row.setUserAgent(userAgent == null ? null
                : userAgent.length() > 255 ? userAgent.substring(0, 255) : userAgent);
        row.setSuccess(success ? 1 : 0);
        row.setFailReason(reason);
        loginLogMapper.insert(row);
        if (userId != null) {
            trim(userId);
        }
    }

    /**
     * 只留这个用户最近的若干条。
     *
     * <p>先数再删，删的是比第 N 条更旧的那些。表空着时 selectCount 返回 0，直接返回。</p>
     */
    private void trim(Long userId) {
        Long count = loginLogMapper.selectCount(
                new LambdaQueryWrapper<SysLoginLog>().eq(SysLoginLog::getUserId, userId));
        if (count == null || count <= KEEP_PER_USER) {
            return;
        }
        List<SysLoginLog> keep = loginLogMapper.selectList(
                new LambdaQueryWrapper<SysLoginLog>()
                        .eq(SysLoginLog::getUserId, userId)
                        .orderByDesc(SysLoginLog::getCreatedAt)
                        .last("LIMIT " + KEEP_PER_USER));
        List<Long> keepIds = keep.stream().map(SysLoginLog::getId).toList();
        loginLogMapper.delete(new LambdaQueryWrapper<SysLoginLog>()
                .eq(SysLoginLog::getUserId, userId)
                .notIn(SysLoginLog::getId, keepIds));
    }

    private static List<Map<String, Object>> toData(List<SysLoginLog> rows) {
        List<Map<String, Object>> data = new ArrayList<>();
        for (SysLoginLog row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId());
            item.put("userId", row.getUserId());
            item.put("username", row.getUsername());
            item.put("ip", row.getIp());
            item.put("source", row.getSource());
            item.put("userAgent", row.getUserAgent());
            item.put("success", row.getSuccess() != null && row.getSuccess() == 1);
            item.put("failReason", row.getFailReason());
            item.put("createdAt", row.getCreatedAt());
            data.add(item);
        }
        return data;
    }
}
