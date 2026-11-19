/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.cpe.model;

/** 一个 CWMP 参数 */
public record CpeParam(String path, String value, String type, boolean object, boolean writable, long ts) {

    public static final long TS_UNKNOWN = 0;

    public CpeParam {
        if (path != null && object && !path.endsWith(".")) {
            path = path + ".";
        }
    }

    public boolean isObject() {
        return object;
    }

    public boolean isLeaf() {
        return !object;
    }

    /** 取上级对象路径，根返回 null */
    public String parentPath() {
        if (path == null) {
            return null;
        }
        int cut = path.lastIndexOf('.', path.length() - 2);
        return cut <= 0 ? null : path.substring(0, cut + 1);
    }
}
