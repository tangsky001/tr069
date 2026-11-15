/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.cpe.model;

/**
 * 一个 CWMP 参数（参考 genieacs Attributes：object/writable/value/type/timestamp）
 *
 * @param path     全路径，如 InternetGatewayDevice.LANDevice.1.LANWLANConfiguration.1.SSID
 *                 对象节点以 '.' 结尾
 * @param value    值（统一存字符串；对象节点为 null）
 * @param type     xsd 类型：string/int/unsignedInt/boolean/dateTime...
 * @param object   是否对象节点
 * @param writable 是否可写
 * @param ts       取值时间戳（毫秒），用于新鲜度判断
 */
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
