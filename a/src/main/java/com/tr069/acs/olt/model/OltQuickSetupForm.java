package com.tr069.acs.olt.model;

import java.util.List;

/** 一键开局表单的取值约束和默认值 */
public record OltQuickSetupForm(boolean supported, List<String> modes, String defaultMode,
                                List<String> portTypes, List<String> defaultPortTypes, int maxPorts,
                                int vlanMin, int vlanMax, OltTr069ProfileRequest tr069Defaults) {
}
