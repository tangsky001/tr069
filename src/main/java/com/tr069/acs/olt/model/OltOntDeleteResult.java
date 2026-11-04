package com.tr069.acs.olt.model;

import java.util.ArrayList;
import java.util.List;

/** 一块业务板清空 ONT 的结果：哪些口删掉了、哪几个口被设备顶回来了 */
public class OltOntDeleteResult {

    /** 板卡槽位号 */
    private Integer slot;

    /** 端口类型：gpon / epon */
    private String ponType;

    /** 删成功的端口 */
    private List<Integer> deletedPorts = new ArrayList<>();

    /** 设备拒绝了的端口 */
    private List<Integer> failedPorts = new ArrayList<>();

    /** 每条删除命令的回显，写采集记录用 */
    private List<String> outputs = new ArrayList<>();

    /** 这块板上删掉的 ONT 总数（服务层按库里快照算好后回填） */
    private Integer ontTotal;

    public Integer getSlot() {
        return slot;
    }

    public void setSlot(Integer slot) {
        this.slot = slot;
    }

    public String getPonType() {
        return ponType;
    }

    public void setPonType(String ponType) {
        this.ponType = ponType;
    }

    public List<Integer> getDeletedPorts() {
        return deletedPorts;
    }

    public void setDeletedPorts(List<Integer> deletedPorts) {
        this.deletedPorts = deletedPorts;
    }

    public List<Integer> getFailedPorts() {
        return failedPorts;
    }

    public void setFailedPorts(List<Integer> failedPorts) {
        this.failedPorts = failedPorts;
    }

    public List<String> getOutputs() {
        return outputs;
    }

    public void setOutputs(List<String> outputs) {
        this.outputs = outputs;
    }

    public Integer getOntTotal() {
        return ontTotal;
    }

    public void setOntTotal(Integer ontTotal) {
        this.ontTotal = ontTotal;
    }

    public boolean isEmpty() {
        return deletedPorts.isEmpty() && failedPorts.isEmpty();
    }
}
