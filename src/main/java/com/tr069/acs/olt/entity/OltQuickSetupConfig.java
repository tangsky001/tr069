package com.tr069.acs.olt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一键开局参数。
 *
 * <p>这份参数原来存在浏览器 localStorage 里：换台电脑打开表单是空的，
 * 换个人填又是另一套。落库之后谁打开、哪台机器打开都是同一份，
 * 默认值也只由后端一处给出（见 {@code OltQuickSetupConfigService}）。</p>
 *
 * <p>字段随开局表单走（华为四套模板那一整套），所以整份存 JSON：
 * 加一个表单字段不用加列，跟 {@code olt_device.vlan_json} 一个路子。</p>
 */
@Data
@TableName("olt_quick_setup_config")
public class OltQuickSetupConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long oltId;

    /** 开局参数快照(JSON) */
    private String configJson;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
