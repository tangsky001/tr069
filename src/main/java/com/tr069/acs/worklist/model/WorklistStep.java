package com.tr069.acs.worklist.model;

import com.tr069.acs.cwmp.RpcMethod;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工单中的一个执行步骤，描述一次 CWMP RPC 调用。
 *
 * <p>步骤中的参数值支持 {@code ${参数名}} 占位符，执行前用工单入参替换；
 * 支持 {@code ${ctx.xxx}} 引用前序步骤存入上下文的结果。</p>
 */
@Data
public class WorklistStep {

    /** 步骤说明。 */
    private String comment;

    /**
     * 要调用的 CWMP 方法，书写方式与 SOAP 报文一致（如 SetParameterValues），
     * 便于现场新增适配时直接参考抓包结果。
     */
    private String rpc;

    /** AddObject / DeleteObject 使用的对象路径。 */
    private String objectName;

    /** GetParameterNames 使用的参数路径。 */
    private String parameterPath;

    /** GetParameterNames 是否递归，对应 NextLevel。 */
    private boolean nextLevel = false;

    /** SetParameterValues 的参数列表。 */
    private List<StepParameter> parameters = new ArrayList<>();

    /**
     * 其余 RPC 专用参数（CommandKey / FileType / URL / Username / Password /
     * DelaySeconds / FileSize 等），按 RPC 类型取用，避免模型臃肿。
     */
    private Map<String, Object> options = new LinkedHashMap<>();

    /**
     * 把本次 RPC 的执行结果存入上下文的变量名，
     * 后续步骤可用 {@code ${ctx.变量名}} 引用。
     */
    private String saveAs;

    /** 该步骤失败时是否中断工单。 */
    private boolean abortOnError = true;

    /** 单个步骤超时时间(秒)，0 表示使用全局配置。 */
    private int timeoutSeconds = 0;

    /**
     * 解析为 {@link RpcMethod} 枚举，未识别的方法名返回 null（由解析器报错）。
     */
    public RpcMethod resolveRpc() {
        return RpcMethod.of(rpc);
    }

    /**
     * 步骤中的单个参数。
     */
    @Data
    public static class StepParameter {
        /** 参数名（受管对象路径）。 */
        private String name;
        /** 参数值，支持 ${占位符}。 */
        private String value;
    }
}
