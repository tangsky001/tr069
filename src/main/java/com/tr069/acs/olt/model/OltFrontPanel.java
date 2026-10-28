package com.tr069.acs.olt.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 整机前面板：一体化 OLT（鸿升光这类）没有板卡插框，型号直接决定面板上有什么口。
 *
 * <p>前端据此画「一台设备的前面板」：左边型号，PON 口一排，右边上联 GE / XGE、
 * NMS、CONSOLE。以前只有「几个 PON 口」，上行口在页面上完全看不见。</p>
 *
 * <p>用普通类而不是 record：这块数据会随板卡一起写进设备行的 JSON 快照，
 * 读回来要能反序列化，普通类不依赖编译参数里的参数名。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OltFrontPanel {

    /** 型号，如 {@code HSGQ-XE08R} */
    private String model;

    /** PON 口类型：GPON / EPON */
    private String ponType;

    /** PON 口数 */
    private int ponCount;

    /** 上联 GE 口数（RJ45） */
    private int geCount;

    /** 上联 10GE 口数（SFP+） */
    private int xgeCount;

    /** 有没有独立 NMS 管理口 */
    private boolean hasNms;

    /** 有没有 CONSOLE 口 */
    private boolean hasConsole;

    /** 有没有 USB 口 */
    private boolean hasUsb;

    /** 规格说明：{@code 8 × GPON + 4 × GE + 4 × XGE} */
    private String summary;
}
