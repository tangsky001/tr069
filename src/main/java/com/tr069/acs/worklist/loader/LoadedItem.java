package com.tr069.acs.worklist.loader;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.nio.file.Path;

/**
 * 一个配置文件的加载结果。
 *
 * <p>即使解析失败也保留条目，这样管理端可以看到「哪一档案有问题」以及原因，
 * 而不是让文件静默消失。</p>
 *
 * @param <T> 配置对象类型
 */
@Data
@AllArgsConstructor
public class LoadedItem<T> {

    /** 配置文件绝对路径。 */
    private Path file;

    /** 相对配置根目录的路径，作为管理端展示用标识。 */
    private String relativePath;

    /** 解析成功的对象，失败时为 null。 */
    private T value;

    /** 文件最后修改时间（毫秒），用于判断是否变化。 */
    private long lastModified;

    /** 解析错误描述，成功时为 null。 */
    private String error;

    /**
     * 是否解析成功。
     */
    public boolean isValid() {
        return error == null && value != null;
    }

    /**
     * 构造成功的加载结果。
     */
    public static <T> LoadedItem<T> ok(Path file, String relativePath, T value, long lastModified) {
        return new LoadedItem<>(file, relativePath, value, lastModified, null);
    }

    /**
     * 构造失败的加载结果。
     */
    public static <T> LoadedItem<T> fail(Path file, String relativePath, String error, long lastModified) {
        return new LoadedItem<>(file, relativePath, null, lastModified, error);
    }
}
