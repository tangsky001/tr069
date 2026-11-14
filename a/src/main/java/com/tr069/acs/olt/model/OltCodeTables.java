package com.tr069.acs.olt.model;

import java.util.List;
import java.util.Map;

/** OLT 页面的展示码表与业务映射，随 {@code GET /olt/options} 一次下发 */
public record OltCodeTables(
        Map<String, String> ontKeyLabels,
        Map<String, String> ontCapValues,
        Map<String, String> ontAuthValues,
        Map<String, String> ontInfoValues,
        Map<String, String> vlanAttrs,
        Map<String, String> ontDownReasons,
        Map<String, String> authModeLabels,
        List<OltOption> authModes,
        List<OltOption> workModes,
        List<OltOption> ponTypes,
        Map<String, String> portTitles,
        Map<String, String> charsetLabels,
        Map<String, String> tabSections) {
}
