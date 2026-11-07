package com.tr069.acs.acs.adaptation;

import com.tr069.acs.acs.model.Isp;

import java.util.List;

/** 运营商适配策略：每家运营商一个实现，各自的定制参数差异收在自己的类里 */
public interface IspAdaptation {

    Isp isp();

    default String code() {
        return isp().getCode();
    }

    default String label() {
        return isp().getLabel();
    }

    /** 型号档案目录名：CM / CT / CU */
    default String dir() {
        return isp().getDir();
    }

    /** 简短名，用于下拉选项：移动 / 电信 / 联通 */
    default String shortLabel() {
        return isp().getLabel();
    }

    /** CWMP 私有参数前缀，用于从能力树识别运营商 */
    default List<String> prefixes() {
        Isp isp = isp();
        if (isp.getPrefix().isEmpty()) {
            return List.of();
        }
        return isp.getAltPrefix().isEmpty()
                ? List.of(isp.getPrefix())
                : List.of(isp.getPrefix(), isp.getAltPrefix());
    }

    /** 定制固件的超管账号节点，相对根节点带尾点；没有定制节点返回空 */
    default List<String> adminAccountNodes() {
        return List.of();
    }
}
