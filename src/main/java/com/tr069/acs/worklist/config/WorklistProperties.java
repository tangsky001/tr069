package com.tr069.acs.worklist.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 光猫适配 / 工单配置的属性。
 *
 * <p>配置根目录位于 jar 外部，因此新增或修改光猫适配无需重新打包，
 * 也确保了运行时配置不属于构建产物。</p>
 */
@Data
@ConfigurationProperties(prefix = "acs.worklist")
public class WorklistProperties {

    /** 是否启用工单配置模块。 */
    private boolean enabled = true;

    /**
     * 配置根目录。相对路径以进程工作目录为基准。
     * 目录下约定：
     * <pre>
     *   profiles/    光猫适配档案 *.yaml
     *   operators/   工单模板 {isp}/{version}/{domain}/{deviceType}/{name}.xml
     * </pre>
     * 存量工单是 xml（由原 Python 版 data.py 批量转换而来），手工新增可用 yaml，两种后缀都会扫描。
     */
    private String rootPath = "./target/configuration";

    /** 根目录下缺失子目录时，是否自动创建并写出示例，方便现场快速适配新光猫。 */
    private boolean initSampleIfEmpty = true;

    /** 热加载相关配置。 */
    private HotReload hotReload = new HotReload();

    /**
     * 热加载配置。
     */
    @Data
    public static class HotReload {

        /** 是否开启热加载。 */
        private boolean enabled = true;

        /**
         * 文件变化后的合并去抖时间(毫秒)。
         * 编辑器保存一次可能触发多个事件，需合并后统一重载。
         */
        private long debounceMillis = 800;

        /**
         * 定时兜底扫描间隔(秒)。WatchService 在网络盘/Docker 挂载场景可能丢事件，
         * 用周期扫描兜底。设为 0 关闭。
         */
        private long rescanIntervalSeconds = 60;

        /** 变更后校验失败时是否保留上一份配置（true 更安全）。 */
        private boolean keepLastOnError = true;
    }
}
