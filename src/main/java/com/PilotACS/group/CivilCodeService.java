/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.610
 *
 */

package com.PilotACS.group;

import com.PilotACS.common.log.Logs;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 行政区划代码表（civilCode.csv）处理。
 *
 * <p>资源文件格式三列：{@code 编号,名称,上级}，首行表头，省级行的上级是空格。
 * 启动时整表读进内存建树并缓存（全国三千多区县，几十 KB），之后查询零 IO。</p>
 *
 * <p>给分组管理用：分组树可以按行政区划搭骨架（省 → 市 → 区县），
 * 设备再挂到区划节点上；也供设备地址、工单片区等场景取区划链路。</p>
 */
@Service
public class CivilCodeService {

    private static final String CSV_PATH = "civilCode.csv";

    /** 编号 → 节点，全表索引 */
    private volatile Map<String, CivilCodeNode> index = Collections.emptyMap();

    /** 根节点（省级行政区）列表 */
    private volatile List<CivilCodeNode> roots = Collections.emptyList();

    /** 启动加载一次；文件缺失只告警不拦启动，分组页按「表未就绪」提示 */
    @PostConstruct
    public void load() {
        Map<String, CivilCodeNode> newIndex = new LinkedHashMap<>();
        List<CivilCodeNode> newRoots = new ArrayList<>();
        int total = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(CSV_PATH).getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                // 跳过表头行：编号,名称,上级
                if (first) {
                    first = false;
                    if (line.contains("编号")) {
                        continue;
                    }
                }
                String[] parts = line.split(",", -1);
                if (parts.length < 2) {
                    continue;
                }
                String code = parts[0].trim();
                String name = parts[1].trim();
                String parent = parts.length > 2 ? parts[2].trim() : "";
                if (code.isEmpty() || name.isEmpty()) {
                    continue;
                }
                CivilCodeNode node = new CivilCodeNode(code, name, parent);
                newIndex.put(code, node);
                if (node.getParentCode() == null) {
                    newRoots.add(node);
                }
                total++;
            }
        } catch (IOException e) {
            Logs.warn(Logs.Module.SYSTEM, "行政区划代码表加载失败，分组树不可用", "原因", e.getMessage());
            return;
        }
        // 第二遍把子节点挂到父节点，孤儿行（上级不存在）当根处理避免丢条目
        for (CivilCodeNode node : newIndex.values()) {
            CivilCodeNode parent = node.getParentCode() == null ? null : newIndex.get(node.getParentCode());
            if (parent == null) {
                if (node.getParentCode() != null && !newRoots.contains(node)) {
                    newRoots.add(node);
                }
            } else {
                parent.addChild(node);
            }
        }
        this.index = newIndex;
        this.roots = newRoots;
        Logs.info(Logs.Module.SYSTEM, "行政区划代码表加载完成",
                "条目", total, "省级", newRoots.size());
    }

    /** 整棵区划树（省 → 市 → 区县），节点 children 已挂好 */
    public List<CivilCodeNode> tree() {
        return roots;
    }

    /** 按编号取节点，不存在返回 null */
    public CivilCodeNode node(String code) {
        return code == null ? null : index.get(code.trim());
    }

    /** 某节点的直属下级；传 null 或空返回省级列表 */
    public List<CivilCodeNode> children(String parentCode) {
        if (parentCode == null || parentCode.isBlank()) {
            return roots;
        }
        CivilCodeNode parent = node(parentCode);
        return parent == null ? Collections.emptyList() : parent.getChildren();
    }

    /** 编号是否存在 */
    public boolean exists(String code) {
        return node(code) != null;
    }

    /**
     * 从省到本级的区划链：110108 → [北京市(11), 市辖区(1101), 海淀区(110108)]。
     * 编号不存在返回空列表。
     */
    public List<CivilCodeNode> path(String code) {
        List<CivilCodeNode> chain = new ArrayList<>();
        CivilCodeNode current = node(code);
        while (current != null) {
            chain.add(0, current);
            current = current.getParentCode() == null ? null : index.get(current.getParentCode());
        }
        return chain;
    }

    /** 「省 / 市 / 区县」拼接文案；编号不存在返回空串 */
    public String pathText(String code) {
        List<CivilCodeNode> chain = path(code);
        if (chain.isEmpty()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (CivilCodeNode node : chain) {
            names.add(node.getName());
        }
        return String.join(" / ", names);
    }
}
