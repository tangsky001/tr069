package com.tr069.acs.olt.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 整机前面板：一体化 OLT（鸿升光这类）没有板卡插框，型号直接决定面板上有什么口 */
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
