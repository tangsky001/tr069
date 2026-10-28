package com.tr069.acs.olt.model;

import java.util.List;

/**
 * 板卡型号档案：这块板的面板长什么样、几个口、口是什么类型。
 *
 * <p>华为 {@code display board 0} 只回板卡名与状态，不带端口数；面板又得知道
 * 画 PON 口还是 GE 口。以前这份档案整张写在页面里（型号关键字匹配也在页面），
 * 现在由服务端按型号算好随板卡一起给，页面只管画。</p>
 *
 * @param code         型号，如 {@code H807GPBD}
 * @param label        中文说明
 * @param panel        面板样式：pon / ge / xge / generic（页面据此画 SVG）
 * @param portMode     端口类型：GPON / EPON / GE / 10GE
 * @param ports        端口数（设备回显没给时按这个兜底）
 * @param controlBoard 是不是主控板
 * @param keywords     型号里命中任一关键字就算这块板；注意顺序，精确的要排在前面
 */
public record OltBoardProfile(String code, String label, String panel, String portMode,
                              Integer ports, boolean controlBoard, List<String> keywords) {
}
