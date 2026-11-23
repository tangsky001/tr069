/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.sip.queue;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.util.List;

/** 呼叫队列管理：CRUD 与呼叫匹配 */
@Service
public class SipQueueService {

    private final SipQueueMapper mapper;

    public SipQueueService(SipQueueMapper mapper) {
        this.mapper = mapper;
    }

    /** 全部队列（按队列号排序） */
    public List<SipQueue> list() {
        return mapper.selectList(new QueryWrapper<SipQueue>().orderByAsc("queue_no").orderByAsc("id"));
    }

    /** 保存/修改 */
    public void save(SipQueue queue) {
        String no = queue.getQueueNo() == null ? "" : queue.getQueueNo().trim();
        if (no.isEmpty()) {
            throw new IllegalArgumentException("队列号不能为空");
        }
        if (queue.getTargets() == null || queue.getTargets().trim().isEmpty()) {
            throw new IllegalArgumentException("目标分机不能为空");
        }
        queue.setQueueNo(no);
        queue.setTargets(normalizeList(queue.getTargets()));
        queue.setMembers(normalizeList(queue.getMembers()));
        if (queue.getId() == null) {
            mapper.insert(queue);
        } else {
            mapper.updateById(queue);
        }
    }

    /** 删除 */
    public void delete(Long id) {
        mapper.deleteById(id);
    }

    /**
     * 呼叫队列号匹配：先取成员包含主叫的队列（按 id 顺序），再取不限制成员的队列。
     * 同一队列号可配多条（如 0→8888 限定 8201 组，0→9999 限定 8301 组）。
     */
    public List<SipQueue> matchQueues(String callerExt, String queueNo) {
        List<SipQueue> memberMatch = new java.util.ArrayList<>();
        List<SipQueue> open = new java.util.ArrayList<>();
        for (SipQueue q : list()) {
            if (!q.getQueueNo().equals(queueNo)) {
                continue;
            }
            if (q.getMembers() == null || q.getMembers().isEmpty()) {
                open.add(q);
            } else if (containsExt(q.getMembers(), callerExt)) {
                memberMatch.add(q);
            }
        }
        memberMatch.addAll(open);
        return memberMatch;
    }

    /** 逗号串是否包含分机 */
    private boolean containsExt(String csv, String ext) {
        for (String s : csv.split(",")) {
            if (s.trim().equals(ext)) {
                return true;
            }
        }
        return false;
    }

    /** 逗号串规范化（去空项与空格） */
    private String normalizeList(String csv) {
        if (csv == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String s : csv.split(",")) {
            if (!s.trim().isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(s.trim());
            }
        }
        return sb.toString();
    }
}
