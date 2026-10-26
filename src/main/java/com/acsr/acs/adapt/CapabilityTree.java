package com.acsr.acs.adapt;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 光猫能力树：型号 + 固件支持的参数清单及其可写标记。
 *
 * <p>XML 形态：
 * <pre>{@code
 * <capability model="HS8145V6" firmware="V6R0C10">
 *   <param name="InternetGatewayDevice.DeviceInfo.SoftwareVersion" writable="false" type="xsd:string"/>
 * </capability>
 * }</pre>
 */
@Data
@Slf4j
public class CapabilityTree {

  private String model = "";
  private String firmware = "";
  private final Map<String, ParamMeta> params = new LinkedHashMap<>();

  @Data
  public static class ParamMeta {
    private String name;
    private boolean writable;
    private String type = "xsd:string";

    public ParamMeta() {}

    public ParamMeta(String name, boolean writable, String type) {
      this.name = name;
      this.writable = writable;
      this.type = type == null || type.isBlank() ? "xsd:string" : type;
    }

    /** 转成 FreeACS 风格标记。 */
    public String flags() {
      return writable ? "RW" : "R";
    }
  }

  public Map<String, ParamMeta> getParams() {
    return params;
  }

  public void put(String name, boolean writable, String type) {
    params.put(name, new ParamMeta(name, writable, type));
  }

  public int size() {
    return params.size();
  }

  /** 是否可写（未登记的参数默认按只读处理，避免误写）。 */
  public boolean isWritable(String name) {
    ParamMeta meta = params.get(name);
    return meta != null && meta.isWritable();
  }

  public String toXml() {
    try {
      StringWriter out = new StringWriter();
      XMLStreamWriter w = javax.xml.stream.XMLOutputFactory.newFactory().createXMLStreamWriter(out);
      w.writeStartDocument("UTF-8", "1.0");
      w.writeStartElement("capability");
      w.writeAttribute("model", model == null ? "" : model);
      w.writeAttribute("firmware", firmware == null ? "" : firmware);
      for (ParamMeta meta : params.values()) {
        w.writeStartElement("param");
        w.writeAttribute("name", meta.getName());
        w.writeAttribute("writable", String.valueOf(meta.isWritable()));
        w.writeAttribute("type", meta.getType());
        w.writeEndElement();
      }
      w.writeEndElement();
      w.writeEndDocument();
      w.flush();
      return out.toString();
    } catch (Exception e) {
      log.warn("序列化能力树失败", e);
      return "<capability></capability>";
    }
  }

  public static CapabilityTree parse(String xml) {
    CapabilityTree tree = new CapabilityTree();
    if (xml == null || xml.isBlank()) {
      return tree;
    }
    try {
      XMLInputFactory factory = XMLInputFactory.newFactory();
      factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
      factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
      XMLStreamReader r = factory.createXMLStreamReader(new StringReader(xml));
      while (r.hasNext()) {
        int event = r.next();
        if (event != XMLStreamConstants.START_ELEMENT) {
          continue;
        }
        String el = r.getLocalName();
        if ("capability".equals(el)) {
          for (int i = 0; i < r.getAttributeCount(); i++) {
            String an = r.getAttributeLocalName(i);
            if ("model".equals(an)) {
              tree.setModel(r.getAttributeValue(i));
            } else if ("firmware".equals(an)) {
              tree.setFirmware(r.getAttributeValue(i));
            }
          }
        } else if ("param".equals(el)) {
          String name = null;
          boolean writable = false;
          String type = "xsd:string";
          for (int i = 0; i < r.getAttributeCount(); i++) {
            switch (r.getAttributeLocalName(i)) {
              case "name" -> name = r.getAttributeValue(i);
              case "writable" -> writable = Boolean.parseBoolean(r.getAttributeValue(i));
              case "type" -> type = r.getAttributeValue(i);
              default -> {
              }
            }
          }
          if (name != null) {
            tree.put(name, writable, type);
          }
        }
      }
    } catch (Exception e) {
      log.warn("解析能力树失败", e);
    }
    return tree;
  }
}
