/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.432
 *
 */

package com.PilotACS.olt.vendor;

/** 厂商适配层的会话能力：执行命令并返回回显 */
@FunctionalInterface
public interface OltCli {

    String run(String command, int timeoutMs);
}
