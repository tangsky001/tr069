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

/**
 * 行政区划节点（civilCode.csv 一行）。
 *
 * <p>编号沿用国标层级：省 2 位（11）、市 2+2 位（1101）、区县 2+2+2 位（110101），
 * 上级编号把三级行政区串成树，分组管理直接拿它当分组树的骨架。</p>
 */
@Data
public class CivilCodeNode {

    /** 区划编号：11 / 1101 / 110101 */
    private String code;

    /** 区划名称：北京市 / 市辖区 / 东城区 */
    private String name;

    /** 上级编号；省级（根节点）为空 */
    private String parentCode;

    /** 层级：1=省，2=市，3=区县（按编号长度推） */
    private int level;

    /** 下级区划，叶子为空列表 */
    private List<CivilCodeNode> children = new ArrayList<>();

    public CivilCodeNode(String code, String name, String parentCode) {
        this.code = code;
        this.name = name;
        this.parentCode = parentCode == null || parentCode.isBlank() ? null : parentCode;
        this.level = code.length() == 2 ? 1 : code.length() == 4 ? 2 : 3;
    }

    /** 只读子节点视图，防止外部改坏树结构 */
    public List<CivilCodeNode> getChildren() {
        return Collections.unmodifiableList(children);
    }

    /** 建树用：只有本包的加载逻辑可以挂子节点 */
    void addChild(CivilCodeNode child) {
        children.add(child);
    }
}
