package com.tr069.acs.olt.vendor;

/** 厂商适配层能用的那点会话能力：发一条命令，把回显拿回来 */
@FunctionalInterface
public interface OltCli {

    
    String run(String command, int timeoutMs);
}
