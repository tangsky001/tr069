package com.tr069.acs.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * RPC 执行记录。
 */
@Data
@TableName("rpc_record")
public class RpcRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String rpcName;

    private String sn;

    private Long worklistId;

    /**
     * 请求参数，JSON 序列化
     */
    private String requestArgs;

    /**
     * 结果内容
     */
    private String resultContent;

    /**
     * init / success / fail / timeout
     */
    private String status;

    private LocalDateTime startTime;

    private LocalDateTime finishTime;

    private LocalDateTime createdAt;
}
