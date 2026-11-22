/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.446
 *
 */

package com.PilotACS.olt.model;

import com.PilotACS.olt.entity.OltSyncRecord;
import lombok.Data;

import java.time.LocalDateTime;

/** 采集记录视图 */
@Data
public class OltSyncRecordView {

    private Long id;

    private String action;

    private Boolean success;

    private String message;

    private Integer costMs;

    private String operator;

    private LocalDateTime createdAt;

    /** 由采集记录实体转视图 */
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
