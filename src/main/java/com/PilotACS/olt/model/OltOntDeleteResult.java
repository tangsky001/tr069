/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.129
 *
 */

package com.PilotACS.olt.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;

/** 批量删除 ONT 结果 */
public class OltOntDeleteResult {

    @Schema(description = "板卡槽位号")
    private Integer slot;

    @Schema(description = "端口类型：gpon / epon")
    private String ponType;

    @Schema(description = "删成功的端口")
    private List<Integer> deletedPorts = new ArrayList<>();

    @Schema(description = "设备拒绝了的端口")
    private List<Integer> failedPorts = new ArrayList<>();

    @Schema(description = "每条删除命令的回显，写采集记录用")
    private List<String> outputs = new ArrayList<>();

    @Schema(description = "这块板上删掉的 ONT 总数（服务层按库里快照算好后回填）")
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
