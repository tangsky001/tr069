/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.742
 *
 */

package com.PilotACS.olt.model;

import java.util.List;

/** 一键开局的预演结果：这次会下发哪些命令，以及要注意什么；error 非空表示参数还没填全——预演阶段不弹错，提交时由后端拦截 */
public record OltQuickSetupPreview(List<String> commands, List<String> warnings, String error) {

    public OltQuickSetupPreview(List<String> commands, List<String> warnings) {
        this(commands, warnings, null);
    }
}
