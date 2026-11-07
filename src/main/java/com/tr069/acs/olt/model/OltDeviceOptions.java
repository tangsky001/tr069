package com.tr069.acs.olt.model;

import java.util.List;
import java.util.Map;

/** 页面要用的码表与阈值：厂商、字符集、默认端口账号、板卡档案、各类中文对照 */
public record OltDeviceOptions(List<OltOption> vendors, List<String> charsets,
                               int sshPort, int telnetPort, String defaultUsername, String defaultPassword,
                               List<OltBoardProfile> boardProfiles, Map<String, String> viewLabels,
                               List<OltOption> onuActions, Map<String, String> vlanTypeLabels,
                               Map<String, String> boardCategoryLabels, int defaultSlotCount,
                               int envTempAlarmC, OltCodeTables codeTables) {
}
