/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.sip.h248;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** H.248（Megaco）文本编码：花括号树解析与组装 */
public class H248Message {

    /** 树节点：name 可为 "Add"、"Context" 等；text 为叶子值；children 为子节点 */
    public static class Node {
        public String name;
        public String text;
        public List<Node> children = new ArrayList<>();

        public Node(String name) {
            this.name = name;
        }

        /** 按名字（忽略大小写）取第一个子节点 */
        public Node child(String childName) {
            for (Node c : children) {
                if (c.name.equalsIgnoreCase(childName)) {
                    return c;
                }
            }
            return null;
        }

        /** 取子节点叶子文本，缺省返回 null */
        public String childText(String childName) {
            Node c = child(childName);
            return c == null ? null : c.text;
        }

        /** 递归收集某名字的全部子孙节点 */
        public List<Node> findAll(String childName) {
            List<Node> out = new ArrayList<>();
            collect(this, childName, out);
            return out;
        }

        private void collect(Node node, String childName, List<Node> out) {
            for (Node c : node.children) {
                if (c.name.equalsIgnoreCase(childName)) {
                    out.add(c);
                }
                collect(c, childName, out);
            }
        }
    }

    /** 顶层结构：msgType（Message/Reply/Request/Pending/...）、相关事务号、消息体树 */
    public static class Parsed {
        public String senderAddress = "";
        public String msgType;
        /** Message 的 Transaction/Reply 号（无则 -1） */
        public long transactionId = -1;
        public Node root;
    }

    private static final Pattern ADDR = Pattern.compile("\\[([0-9a-fA-F.:]+)\\]");
    private static final Pattern TXN = Pattern.compile("(Transaction|Reply|Pending)\\s*=\\s*(\\d+)\\s*\\{");

    /** 解析 H.248 文本报文，非法返回 null */
    public static Parsed parse(String raw) {
        try {
            Parsed p = new Parsed();
            Matcher m = ADDR.matcher(raw);
            if (m.find()) {
                p.senderAddress = m.group(1);
            }
            m = TXN.matcher(raw);
            if (m.find()) {
                p.msgType = m.group(1);
                p.transactionId = Long.parseLong(m.group(2));
            }
            int brace = raw.indexOf('{');
            if (brace < 0) {
                return null;
            }
            List<Node> roots = parseBlock(raw.substring(brace));
            if (roots.size() == 1) {
                p.root = roots.get(0);
            } else if (roots.size() > 1) {
                p.root = new Node("root");
                p.root.children = roots;
            } else {
                return null;
            }
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析 "{ ... }" 块为兄弟节点列表 */
    private static List<Node> parseBlock(String block) {
        List<Node> out = new ArrayList<>();
        int i = 0;
        int len = block.length();
        while (i < len) {
            // 跳到下一个条目名或块结束
            while (i < len && (Character.isWhitespace(block.charAt(i)) || block.charAt(i) == '{' || block.charAt(i) == '}')) {
                i++;
            }
            if (i >= len) {
                break;
            }
            // 读条目名
            int nameStart = i;
            while (i < len && block.charAt(i) != '=' && block.charAt(i) != '{'
                    && block.charAt(i) != '}' && !Character.isWhitespace(block.charAt(i))) {
                i++;
            }
            String name = block.substring(nameStart, i).trim();
            if (name.isEmpty()) {
                i++;
                continue;
            }
            Node node = new Node(name);
            // 跳空白
            while (i < len && Character.isWhitespace(block.charAt(i))) {
                i++;
            }
            if (i < len && block.charAt(i) == '{') {
                // 子块：找配对花括号
                int depth = 0;
                int start = ++i;
                while (i < len) {
                    char c = block.charAt(i);
                    if (c == '{') {
                        depth++;
                    } else if (c == '}') {
                        if (depth == 0) {
                            break;
                        }
                        depth--;
                    }
                    i++;
                }
                node.children = parseBlock(block.substring(start, Math.min(i, len)));
                i++;
            } else if (i < len && block.charAt(i) == '=') {
                i++;
                while (i < len && Character.isWhitespace(block.charAt(i))) {
                    i++;
                }
                StringBuilder val = new StringBuilder();
                while (i < len && block.charAt(i) != '{' && block.charAt(i) != '}') {
                    char c = block.charAt(i);
                    if (Character.isWhitespace(c) && i + 1 < len && block.charAt(i + 1) == '}') {
                        break;
                    }
                    if (Character.isWhitespace(c) && val.length() > 0
                            && (Character.isWhitespace(block.charAt(i + 1)) || i + 1 >= len)) {
                        break;
                    }
                    val.append(c);
                    i++;
                }
                node.text = val.toString().trim();
            } else {
                node.text = "";
            }
            out.add(node);
        }
        return out;
    }

    // ------------------------------------------------ 组装

    /** MGC → MG 消息头部 */
    public static StringBuilder header(String mgcIp) {
        return new StringBuilder("MEGACO/1 [").append(mgcIp).append("]\n");
    }

    /** 缩进深度 */
    public static StringBuilder indent(StringBuilder sb, int depth) {
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        return sb;
    }

    /** SDP 文本转单行（花括号内写不下换行，用空格分隔） */
    public static String sdpToLine(String sdp) {
        return sdp.replace("\r\n", "\n").replace("\n", " ").trim();
    }

    /** 单行 SDP 还原为多行（Local/Remote 描述符里） */
    public static String lineToSdp(String line) {
        if (line == null) {
            return "";
        }
        return line.replace("v=0 ", "v=0\n").replace("o=", "\no=").replace("c=", "\nc=")
                .replace("m=", "\nm=").replace("a=", "\na=").replace("s=", "\ns=").replace("t=", "\nt=").trim();
    }
}
