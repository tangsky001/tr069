package com.tr069.acs.olt.vendor.Hsgq;

import com.tr069.acs.olt.model.OltFrontPanel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 鸿升光整机型号规格：一体化 OLT 没有板卡，型号直接决定前面板长什么样 */
public final class HsgqModelCatalog {

    private HsgqModelCatalog() {
    }

    /** 一款整机的规格 */
    public record Spec(String code, String label, String ponType, int ponCount,
                       int geCount, int xgeCount, boolean hasNms, boolean hasConsole, boolean hasUsb) {

        /** 面板上的规格说明：{@code 8 × GPON + 4 × GE + 4 × XGE} */
        public String summary() {
            List<String> parts = new ArrayList<>();
            parts.add(ponCount + " × " + ponType);
            if (geCount > 0) {
                parts.add(geCount + " × GE");
            }
            if (xgeCount > 0) {
                parts.add(xgeCount + " × XGE");
            }
            if (hasNms) {
                parts.add("NMS");
            }
            if (hasConsole) {
                parts.add("CONSOLE");
            }
            if (hasUsb) {
                parts.add("USB");
            }
            return String.join(" + ", parts);
        }
    }

    private static final List<Spec> SPECS = List.of(
            new Spec("HSGQ-XE08R", "HSGQ-XE08R", "GPON", 8, 4, 4, true, true, false),
            new Spec("HSGQ-G16R", "HSGQ-G16R", "GPON", 16, 4, 2, true, true, false),
            new Spec("HSGQ-G16L", "HSGQ-G16L", "GPON", 16, 4, 2, false, true, false),
            new Spec("HSGQ-G08R", "HSGQ-G08R", "GPON", 8, 4, 4, true, true, false),
            new Spec("HSGQ-G08L", "HSGQ-G08L", "GPON", 8, 4, 2, false, true, false),
            new Spec("HSGQ-G04R", "HSGQ-G04R", "GPON", 4, 4, 4, true, true, true),
            new Spec("HSGQ-G04L", "HSGQ-G04L", "GPON", 4, 4, 2, false, true, false),
            new Spec("Neutral-G16", "Neutral-G16", "GPON", 16, 4, 2, false, true, false),
            new Spec("Neutral-G08", "Neutral-G08", "GPON", 8, 4, 2, false, true, false),
            new Spec("Neutral-G04", "Neutral-G04", "GPON", 4, 4, 2, false, true, false),
            // EPON 那条产品线：4 个 GE 上联，没有 10GE 光口
            new Spec("HSGQ-E16", "HSGQ-E16", "EPON", 16, 4, 0, false, true, false),
            new Spec("HSGQ-E08", "HSGQ-E08", "EPON", 8, 4, 0, false, true, false),
            new Spec("HSGQ-E04", "HSGQ-E04", "EPON", 4, 4, 0, false, true, false));

    /** 按型号找规格，找不到返回 null */
    public static Spec match(String model) {
        if (model == null || model.isBlank()) {
            return null;
        }
        String text = model.trim().toUpperCase(Locale.ROOT);
        for (Spec spec : SPECS) {
            if (text.contains(spec.code().toUpperCase(Locale.ROOT))) {
                return spec;
            }
        }
        return null;
    }

    /** 按型号给出整机前面板规格；不是鸿升光整机（型号不认识）返回 null */
    public static OltFrontPanel frontPanelOf(String model) {
        if (model == null || model.isBlank()) {
            return null;
        }
        String text = model.trim().toUpperCase(Locale.ROOT);
        if (!text.startsWith("HSGQ-") && !text.startsWith("NEUTRAL-")) {
            return null;
        }
        Spec spec = match(text);
        if (spec == null) {
            return null;
        }
        return new OltFrontPanel(spec.code(), spec.ponType(), spec.ponCount(),
                spec.geCount(), spec.xgeCount(), spec.hasNms(), spec.hasConsole(), spec.hasUsb(),
                spec.summary());
    }

    /** 型号认不出来时按 PON 口数和类型兜底：面板上至少 PON 口是对的 */
    public static Spec fallback(String ponType, int ponCount) {
        String type = ponType == null || ponType.isBlank() ? "GPON" : ponType.toUpperCase(Locale.ROOT);
        int count = Math.max(ponCount, 1);
        return new Spec("HSGQ-" + type.charAt(0) + String.format("%02d", count),
                "鸿升光整机", type, count, 4, 2, false, true, false);
    }
}
