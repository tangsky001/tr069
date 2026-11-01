package com.tr069.acs.olt.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 注册待注册 ONT 的请求。
 *
 * <p>两种用法：</p>
 * <ul>
 *   <li>页面手动：勾选几行 + 指定一条模板（{@link #templateId}），点「一键注册」；</li>
 *   <li>后台自动：{@link #templateId} 留空、{@link #rows} 留空，
 *       后端把自己扫到的待注册 ONT 按优先级匹配模板注册掉。</li>
 * </ul>
 */
@Data
public class OltOntRegisterRequest {

    /** 手动注册时指定的模板 ID；留空表示按优先级自动匹配 */
    private Long templateId;

    /** 要注册的 ONT；留空表示注册设备上当前所有待注册的 */
    private List<Row> rows = new ArrayList<>();

    /** true 时只编命令不下发，给页面预览用 */
    private boolean preview;

    /**
     * 一行待注册 ONT。字段跟「ONT 自动发现」那张表一致——页面把它看到的那一整行
     * 原样回传，后端不用再去设备上查一遍（查一遍要多等一个登录来回，
     * 而且查到的可能已经不是页面上那一批）。
     */
    @Data
    public static class Row {

        /** {@code display ont autofind} 回显里的 Number，{@code ont confirm} 用的就是它 */
        private Integer id;

        /** 框/槽/端口，如 0/2/0 */
        private String fsp;

        /** SN / MAC / LOID，按模板的注册方式取对应那个值 */
        private String sn;

        private String loid;

        private String password;

        private String model;

        private String vendorId;

        private String ponType;
    }
}
