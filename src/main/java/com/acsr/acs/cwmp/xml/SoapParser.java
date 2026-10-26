package com.acsr.acs.cwmp.xml;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * CWMP SOAP 解析器：把 CPE 发来的 HTTP body 解析成结构化对象。
 *
 * <p>使用标准 SAX，禁用 DTD 防止 XXE；不依赖 Content-Length，空包直接视为 Empty。
 */
@Getter
@Slf4j
public class SoapParser extends DefaultHandler {

  private static final String SOAP_ENV = "http://schemas.xmlsoap.org/soap/envelope/";
  private static final String CWMP_NS_PREFIX = "urn:dslforum-org:cwmp-";
  private static final String FEATURE_DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl";

  private CwmpMethod method = CwmpMethod.Empty;
  /** 该报文是 CPE 对 ACS 请求的响应（如 GetParameterValuesResponse） */
  private boolean incomingResponse;
  private String cwmpVersion = "1-0";

  private String headerId;
  private boolean holdRequests;
  private boolean noMoreRequests;

  private final DeviceIdStruct deviceId = new DeviceIdStruct();
  private final List<EventStruct> events = new ArrayList<>();
  private final ParameterList parameterList = new ParameterList();
  private final List<String> methodList = new ArrayList<>();
  private Fault fault;

  private String maxEnvelopes;
  private String currentTime;
  private String retryCount;
  private String status;
  private String startTime;
  private String completeTime;
  private String commandKey;
  /** AddObjectResponse 返回的实例号 */
  private String instanceNumber;

  private String rawXml;

  // 解析过程状态
  private final StringBuilder text = new StringBuilder();
  private boolean inHeader;
  private boolean inBody;
  private boolean inDeviceId;
  private boolean inEvent;
  private boolean inParamList;
  private boolean inPvs;
  private boolean inPis;
  private boolean inPas;
  private boolean inMethodList;
  private boolean inFault;
  private String headerLeaf;
  private EventStruct currentEvent;
  private ParameterValueStruct pvs;
  private ParameterInfoStruct pis;
  private ParameterAttributeStruct pas;

  private SoapParser() {}

  /** 解析字节数组；空包返回 method=Empty 的解析器。 */
  public static SoapParser parse(byte[] xml) throws Exception {
    SoapParser parser = new SoapParser();
    if (xml == null || xml.length == 0) {
      return parser;
    }
    parser.rawXml = new String(xml, StandardCharsets.UTF_8);
    try {
      SAXParserFactory factory = SAXParserFactory.newInstance();
      // 部分 CPE 会漏声明 soapenc/xsi 前缀，关闭命名空间感知以提高兼容性
      factory.setNamespaceAware(false);
      factory.setFeature(FEATURE_DISALLOW_DOCTYPE, true);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      SAXParser saxParser = factory.newSAXParser();
      saxParser.parse(new ByteArrayInputStream(xml), parser);
    } catch (Exception e) {
      throw new SAXException("解析 CWMP SOAP 报文失败: " + e.getMessage(), e);
    }
    // 关闭命名空间感知后 startPrefixMapping 不会触发，直接从原文提取 CWMP 版本
    java.util.regex.Matcher m = java.util.regex.Pattern
        .compile("urn:dslforum-org:cwmp-([A-Za-z0-9\\-]+)")
        .matcher(parser.rawXml);
    if (m.find()) {
      parser.cwmpVersion = m.group(1);
    }
    return parser;
  }

  @Override
  public void startPrefixMapping(String prefix, String uri) {
    if ("cwmp".equals(prefix) && uri != null && uri.startsWith(CWMP_NS_PREFIX)) {
      cwmpVersion = uri.substring(CWMP_NS_PREFIX.length());
    }
  }

  @Override
  public void startElement(String uri, String localName, String qName, Attributes atts) {
    text.setLength(0);
    String ln = name(localName, qName);

    if ("Header".equals(ln)) {
      inHeader = true;
      return;
    }
    if ("Body".equals(ln)) {
      inBody = true;
      return;
    }
    if (inHeader) {
      if ("ID".equals(ln) || "HoldRequests".equals(ln) || "NoMoreRequests".equals(ln)) {
        headerLeaf = ln;
      }
      return;
    }
    if (!inBody) {
      return;
    }
    if ("Fault".equals(ln) || "SetParameterValuesFault".equals(ln)) {
      ensureFault();
      inFault = true;
      if ("Fault".equals(ln) && method == CwmpMethod.Empty) {
        method = CwmpMethod.Fault;
      }
      return;
    }
    if (method == CwmpMethod.Empty) {
      CwmpMethod m = CwmpMethod.fromString(ln);
      if (m != CwmpMethod.Empty) {
        method = m;
        incomingResponse = ln.endsWith("Response");
        return;
      }
    }
    switch (ln) {
      case "DeviceId" -> inDeviceId = true;
      case "EventStruct" -> {
        inEvent = true;
        currentEvent = new EventStruct();
      }
      case "ParameterList" -> inParamList = true;
      case "ParameterValueStruct" -> {
        inPvs = true;
        pvs = new ParameterValueStruct();
      }
      case "ParameterInfoStruct" -> {
        inPis = true;
        pis = new ParameterInfoStruct();
      }
      case "ParameterAttributeStruct" -> {
        inPas = true;
        pas = new ParameterAttributeStruct();
      }
      case "MethodList" -> inMethodList = true;
      case "Value" -> {
        if (pvs != null && atts != null) {
          for (int i = 0; i < atts.getLength(); i++) {
            String aq = atts.getQName(i);
            if (aq != null && aq.endsWith(":type")) {
              pvs.setType(atts.getValue(i));
              break;
            }
          }
        }
      }
      default -> {
        // 其它元素无需特殊处理
      }
    }
  }

  /** 兼容命名空间感知关闭的情况：优先 localName，其次去掉前缀的 qName。 */
  private static String name(String localName, String qName) {
    if (localName != null && !localName.isEmpty()) {
      return localName;
    }
    if (qName == null) {
      return "";
    }
    int idx = qName.indexOf(':');
    return idx >= 0 ? qName.substring(idx + 1) : qName;
  }

  @Override
  public void characters(char[] ch, int start, int length) {
    text.append(ch, start, length);
  }

  @Override
  public void endElement(String uri, String localName, String qName) {
    String v = text.toString().trim();
    String ln = name(localName, qName);

    if ("Header".equals(ln)) {
      inHeader = false;
      return;
    }
    if ("Body".equals(ln)) {
      inBody = false;
      return;
    }
    if (inHeader) {
      switch (ln) {
        case "ID" -> headerId = v;
        case "HoldRequests" -> holdRequests = "1".equals(v) || "true".equals(v);
        case "NoMoreRequests" -> noMoreRequests = "1".equals(v) || "true".equals(v);
        default -> {
        }
      }
      return;
    }

    switch (ln) {
      case "Manufacturer" -> {
        if (inDeviceId) {
          deviceId.setManufacturer(v);
        }
      }
      case "OUI" -> {
        if (inDeviceId) {
          deviceId.setOui(v);
        }
      }
      case "ProductClass" -> {
        if (inDeviceId) {
          deviceId.setProductClass(v);
        }
      }
      case "SerialNumber" -> {
        if (inDeviceId) {
          deviceId.setSerialNumber(v);
        }
      }
      case "DeviceId" -> inDeviceId = false;
      case "EventCode" -> {
        if (currentEvent != null) {
          currentEvent.setEventCode(v);
        }
      }
      case "CommandKey" -> {
        if (inEvent && currentEvent != null) {
          currentEvent.setCommandKey(v);
        } else if (!v.isEmpty()) {
          commandKey = v;
        }
      }
      case "EventStruct" -> {
        if (currentEvent != null) {
          events.add(currentEvent);
        }
        inEvent = false;
        currentEvent = null;
      }
      case "Name" -> {
        if (inPvs && pvs != null) {
          pvs.setName(v);
        } else if (inPis && pis != null) {
          pis.setName(v);
        } else if (inPas && pas != null) {
          pas.setName(v);
        }
      }
      case "Value" -> {
        if (inPvs && pvs != null) {
          pvs.setValue(v);
        }
      }
      case "Writable" -> {
        if (pis != null) {
          pis.setWritable(!"0".equals(v) && !"false".equals(v));
        }
      }
      case "Notification" -> {
        if (pas != null) {
          try {
            pas.setNotification(Integer.parseInt(v));
          } catch (NumberFormatException ignore) {
            pas.setNotification(0);
          }
        }
      }
      case "AccessList" -> {
        if (pas != null && !v.isEmpty()) {
          pas.getAccessList().add(v);
        }
      }
      case "ParameterValueStruct" -> {
        if (pvs != null) {
          parameterList.addParameterValueStruct(pvs);
        }
        inPvs = false;
        pvs = null;
      }
      case "ParameterInfoStruct" -> {
        if (pis != null) {
          parameterList.addParameterInfoStruct(pis);
        }
        inPis = false;
        pis = null;
      }
      case "ParameterAttributeStruct" -> {
        if (pas != null) {
          parameterList.addParameterAttributeStruct(pas);
        }
        inPas = false;
        pas = null;
      }
      case "ParameterList" -> inParamList = false;
      case "MethodList" -> inMethodList = false;
      case "string" -> {
        if (inMethodList && !v.isEmpty()) {
          methodList.add(v);
        }
      }
      case "InstanceNumber" -> instanceNumber = v;
      case "MaxEnvelopes" -> maxEnvelopes = v;
      case "CurrentTime" -> currentTime = v;
      case "RetryCount" -> retryCount = v;
      case "Status" -> status = v;
      case "StartTime" -> startTime = v;
      case "CompleteTime" -> completeTime = v;
      case "faultcode", "FaultCode" -> {
        ensureFault();
        fault.setFaultCode(v);
      }
      case "faultstring", "FaultString" -> {
        ensureFault();
        fault.setFaultString(v);
      }
      case "ParameterName" -> {
        if (fault != null) {
          fault.setParameterName(v);
        }
      }
      default -> {
      }
    }
    text.setLength(0);
  }

  private void ensureFault() {
    if (fault == null) {
      fault = new Fault();
    }
  }

  public DeviceIdStruct getDeviceIdStruct() {
    return deviceId;
  }

  public List<EventStruct> getEventList() {
    return events;
  }
}
