package com.tr069.acs.olt.vendor;

/**
 * 厂商适配层能用的那点会话能力：发一条命令，把回显拿回来。
 *
 * <p>各家厂商的自有逻辑（认厂商、拼命令、解析回显）只依赖这一个接口，不依赖
 * Telnet 会话本身——所以新增一个品牌不必改会话代码，测试里也能直接塞一个假实现，
 * 不用起 Telnet 服务。</p>
 */
@FunctionalInterface
public interface OltCli {

    /**
     * @param command    要发的命令
     * @param timeoutMs  等回显的超时
     * @return 设备回显；超时、连接断开或命令报错返回 null / 空串
     */
    String run(String command, int timeoutMs);
}
