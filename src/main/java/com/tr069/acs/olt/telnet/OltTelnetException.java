package com.tr069.acs.olt.telnet;

import java.io.IOException;

/**
 * OLT 命令行交互失败。
 */
public class OltTelnetException extends IOException {

    private static final long serialVersionUID = 1L;

    public OltTelnetException(String message) {
        super(message);
    }

    public OltTelnetException(String message, Throwable cause) {
        super(message, cause);
    }
}
