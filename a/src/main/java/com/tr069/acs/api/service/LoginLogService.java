package com.tr069.acs.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tr069.acs.domain.entity.SysLoginLog;
import com.tr069.acs.mapper.SysLoginLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 登录日志 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginLogService {

    /** 每个用户保留的条数，超出的把最旧的删掉 */
    private static final int KEEP_PER_USER = 50;

    private final SysLoginLogMapper loginLogMapper;

    /** 记一次成功的登录 */
    public void succeed(Long userId, String username, String ip, String source, String userAgent) {
        write(userId, username, ip, source, userAgent, true, null);
    }

    /** 记一次失败的登录。用户名不存在时 userId 传 null */
    public void fail(Long userId, String username, String ip, String source,
                     String userAgent, String reason) {
        write(userId, username, ip, source, userAgent, false, reason);
    }

    /** 登录来源 → 中文名：页面不再维护这张码表 */
    private static final Map<String, String> SOURCE_LABELS = Map.of(
            "WEB", "网页端",
            "CLIENT", "客户端",
            "MOBILE", "移动端");

    /** 按条件查登录日志：地址模糊、来源、时间段、指定用户 */
    public List<Map<String, Object>> search(Long userId, String ip, String source,
                                            LocalDateTime from, LocalDateTime to, int limit) {
        LambdaQueryWrapper<SysLoginLog> wrapper = new LambdaQueryWrapper<>();
        if (userId != null) {
            wrapper.eq(SysLoginLog::getUserId, userId);
        }
        if (ip != null && !ip.isBlank()) {
            wrapper.like(SysLoginLog::getIp, ip.trim());
        }
        if (source != null && !source.isBlank()) {
            wrapper.eq(SysLoginLog::getSource, source.trim().toUpperCase(Locale.ROOT));
        }
        if (from != null) {
            wrapper.ge(SysLoginLog::getCreatedAt, from);
        }
        if (to != null) {
            wrapper.le(SysLoginLog::getCreatedAt, to);
        }
        wrapper.orderByDesc(SysLoginLog::getCreatedAt)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 500));
        return toData(loginLogMapper.selectList(wrapper));
    }

    /** 最近的登录记录，按时间倒序 */
    public List<Map<String, Object>> recent(int limit) {
        List<SysLoginLog> rows = loginLogMapper.selectList(
                new LambdaQueryWrapper<SysLoginLog>()
                        .orderByDesc(SysLoginLog::getCreatedAt)
                        .last("LIMIT " + Math.min(Math.max(limit, 1), 200)));
        return toData(rows);
    }

    /** 某个人最近的登录记录 */
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

    /** 只留这个用户最近的若干条 */
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
            item.put("sourceText", row.getSource() == null
                    ? "-" : SOURCE_LABELS.getOrDefault(row.getSource(), row.getSource()));
            item.put("userAgent", row.getUserAgent());
            boolean success = row.getSuccess() != null && row.getSuccess() == 1;
            item.put("success", success);
            item.put("failReason", row.getFailReason());
            // 结果文案也由后端给：成功 / 失败原因，页面只上色
            item.put("resultText", success ? "成功"
                    : (row.getFailReason() == null || row.getFailReason().isBlank()
                    ? "失败" : row.getFailReason()));
            item.put("createdAt", row.getCreatedAt());
            // 时间文案后端格式化：页面不再自己把 ISO 串里的 T 换成空格
            item.put("createdAtText", row.getCreatedAt() == null ? "-"
                    : row.getCreatedAt().format(java.time.format.DateTimeFormatter
                    .ofPattern("yyyy-MM-dd HH:mm:ss")));
            data.add(item);
        }
        return data;
    }
}
