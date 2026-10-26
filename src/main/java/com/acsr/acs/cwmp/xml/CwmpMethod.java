package com.acsr.acs.cwmp.xml;

import java.util.Arrays;

/** TR-069 CWMP 方法枚举。CPE 发来的 XxxResponse 与 Xxx 归并到同一个枚举值。 */
public enum CwmpMethod {
  Empty,
  Fault,
  Inform,
  GetRPCMethods,
  GetParameterNames,
  GetParameterValues,
  SetParameterValues,
  GetParameterAttributes,
  SetParameterAttributes,
  AddObject,
  DeleteObject,
  Download,
  Upload,
  Reboot,
  FactoryReset,
  TransferComplete,
  AutonomousTransferComplete,
  ScheduleInform,
  RequestDownload,
  DUStateChangeComplete;

  private static final String RESPONSE_POSTFIX = "Response";

  public static CwmpMethod fromString(String name) {
    if (name == null) {
      return Empty;
    }
    String raw = name.trim();
    return Arrays.stream(values())
        .filter(m -> raw.equals(m.name()) || raw.equals(m.name() + RESPONSE_POSTFIX))
        .findFirst()
        .orElse(Empty);
  }

  /** ACS 主动下发的请求方法（非 CPE 响应）。 */
  public boolean isAcsRequest() {
    return switch (this) {
      case GetRPCMethods, GetParameterNames, GetParameterValues, SetParameterValues,
          GetParameterAttributes, SetParameterAttributes, AddObject, DeleteObject,
          Download, Upload, Reboot, FactoryReset, ScheduleInform, RequestDownload -> true;
      default -> false;
    };
  }
}
