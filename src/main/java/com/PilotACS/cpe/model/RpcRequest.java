/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.cpe.model;

import lombok.Data;

import java.util.List;
import java.util.Map;

/** ACS 下发给 CPE 的一条 RPC 请求（会话里同时只有一条在飞） */
@Data
public class RpcRequest {

    public enum Kind {
        GPN, GPV, SPV, ADD_OBJECT, DELETE_OBJECT,
        REBOOT, FACTORY_RESET, DOWNLOAD, GET_RPC_METHODS, CUSTOM
    }

    private Kind kind;
    /** CUSTOM 时的方法名 */
    private String method;
    private String id;
    /** 任务号 */
    private String key;

    private String path;
    private boolean nextLevel;

    private List<String> names;

    private List<NameValue> values;

    private long instanceNumber;
    private String fileType;
    private String url;
    private String fileName;
    private long fileSize;
    private String username;
    private String password;

    private Map<String, Object> args;

    public static RpcRequest gpn(String path, boolean nextLevel, String key) {
        RpcRequest r = new RpcRequest();
        r.kind = Kind.GPN;
        r.path = path;
        r.nextLevel = nextLevel;
        r.key = key;
        return r;
    }

    public static RpcRequest gpv(List<String> names, String key) {
        RpcRequest r = new RpcRequest();
        r.kind = Kind.GPV;
        r.names = names;
        r.key = key;
        return r;
    }

    public static RpcRequest spv(List<NameValue> values, String key) {
        RpcRequest r = new RpcRequest();
        r.kind = Kind.SPV;
        r.values = values;
        r.key = key;
        return r;
    }

    public static RpcRequest of(Kind kind, String key) {
        RpcRequest r = new RpcRequest();
        r.kind = kind;
        r.key = key;
        return r;
    }

    public static RpcRequest download(String key, String fileType, String url, String fileName, long fileSize) {
        RpcRequest r = new RpcRequest();
        r.kind = Kind.DOWNLOAD;
        r.key = key;
        r.fileType = fileType;
        r.url = url;
        r.fileName = fileName;
        r.fileSize = fileSize;
        return r;
    }

    public static RpcRequest custom(String method, Map<String, Object> args, String key) {
        RpcRequest r = new RpcRequest();
        r.kind = Kind.CUSTOM;
        r.method = method;
        r.args = args;
        r.key = key;
        return r;
    }

    /** 任务 key（task-<id>） */
    public String taskKey() {
        return key == null ? "" : key;
    }

    /** 值对 */
    public record NameValue(String name, String value, String type) {
    }
}
