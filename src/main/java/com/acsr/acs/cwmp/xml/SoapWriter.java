package com.acsr.acs.cwmp.xml;

import java.util.List;

/** CWMP SOAP 响应构造（ACS -> CPE）。所有值统一做 XML 转义。 */
public final class SoapWriter {

  private SoapWriter() {}

  public static String esc(String s) {
    if (s == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder(s.length() + 16);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '&' -> sb.append("&amp;");
        case '<' -> sb.append("&lt;");
        case '>' -> sb.append("&gt;");
        case '"' -> sb.append("&quot;");
        case '\'' -> sb.append("&apos;");
        default -> sb.append(c);
      }
    }
    return sb.toString();
  }

  /** 组装完整的 SOAP 信封。 */
  public static String envelope(String headerId, String bodyXml, String cwmpVersion) {
    String ver = (cwmpVersion == null || cwmpVersion.isBlank()) ? "1-0" : cwmpVersion;
    StringBuilder sb = new StringBuilder(512);
    sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    sb.append("<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
        .append("xmlns:soapenc=\"http://schemas.xmlsoap.org/soap/encoding/\" ")
        .append("xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\" ")
        .append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
        .append("xmlns:cwmp=\"urn:dslforum-org:cwmp-").append(ver).append("\">\n");
    if (headerId != null && !headerId.isBlank()) {
      sb.append("<soapenv:Header>\n<cwmp:ID soapenv:mustUnderstand=\"1\">")
          .append(esc(headerId))
          .append("</cwmp:ID>\n</soapenv:Header>\n");
    } else {
      sb.append("<soapenv:Header/>\n");
    }
    sb.append("<soapenv:Body>\n").append(bodyXml).append("\n</soapenv:Body>\n");
    sb.append("</soapenv:Envelope>");
    return sb.toString();
  }

  public static String informResponse() {
    return "<cwmp:InformResponse>\n\t<MaxEnvelopes>1</MaxEnvelopes>\n</cwmp:InformResponse>";
  }

  public static String getRpcMethodsResponse(List<String> methods) {
    StringBuilder sb = new StringBuilder();
    sb.append("<cwmp:GetRPCMethodsResponse>\n\t<MethodList soapenc:arrayType=\"xsd:string[")
        .append(methods.size())
        .append("]\">\n");
    for (String m : methods) {
      sb.append("\t\t<string>").append(esc(m)).append("</string>\n");
    }
    sb.append("\t</MethodList>\n</cwmp:GetRPCMethodsResponse>");
    return sb.toString();
  }

  public static String getParameterNames(String parameterPath, boolean nextLevel) {
    return "<cwmp:GetParameterNames>\n\t<ParameterPath>"
        + esc(parameterPath)
        + "</ParameterPath>\n\t<NextLevel>"
        + nextLevel
        + "</NextLevel>\n</cwmp:GetParameterNames>";
  }

  public static String getParameterValues(List<String> names) {
    StringBuilder sb = new StringBuilder();
    sb.append("<cwmp:GetParameterValues>\n\t<ParameterNames soapenc:arrayType=\"xsd:string[")
        .append(names.size())
        .append("]\">\n");
    for (String n : names) {
      sb.append("\t\t<string>").append(esc(n)).append("</string>\n");
    }
    sb.append("\t</ParameterNames>\n</cwmp:GetParameterValues>");
    return sb.toString();
  }

  public static String setParameterValues(List<ParameterValueStruct> params, String parameterKey) {
    StringBuilder sb = new StringBuilder();
    sb.append("<cwmp:SetParameterValues>\n\t<ParameterList soapenc:arrayType=\"cwmp:ParameterValueStruct[")
        .append(params.size())
        .append("]\">\n");
    for (ParameterValueStruct p : params) {
      String type = p.getType() == null || p.getType().isBlank() ? "xsd:string" : p.getType();
      String value = p.getValue() == null ? "" : p.getValue();
      if (type.contains("int") && value.trim().isEmpty()) {
        value = "0";
      }
      sb.append("\t\t<ParameterValueStruct>\n")
          .append("\t\t\t<Name>").append(esc(p.getName())).append("</Name>\n")
          .append("\t\t\t<Value xsi:type=\"").append(esc(type)).append("\">")
          .append(esc(value))
          .append("</Value>\n")
          .append("\t\t</ParameterValueStruct>\n");
    }
    sb.append("\t</ParameterList>\n");
    if (parameterKey != null && !parameterKey.isBlank()) {
      sb.append("\t<ParameterKey>").append(esc(parameterKey)).append("</ParameterKey>\n");
    }
    sb.append("</cwmp:SetParameterValues>");
    return sb.toString();
  }

  public static String addObject(String objectName, String parameterKey) {
    return "<cwmp:AddObject>\n\t<ObjectName>"
        + esc(objectName)
        + "</ObjectName>\n\t<ParameterKey>"
        + esc(parameterKey == null ? "" : parameterKey)
        + "</ParameterKey>\n</cwmp:AddObject>";
  }

  public static String deleteObject(String objectName, String parameterKey) {
    return "<cwmp:DeleteObject>\n\t<ObjectName>"
        + esc(objectName)
        + "</ObjectName>\n\t<ParameterKey>"
        + esc(parameterKey == null ? "" : parameterKey)
        + "</ParameterKey>\n</cwmp:DeleteObject>";
  }

  public static String reboot(String commandKey) {
    return "<cwmp:Reboot>\n\t<CommandKey>"
        + esc(commandKey == null ? "" : commandKey)
        + "</CommandKey>\n</cwmp:Reboot>";
  }

  public static String factoryReset() {
    return "<cwmp:FactoryReset/>";
  }

  public static String download(String commandKey, String fileType, String url, String username,
                                String password, long fileSize, String targetFileName, int delaySeconds) {
    return "<cwmp:Download>\n\t<CommandKey>"
        + esc(commandKey == null ? "" : commandKey)
        + "</CommandKey>\n\t<FileType>"
        + esc(fileType)
        + "</FileType>\n\t<URL>"
        + esc(url)
        + "</URL>\n\t<Username>"
        + esc(username == null ? "" : username)
        + "</Username>\n\t<Password>"
        + esc(password == null ? "" : password)
        + "</Password>\n\t<FileSize>"
        + fileSize
        + "</FileSize>\n\t<TargetFileName>"
        + esc(targetFileName == null ? "" : targetFileName)
        + "</TargetFileName>\n\t<DelaySeconds>"
        + delaySeconds
        + "</DelaySeconds>\n\t<SuccessURL></SuccessURL>\n\t<FailureURL></FailureURL>\n</cwmp:Download>";
  }

  public static String upload(String commandKey, String fileType, String url, String username,
                              String password, int delaySeconds) {
    return "<cwmp:Upload>\n\t<CommandKey>"
        + esc(commandKey == null ? "" : commandKey)
        + "</CommandKey>\n\t<FileType>"
        + esc(fileType)
        + "</FileType>\n\t<URL>"
        + esc(url)
        + "</URL>\n\t<Username>"
        + esc(username == null ? "" : username)
        + "</Username>\n\t<Password>"
        + esc(password == null ? "" : password)
        + "</Password>\n\t<DelaySeconds>"
        + delaySeconds
        + "</DelaySeconds>\n</cwmp:Upload>";
  }

  public static String getParameterAttributes(List<String> names) {
    StringBuilder sb = new StringBuilder();
    sb.append("<cwmp:GetParameterAttributes>\n\t<ParameterNames soapenc:arrayType=\"xsd:string[")
        .append(names.size())
        .append("]\">\n");
    for (String n : names) {
      sb.append("\t\t<string>").append(esc(n)).append("</string>\n");
    }
    sb.append("\t</ParameterNames>\n</cwmp:GetParameterAttributes>");
    return sb.toString();
  }

  public static String setParameterAttributes(List<ParameterAttributeStruct> attrs) {
    StringBuilder sb = new StringBuilder();
    sb.append("<cwmp:SetParameterAttributes>\n");
    for (ParameterAttributeStruct a : attrs) {
      sb.append("\t<ParameterAttributeStruct>\n")
          .append("\t\t<Name>").append(esc(a.getName())).append("</Name>\n")
          .append("\t\t<Notification>").append(a.getNotification()).append("</Notification>\n")
          .append("\t\t<AccessList>\n");
      for (String s : a.getAccessList()) {
        sb.append("\t\t\t<string>").append(esc(s)).append("</string>\n");
      }
      sb.append("\t\t</AccessList>\n")
          .append("\t</ParameterAttributeStruct>\n");
    }
    sb.append("</cwmp:SetParameterAttributes>");
    return sb.toString();
  }

  public static String transferCompleteResponse() {
    return "<cwmp:TransferCompleteResponse/>";
  }

  public static String autonomousTransferCompleteResponse() {
    return "<cwmp:AutonomousTransferCompleteResponse/>";
  }

  public static String scheduleInform(int delaySeconds, String commandKey) {
    return "<cwmp:ScheduleInform>\n\t<DelaySeconds>"
        + delaySeconds
        + "</DelaySeconds>\n\t<CommandKey>"
        + esc(commandKey == null ? "" : commandKey)
        + "</CommandKey>\n</cwmp:ScheduleInform>";
  }

  /** 通用的空方法响应（RebootResponse / DownloadResponse 等）。 */
  public static String emptyMethodResponse(String methodName) {
    return "<cwmp:" + methodName + "Response/>";
  }
}
