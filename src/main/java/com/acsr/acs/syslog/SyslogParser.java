package com.acsr.acs.syslog;

import lombok.Data;

/** RFC3164 风格 syslog 文本解析：<PRI>时间戳 主机 标识: 内容。 */
public final class SyslogParser {

  private SyslogParser() {}

  @Data
  public static class Entry {
    private int facility;
    private int severity;
    private String timestamp = "";
    private String hostname = "";
    private String tag = "";
    private String content = "";
  }

  public static Entry parse(String line) {
    Entry e = new Entry();
    if (line == null || line.isBlank()) {
      return e;
    }
    String s = line.trim();
    // <PRI>
    if (s.startsWith("<")) {
      int end = s.indexOf('>');
      if (end > 1) {
        try {
          int pri = Integer.parseInt(s.substring(1, end).trim());
          e.setFacility(pri / 8);
          e.setSeverity(pri % 8);
        } catch (NumberFormatException ignore) {
          // 忽略非法 PRI
        }
        s = s.substring(end + 1).trim();
      }
    }
    // 时间戳：如 "Oct 26 10:00:01" 或 ISO
    int sp1 = s.indexOf(' ');
    if (sp1 > 0) {
      String first = s.substring(0, sp1);
      if (isTimestampStart(first)) {
        // 时间戳一般包含两个空格分隔段（日期 + 时间）
        int sp2 = s.indexOf(' ', sp1 + 1);
        int sp3 = sp2 > 0 ? s.indexOf(' ', sp2 + 1) : -1;
        if (sp3 > 0) {
          e.setTimestamp(s.substring(0, sp3).trim());
          s = s.substring(sp3 + 1).trim();
        } else {
          e.setTimestamp(s.substring(0, sp1).trim());
          s = s.substring(sp1 + 1).trim();
        }
      }
    }
    // 主机名
    int sp = s.indexOf(' ');
    if (sp > 0) {
      e.setHostname(s.substring(0, sp).trim());
      s = s.substring(sp + 1).trim();
    }
    // TAG: 内容
    int colon = s.indexOf(':');
    if (colon > 0 && colon < 64) {
      e.setTag(s.substring(0, colon).trim());
      e.setContent(s.substring(colon + 1).trim());
    } else {
      e.setContent(s);
    }
    return e;
  }

  private static boolean isTimestampStart(String token) {
    if (token.length() < 3) {
      return false;
    }
    char c = token.charAt(0);
    return (c >= '0' && c <= '9') || token.length() == 3;
  }
}
