package com.PilotACS.olt.model;

import java.util.List;

/** 一键开局的预演结果：这次会下发哪些命令，以及要注意什么 */
public record OltQuickSetupPreview(List<String> commands, List<String> warnings) {
}
