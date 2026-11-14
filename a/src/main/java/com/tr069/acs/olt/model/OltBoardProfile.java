package com.tr069.acs.olt.model;

import java.util.List;

/** 板卡型号档案：这块板的面板长什么样、几个口、口是什么类型 */
public record OltBoardProfile(String code, String label, String panel, String portMode,
                              Integer ports, boolean controlBoard, List<String> keywords) {
}
