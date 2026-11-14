package com.tr069.acs.olt.model;

import lombok.Data;

import java.util.List;

/** 「删除业务板上的全部 ONT」的表单 */
@Data
public class OltOntDeleteAllRequest {

    /** 只清这几块业务板（槽位号）；留空表示所有业务板 */
    private List<Integer> slots;
}
