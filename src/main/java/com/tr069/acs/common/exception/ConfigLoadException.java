package com.tr069.acs.common.exception;

/**
 * 工单 / 光猫适配配置文件解析失败时抛出。
 */
public class ConfigLoadException extends RuntimeException {

    private final String sourceFile;

    public ConfigLoadException(String sourceFile, String message) {
        super(message);
        this.sourceFile = sourceFile;
    }

    public ConfigLoadException(String sourceFile, String message, Throwable cause) {
        super(message, cause);
        this.sourceFile = sourceFile;
    }

    /**
     * 出错的配置文件（相对路径），用于热加载时定位。
     */
    public String getSourceFile() {
        return sourceFile;
    }
}
