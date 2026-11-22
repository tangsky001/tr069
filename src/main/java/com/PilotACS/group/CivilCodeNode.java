/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.560
 *
 */

package com.PilotACS.group;

import lombok.Data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 行政区划节点 */
@Data
public class CivilCodeNode {

    /** 区划编号 */
    private String code;

    /** 区划名称 */
    private String name;

    /** 上级编号 */
    private String parentCode;

    /** 层级：1=省，2=市，3=区县 */
    private int level;

    /** 下级区划 */
    private List<CivilCodeNode> children = new ArrayList<>();

    public CivilCodeNode(String code, String name, String parentCode) {
        this.code = code;
        this.name = name;
        this.parentCode = parentCode == null || parentCode.isBlank() ? null : parentCode;
        this.level = code.length() == 2 ? 1 : code.length() == 4 ? 2 : 3;
    }

    /** 只读子节点视图 */
    public List<CivilCodeNode> getChildren() {
        return Collections.unmodifiableList(children);
    }

    /** 建树用 */
    void addChild(CivilCodeNode child) {
        children.add(child);
    }
}
