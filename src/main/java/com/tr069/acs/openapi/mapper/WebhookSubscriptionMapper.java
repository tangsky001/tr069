package com.tr069.acs.openapi.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tr069.acs.openapi.entity.WebhookSubscription;
import org.apache.ibatis.annotations.Mapper;

/**
 * Webhook 订阅数据访问。
 */
@Mapper
public interface WebhookSubscriptionMapper extends BaseMapper<WebhookSubscription> {
}
