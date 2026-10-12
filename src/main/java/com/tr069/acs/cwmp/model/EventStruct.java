package com.tr069.acs.cwmp.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Inform 事件结构，对应 {@code cwmp:EventStruct}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventStruct implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 事件码，如 "0 BOOTSTRAP"、"1 BOOT"、"2 PERIODIC"、"6 CONNECTION REQUEST"
     */
    private String eventCode;

    /**
     * 命令标识
     */
    private String commandKey;
}
