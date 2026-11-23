/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.sip.record;

import com.PilotACS.common.log.Logs;
import com.PilotACS.sip.SipProperties;
import com.PilotACS.sip.call.CallSession;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** 通话记录：呼叫结束落库、分页查询、录音下载 */
@Service
public class SipCallRecordService {

    private final SipCallRecordMapper mapper;
    private final SipProperties props;

    public SipCallRecordService(SipCallRecordMapper mapper, SipProperties props) {
        this.mapper = mapper;
        this.props = props;
    }

    /** 呼叫结束后写记录（CallManager 在会话首次结束时调用一次） */
    public void add(CallSession session, String endReason) {
        SipCallRecord r = new SipCallRecord();
        r.setCaller(session.getCallerExt());
        r.setCallee(session.getCalleeExt());
        r.setStartedAt(LocalDateTime.ofInstant(Instant.ofEpochMilli(session.getCreatedAt()), ZoneId.systemDefault()));
        r.setAnswered(session.getAnsweredAt() > 0);
        r.setDurationSeconds(session.getAnsweredAt() > 0
                ? (int) ((System.currentTimeMillis() - session.getAnsweredAt()) / 1000) : 0);
        r.setEndReason(endReason == null ? "" : endReason);
        r.setRecordPath(session.getRecordPath());
        try {
            mapper.insert(r);
        } catch (Exception e) {
            Logs.error(Logs.Module.SIP, "通话记录写入失败", "原因", e.getMessage());
        }
    }

    /** 分页查询：ext 同时匹配主叫或被叫 */
    public IPage<SipCallRecord> page(String ext, int page, int size) {
        QueryWrapper<SipCallRecord> qw = new QueryWrapper<>();
        if (ext != null && !ext.isBlank()) {
            qw.and(w -> w.eq("caller", ext.trim()).or().eq("callee", ext.trim()));
        }
        qw.orderByDesc("id");
        return mapper.selectPage(Page.of(Math.max(1, page), Math.max(1, Math.min(size, 200))), qw);
    }

    /** 解析录音文件绝对路径（限定在录音目录内，防目录穿越） */
    public Path resolveRecordFile(Long id) {
        SipCallRecord r = mapper.selectById(id);
        if (r == null || r.getRecordPath() == null || r.getRecordPath().isBlank()) {
            return null;
        }
        Path root = Paths.get(props.getRecordDir()).toAbsolutePath().normalize();
        Path file = Paths.get(r.getRecordPath()).toAbsolutePath().normalize();
        return file.startsWith(root) && Files.exists(file) ? file : null;
    }
}
