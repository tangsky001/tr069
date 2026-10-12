package com.tr069.acs.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tr069.acs.domain.entity.SoapMessage;
import org.apache.ibatis.annotations.Mapper;

/**
 * SOAP 报文数据访问。
 */
@Mapper
public interface SoapMessageMapper extends BaseMapper<SoapMessage> {
}
