package com.PilotACS.service;

import com.PilotACS.common.enums.ClientType;
import com.PilotACS.entity.SysLoginLog;
import com.PilotACS.mapper.SysLoginLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 登录日志服务
@Service
public class SysLoginLogService extends ServiceImpl<SysLoginLogMapper, SysLoginLog> {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter INPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    // 查询参数：limit/ip/source/from/to，返回前端约定字段
    public List<Map<String, Object>> search(Long limit, String ip, String source, String from, String to) {
        LambdaQueryWrapper<SysLoginLog> qw = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(ip)) {
            qw.like(SysLoginLog::getIp, ip);
        }
        if (StringUtils.hasText(source)) {
            qw.eq(SysLoginLog::getSource, source.toUpperCase());
        }
        try {
            if (StringUtils.hasText(from)) {
                qw.ge(SysLoginLog::getCreatedAt, LocalDateTime.parse(from, INPUT));
            }
            if (StringUtils.hasText(to)) {
                qw.le(SysLoginLog::getCreatedAt, LocalDateTime.parse(to, INPUT));
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("时间格式应为 yyyy-MM-ddTHH:mm:ss");
        }
        qw.orderByDesc(SysLoginLog::getId);
        long size = (limit == null || limit <= 0) ? 200 : Math.min(limit, 1000);
        List<Map<String, Object>> list = new ArrayList<>();
        for (SysLoginLog log : this.page(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, size), qw).getRecords()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("username", log.getUsername());
            row.put("ip", log.getIp());
            row.put("source", log.getSource());
            row.put("sourceText", ClientType.of(log.getSource()).getLabel());
            boolean success = log.getSuccess() != null && log.getSuccess() == 1;
            row.put("success", success);
            row.put("resultText", success ? "成功" : "失败");
            row.put("userAgent", log.getUserAgent());
            row.put("createdAtText", log.getCreatedAt() == null ? "-" : FMT.format(log.getCreatedAt()));
            list.add(row);
        }
        return list;
    }
}
