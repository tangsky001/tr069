package com.tr069.acs.olt.model;

import java.util.List;

/**
 * 一键开局的预演结果：这次会下发哪些命令，以及要注意什么。
 *
 * <p>命令由厂商 profile 现算，和实际下发走的是同一个
 * {@code quickSetupCommands}，所以页面上看到的必然就是即将下发的那条序列——
 * 以前前端自己拼一份预览，改了后端不改前端（或反过来）就会出现「预览对、下发错」。</p>
 *
 * @param commands 将要下发的命令，空表示没什么可做的
 * @param warnings 提醒（不是错误）：比如只改模式没配 VLAN
 */
public record OltQuickSetupPreview(List<String> commands, List<String> warnings) {
}
