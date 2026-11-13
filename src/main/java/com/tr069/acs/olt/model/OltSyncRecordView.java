package com.tr069.acs.olt.model;

import com.tr069.acs.olt.entity.OltSyncRecord;
import lombok.Data;

import java.time.LocalDateTime;

/** 采集记录视图：只带结果摘要，不再携带设备回显（raw_output 已退出台账） */
@Data
public class OltSyncRecordView {

    private Long id;

    private String action;

    private Boolean success;

    private String message;

    private Integer costMs;

    private String operator;

    private LocalDateTime createdAt;

    public static OltSyncRecordView from(OltSyncRecord record, int previewChars) {
        if (record == null) {
            return null;
        }
        OltSyncRecordView view = new OltSyncRecordView();
        view.setId(record.getId());
        view.setAction(record.getAction());
        view.setSuccess(record.getSuccess());
        view.setMessage(record.getMessage());
        view.setCostMs(record.getCostMs());
        view.setOperator(record.getOperator());
        view.setCreatedAt(record.getCreatedAt());
        return view;
    }
}
