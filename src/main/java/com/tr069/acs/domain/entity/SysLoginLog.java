package com.tr069.acs.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 登录日志：谁、什么时候、从哪个 IP、用什么端、成了没有。
 *
 * <p>失败的也要记。连着几十次失败是有人在试密码，只记成功的话这条线索就没了。</p>
 */
@Data
@TableName("sys_login_log")
public class SysLoginLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户 id，登录时连用户名都不存在就是 NULL。 */
    private Long userId;

    private String username;

    private String ip;

    /** WEB / CLIENT / MOBILE。 */
    private String source;

    private String userAgent;

    private Integer success;

    /** 用户不存在 / 密码错误 / 账号已禁用。 */
    private String failReason;

    private LocalDateTime createdAt;
}
