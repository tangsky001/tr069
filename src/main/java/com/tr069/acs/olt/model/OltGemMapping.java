package com.tr069.acs.olt.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** GEM port 内的一条映射（{@code display ont-lineprofile gpon profile-id X} 的映射表格行） */
@Data
public class OltGemMapping {

    /** GEM 内映射序号（页面「GEM 映射ID」） */
    private Integer index;

    /** 映射的 VLAN */
    private String vlan;
}
