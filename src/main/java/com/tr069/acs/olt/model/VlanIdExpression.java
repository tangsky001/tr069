package com.tr069.acs.olt.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 页面上填的 VLAN ID 表达式：{@code 100}、{@code 100-200}、{@code 100,102,200-210}。
 *
 * <p>设备一条命令能建一段连续 VLAN（{@code vlan standard 101 110}），但配成员端口得逐个 VLAN
 * 进视图，所以这里既给出「连续段」（拼建 VLAN 的命令），也能展开成一个个 ID（配端口用）。</p>
 *
 * <p>规则：1-4094、允许 {@code ,} 和 {@code -}、段必须前小后大；写错直接抛
 * {@link IllegalArgumentException}，消息就是给用户看的那句话。</p>
 */
public final class VlanIdExpression {

    /** VLAN ID 上限（手册：取值 1-4094） */
    public static final int MAX_VLAN_ID = 4094;

    private VlanIdExpression() {
    }

    /**
     * 切成连续段，按填写顺序保留、自动去重排序：{@code 200-210,100} → [[100,100],[200,210]]。
     *
     * @throws IllegalArgumentException 表达式为空、含非法字符或 ID 越界
     */
    public static List<int[]> segments(String text) {
        List<Integer> ids = expand(text);
        List<int[]> segments = new ArrayList<>();
        int start = ids.get(0);
        int prev = start;
        for (int i = 1; i < ids.size(); i++) {
            int current = ids.get(i);
            if (current == prev + 1) {
                prev = current;
                continue;
            }
            segments.add(new int[]{start, prev});
            start = current;
            prev = current;
        }
        segments.add(new int[]{start, prev});
        return segments;
    }

    /**
     * 展开成具体 ID，升序去重。
     *
     * @throws IllegalArgumentException 表达式为空、含非法字符或 ID 越界
     */
    public static List<Integer> expand(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("请填写 VLAN ID，例如 100 或 100-200,300");
        }
        Set<Integer> ids = new LinkedHashSet<>();
        for (String part : text.split("[,，]")) {
            String piece = part.trim();
            if (piece.isEmpty()) {
                continue;
            }
            int dash = piece.indexOf('-');
            if (dash < 0) {
                ids.add(parseId(piece));
                continue;
            }
            String from = piece.substring(0, dash).trim();
            String to = piece.substring(dash + 1).trim();
            if (from.isEmpty() || to.isEmpty()) {
                throw new IllegalArgumentException("VLAN 范围写法不对：" + piece + "，应该像 100-200");
            }
            int start = parseId(from);
            int end = parseId(to);
            if (end < start) {
                throw new IllegalArgumentException("VLAN 范围 " + piece + " 前后写反了，应该是 小-大");
            }
            for (int id = start; id <= end; id++) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("请填写 VLAN ID，例如 100 或 100-200,300");
        }
        List<Integer> sorted = new ArrayList<>(ids);
        sorted.sort(null);
        return sorted;
    }

    private static int parseId(String piece) {
        int id;
        try {
            id = Integer.parseInt(piece);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("VLAN ID 只能是数字：" + piece);
        }
        if (id < 1 || id > MAX_VLAN_ID) {
            throw new IllegalArgumentException("VLAN ID 必须在 1-" + MAX_VLAN_ID + " 之间：" + id);
        }
        return id;
    }
}
