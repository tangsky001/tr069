package com.tr069.acs.acs.session;

/**
 * CPE 会话状态机。
 *
 * <p>对齐 Python 版 cpe.py 的状态流转：</p>
 * <pre>
 *   INIT --收到 Inform--> WAIT_POST_NULL --收到空 POST 且有任务--> WAIT_RPC_RESPONSE
 *                              ^                                            |
 *                              |                                    收到 RPC 响应/204
 *                              +----------- READY <------------------------+
 * </pre>
 */
public enum SessionState {

    /**
     * 初始状态，尚未收到 Inform
     */
    INIT,

    /**
     * 已回复 InformResponse，等待 CPE 发来空 POST
     */
    WAIT_POST_NULL,

    /**
     * 已通过空 POST 的响应体下发 RPC，等待 CPE 回应 RPC 结果
     */
    WAIT_RPC_RESPONSE,

    /**
     * 空闲，无未完成的交互
     */
    READY,

    /**
     * 会话已关闭，等待清理
     */
    CLOSED
}
