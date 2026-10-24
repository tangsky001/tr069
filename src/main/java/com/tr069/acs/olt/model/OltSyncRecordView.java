package com.tr069.acs.olt.model;

import com.tr069.acs.olt.entity.OltSyncRecord;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 采集记录视图：列表只带原文片段，完整原文留给排查接口。
 */
@Data
public class OltSyncRecordView {

    private Long id;

    private String action;

    private Boolean success;

    private String message;

    private String rawPreview;

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
        String raw = record.getRawOutput();
        if (raw != null) {
            view.setRawPreview(raw.length() <= previewChars ? raw : raw.substring(0, previewChars) + "...");
        }
        return view;
    }
}
