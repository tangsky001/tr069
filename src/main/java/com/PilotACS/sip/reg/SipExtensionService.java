/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.sip.reg;

import com.PilotACS.common.log.Logs;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SIP 分机管理：增删改、文件批量导入 */
@Service
public class SipExtensionService {

    private final SipExtensionMapper mapper;
    private final ExtensionRegistry registry;

    public SipExtensionService(SipExtensionMapper mapper, ExtensionRegistry registry) {
        this.mapper = mapper;
        this.registry = registry;
    }

    /** 全部分机（按分机号排序） */
    public List<SipExtension> list() {
        return mapper.selectList(new QueryWrapper<SipExtension>().orderByAsc("extension"));
    }

    /** 单个添加分机，密码留空默认同分机号 */
    public SipExtension add(String extension, String password, String remark) {
        String ext = normalize(extension);
        if (ext == null) {
            throw new IllegalArgumentException("分机号不能为空");
        }
        if (mapper.selectCount(new QueryWrapper<SipExtension>().eq("extension", ext)) > 0) {
            throw new IllegalArgumentException("分机号已存在: " + ext);
        }
        SipExtension e = new SipExtension();
        e.setExtension(ext);
        e.setPassword(password == null || password.isBlank() ? ext : password.trim());
        e.setRemark(remark == null ? "" : remark.trim());
        mapper.insert(e);
        registry.reload();
        Logs.info(Logs.Module.SIP, "分机添加", "分机", ext);
        return e;
    }

    /** 修改密码/备注 */
    public void update(Long id, String password, String remark) {
        SipExtension e = mapper.selectById(id);
        if (e == null) {
            throw new IllegalArgumentException("分机不存在");
        }
        if (password != null && !password.isBlank()) {
            e.setPassword(password.trim());
        }
        if (remark != null) {
            e.setRemark(remark.trim());
        }
        mapper.updateById(e);
        registry.reload();
        Logs.info(Logs.Module.SIP, "分机修改", "分机", e.getExtension());
    }

    /** 删除分机（在线注册自动失效） */
    public void delete(Long id) {
        SipExtension e = mapper.selectById(id);
        if (e == null) {
            return;
        }
        mapper.deleteById(id);
        registry.unregister(e.getExtension());
        registry.reload();
        Logs.info(Logs.Module.SIP, "分机删除", "分机", e.getExtension());
    }

    /**
     * 批量生成号段：前缀 + 起止号补齐为 4 位（如前缀 82、起止 1~99 → 8201~8299）。
     * exclude4 为真时跳过第 3/4 位含 4 的号码（如 8404/8424/8204/8214）；
     * 已存在的分机跳过不覆盖，密码留空默认同分机号。
     */
    public Map<String, Object> generate(String prefix, int start, int end, boolean exclude4, String password) {
        String p = prefix == null ? "" : prefix.trim();
        if (!p.matches("\\d{2,3}")) {
            throw new IllegalArgumentException("前缀须为 2~3 位数字，如 82");
        }
        int width = 4 - p.length();
        int max = (int) Math.pow(10, width) - 1;
        if (start < 1 || end < start || end > max) {
            throw new IllegalArgumentException("起止范围须在 1~" + max);
        }
        int added = 0;
        int skipped = 0;
        for (int n = start; n <= end; n++) {
            String ext = p + String.format("%0" + width + "d", n);
            if (exclude4 && hasForbidden4(ext)) {
                skipped++;
                continue;
            }
            if (mapper.selectCount(new QueryWrapper<SipExtension>().eq("extension", ext)) > 0) {
                skipped++;
                continue;
            }
            SipExtension e = new SipExtension();
            e.setExtension(ext);
            e.setPassword(password == null || password.isBlank() ? ext : password.trim());
            mapper.insert(e);
            added++;
        }
        registry.reload();
        Logs.info(Logs.Module.SIP, "分机批量生成", "前缀", p, "范围", start + "-" + end,
                "新增", added, "跳过", skipped);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("added", added);
        out.put("skipped", skipped);
        return out;
    }

    /** 第 3/4 位是否含数字 4 */
    private boolean hasForbidden4(String ext) {
        if (ext.length() < 4) {
            return ext.contains("4");
        }
        return ext.charAt(2) == '4' || ext.charAt(3) == '4';
    }

    /** 导入文件（txt/csv，每行：分机号 分隔符 密码 [备注]） */
    public Map<String, Object> importFile(MultipartFile file) throws Exception {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return importLines(lines);
    }

    /** 批量导入：已存在的覆盖密码，返回统计 */
    public Map<String, Object> importLines(List<String> lines) {
        int added = 0;
        int updated = 0;
        List<String> errors = new ArrayList<>();
        for (String raw : lines) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("[,;:=\\t ]+");
            if (parts.length < 2 || normalize(parts[0]) == null || parts[1].isBlank()) {
                errors.add("格式错误: " + line);
                continue;
            }
            String ext = normalize(parts[0]);
            String password = parts[1].trim();
            StringBuilder remark = new StringBuilder();
            for (int i = 2; i < parts.length; i++) {
                if (!parts[i].isBlank()) {
                    remark.append(parts[i]).append(' ');
                }
            }
            SipExtension existing = mapper.selectOne(new QueryWrapper<SipExtension>().eq("extension", ext));
            if (existing == null) {
                SipExtension e = new SipExtension();
                e.setExtension(ext);
                e.setPassword(password);
                e.setRemark(remark.toString().trim());
                mapper.insert(e);
                added++;
            } else {
                existing.setPassword(password);
                if (!remark.isEmpty()) {
                    existing.setRemark(remark.toString().trim());
                }
                mapper.updateById(existing);
                updated++;
            }
        }
        registry.reload();
        Logs.info(Logs.Module.SIP, "分机批量导入", "新增", added, "覆盖", updated, "失败", errors.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("added", added);
        out.put("updated", updated);
        out.put("errors", errors);
        return out;
    }

    private String normalize(String ext) {
        if (ext == null) {
            return null;
        }
        String trimmed = ext.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
