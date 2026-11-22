/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.923
 *
 */

package com.PilotACS.service;

import com.PilotACS.common.log.Logs;
import com.PilotACS.common.security.LoginUser;
import com.PilotACS.common.security.UserContext;
import com.PilotACS.common.utils.IpUtil;
import com.PilotACS.entity.SysOperLog;
import com.PilotACS.mapper.SysOperLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 操作审计日志服务：修改密码、新建/删除分组、移动设备、ACL 授权等关键动作 */
@Service
public class SysOperLogService extends ServiceImpl<SysOperLogMapper, SysOperLog> implements InitializingBean {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter INPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static final String CREATE_SQL = """
            CREATE TABLE IF NOT EXISTS `sys_oper_log` (
                `id`         BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
                `username`   VARCHAR(64)   NOT NULL COMMENT '操作人登录名，系统动作为 system',
                `module`     VARCHAR(32)   NOT NULL COMMENT '模块：用户/分组/设备权限/OLT',
                `action`     VARCHAR(64)   NOT NULL COMMENT '动作：修改密码/新建分组/删除分组/移动设备',
                `detail`     VARCHAR(500)  DEFAULT NULL COMMENT '详情',
                `ip`         VARCHAR(64)   DEFAULT NULL COMMENT '来源 IP',
                `created_at` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (`id`),
                KEY `idx_oper_user` (`username`),
                KEY `idx_oper_created` (`created_at`)
            ) ENGINE = InnoDB COMMENT '操作审计日志'
            """;

    private final JdbcTemplate jdbcTemplate;

    public SysOperLogService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 启动时自动补建表 */
    @Override
    public void afterPropertiesSet() {
        try {
            jdbcTemplate.execute(CREATE_SQL);
            Logs.info(Logs.Module.SYSTEM, "操作日志表已就绪", "表", "sys_oper_log");
        } catch (Exception e) {
            Logs.error(Logs.Module.SYSTEM, "操作日志表自动建表失败，请手工执行 sql/mysql.sql", e);
        }
    }

    /** 记录一条操作日志 */
    public void log(String module, String action, String detail) {
        try {
            SysOperLog entry = new SysOperLog();
            LoginUser user = UserContext.get();
            entry.setUsername(user == null ? "system" : user.getUsername());
            entry.setModule(module);
            entry.setAction(action);
            entry.setDetail(detail);
            entry.setIp(currentIp());
            entry.setCreatedAt(LocalDateTime.now());
            this.save(entry);
        } catch (Exception e) {
            Logs.warn(Logs.Module.SYSTEM, "操作日志写入失败", e, "模块", module, "动作", action);
        }
    }

    /** 按条件查询操作日志 */
    public List<Map<String, Object>> search(Long limit, String module, String username,
                                            String keyword, String from, String to) {
        LambdaQueryWrapper<SysOperLog> qw = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(module)) {
            qw.eq(SysOperLog::getModule, module);
        }
        if (StringUtils.hasText(username)) {
            qw.eq(SysOperLog::getUsername, username);
        }
        if (StringUtils.hasText(keyword)) {
            qw.and(w -> w.like(SysOperLog::getAction, keyword).or().like(SysOperLog::getDetail, keyword));
        }
        try {
            if (StringUtils.hasText(from)) {
                qw.ge(SysOperLog::getCreatedAt, LocalDateTime.parse(from, INPUT));
            }
            if (StringUtils.hasText(to)) {
                qw.le(SysOperLog::getCreatedAt, LocalDateTime.parse(to, INPUT));
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("时间格式应为 yyyy-MM-ddTHH:mm:ss");
        }
        qw.orderByDesc(SysOperLog::getId);
        long size = (limit == null || limit <= 0) ? 200 : Math.min(limit, 1000);
        List<Map<String, Object>> list = new ArrayList<>();
        for (SysOperLog log : this.page(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, size), qw).getRecords()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("username", log.getUsername());
            row.put("module", log.getModule());
            row.put("action", log.getAction());
            row.put("detail", log.getDetail());
            row.put("ip", log.getIp());
            row.put("createdAtText", log.getCreatedAt() == null ? "-" : FMT.format(log.getCreatedAt()));
            list.add(row);
        }
        return list;
    }

    private String currentIp() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return "-";
        }
        HttpServletRequest request = attrs.getRequest();
        try {
            return IpUtil.getClientIp(request);
        } catch (Exception e) {
            return "-";
        }
    }
}
