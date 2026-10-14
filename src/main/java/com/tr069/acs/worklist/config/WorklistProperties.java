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
     *   models/      型号能力树 {isp}/{型号标识}.xml，设备上报的参数树按型号导出的归档
     * </pre>
     * 配置模板已改为入库的意图模板（config_template），不再以文件形式存放。
     */
    private String rootPath = "./target/configuration";

    /**
     * 目录为空时是否解包内置示例。已无内置示例可解包：示例资源不进 jar，
     * 光猫档案与能力树全部来自现场适配产出，置 true 也不会写出任何文件。
     */
    private boolean initSampleIfEmpty = false;

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
