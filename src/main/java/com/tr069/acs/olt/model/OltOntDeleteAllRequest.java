package com.tr069.acs.olt.model;

import lombok.Data;

import java.util.List;

/**
 * 「删除业务板上的全部 ONT」的表单。
 *
 * <p>槽位不写死：页面把当前采到的业务板槽位传上来（比如这台是 2 号槽位），
 * 后端再按库里的板卡复核一遍端口，逐口下发 {@code ont delete <端口> all}。</p>
 */
@Data
public class OltOntDeleteAllRequest {

    /** 只清这几块业务板（槽位号）；留空表示所有业务板 */
    private List<Integer> slots;
}
