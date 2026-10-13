package com.tr069.acs.biz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tr069.acs.biz.entity.BizTemplateExecution;
import com.tr069.acs.biz.mapper.BizTemplateExecutionMapper;
import com.tr069.acs.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 业务模板下发记录查询。
 *
 * <p>下发动作依赖「意图模板 + 型号语义映射」生成真实参数，执行器尚未落地，
 * 这里只保留历史执行记录的查询，供页面回溯。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BizTemplateExecutionService {

    private final BizTemplateExecutionMapper executionMapper;

    /**
     * 单条执行记录。
     */
    public BizTemplateExecution get(Long id) {
        BizTemplateExecution execution = executionMapper.selectById(id);
        if (execution == null) {
            throw new BizException("下发记录不存在: " + id);
        }
        return execution;
    }

    /**
     * 下发记录列表，按时间倒序。
     */
    public List<BizTemplateExecution> list(String sn, Long templateId, int limit) {
        LambdaQueryWrapper<BizTemplateExecution> query = new LambdaQueryWrapper<>();
        if (sn != null && !sn.isBlank()) {
            query.eq(BizTemplateExecution::getSn, sn.trim());
        }
        if (templateId != null) {
            query.eq(BizTemplateExecution::getTemplateId, templateId);
        }
        query.orderByDesc(BizTemplateExecution::getId).last("limit " + Math.min(Math.max(limit, 1), 200));
        return executionMapper.selectList(query);
    }
}
