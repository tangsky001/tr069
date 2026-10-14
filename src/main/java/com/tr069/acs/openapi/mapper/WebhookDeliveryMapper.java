package com.tr069.acs.openapi.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tr069.acs.openapi.entity.WebhookDelivery;
import org.apache.ibatis.annotations.Mapper;

/**
 * 事件投递记录数据访问。
 */
@Mapper
public interface WebhookDeliveryMapper extends BaseMapper<WebhookDelivery> {
}
