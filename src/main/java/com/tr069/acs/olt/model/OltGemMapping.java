package com.tr069.acs.olt.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * GEM port 内的一条映射（{@code display ont-lineprofile gpon profile-id X} 的映射表格行）：
 *
 * <pre>{@code
 * Mapping VLAN Priority Port type Port ID Bundle ID Flow CAR Transparent
 * 0       45  -       -         -       -        -    -
 * }</pre>
 *
 * index 是 GEM 内映射序号，vlan 是映射的 VLAN；一个 GEM port 可以有多条映射。
 */
@Data
public class OltGemMapping {

    /** GEM 内映射序号（页面「GEM 映射ID」） */
    private Integer index;

    /** 映射的 VLAN */
    private String vlan;
}
