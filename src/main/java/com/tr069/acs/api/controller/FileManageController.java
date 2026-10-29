package com.tr069.acs.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tr069.acs.acs.service.CpeFileService;
import com.tr069.acs.common.Result;
import com.tr069.acs.domain.entity.CpeFile;
import com.tr069.acs.agent.RpcDispatchService;
import com.tr069.acs.mapper.CpeFileMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件台账：固件的上传、清单与删除。
 *
 * <p>上传走的还是 {@code /api}，要令牌；设备取文件走 {@code /files/{id}}，不要令牌。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileManageController {

    /** 单个文件大小上限，防的是误传一个几个 G 的东西把磁盘塞满。 */
    private static final long MAX_SIZE = 512L * 1024 * 1024;

    private final CpeFileMapper fileMapper;
    private final CpeFileService fileService;
    private final RpcDispatchService rpcDispatchService;

    /** 文件清单，新的在前。 */
    @GetMapping
    public Result<List<CpeFile>> list() {
        return Result.success(fileMapper.selectList(
                new LambdaQueryWrapper<CpeFile>().orderByDesc(CpeFile::getId)));
    }

    /** 文件详情。 */
    @GetMapping("/{id}")
    public Result<CpeFile> detail(@PathVariable Long id) {
        CpeFile file = fileMapper.selectById(id);
        return file == null ? Result.failure("文件不存在") : Result.success(file);
    }

    /** 设备取这个文件用的地址，下发 Download 时填的就是它。 */
    @GetMapping("/{id}/url")
    public Result<String> url(@PathVariable Long id) {
        if (fileMapper.selectById(id) == null) {
            return Result.failure("文件不存在");
        }
        return Result.success(fileService.urlOf(id));
    }

    /**
     * 上传文件。
     *
     * @param fileType FIRMWARE 固件 / CONFIG 配置 / VENDOR 厂商文件，不填按固件
     */
    @PostMapping
    public Result<CpeFile> upload(@RequestParam("file") MultipartFile file,
                                  @RequestParam(value = "fileType", required = false) String fileType,
                                  @RequestParam(value = "version", required = false) String version,
                                  @RequestParam(value = "remark", required = false) String remark) {
        if (file == null || file.isEmpty()) {
            return Result.failure("文件不能为空");
        }
        if (file.getSize() > MAX_SIZE) {
            return Result.failure("文件超过 512MB 上限");
        }
        try {
            CpeFile saved = fileService.save(file.getOriginalFilename(), fileType, version, remark,
                    file.getInputStream());
            return Result.success(saved);
        } catch (Exception e) {
            log.warn("保存文件失败: {}", e.getMessage());
            return Result.failure("保存文件失败: " + e.getMessage());
        }
    }

    /**
     * 把这个文件下发给一批设备（Download），升级固件走这个口。
     *
     * <p>每台设备各排一条 Download：队列是按设备分流的，多设备之间不会互相堵。
     * 设备下载完会主动上报 TransferComplete，结果用返回的 taskId 查。</p>
     *
     * @param sns 设备序列号，数组或逗号分隔都行
     */
    @PostMapping("/{id}/deliver")
    public Result<List<Map<String, Object>>> deliver(@PathVariable Long id,
                                                     @RequestBody(required = false) Map<String, Object> body) {
        CpeFile file = fileMapper.selectById(id);
        if (file == null) {
            return Result.failure("文件不存在");
        }
        List<String> sns = snsOf(body);
        if (sns.isEmpty()) {
            return Result.failure("没有指定设备");
        }
        String fileType = body == null || body.get("fileType") == null
                ? file.getFileType() : String.valueOf(body.get("fileType"));
        int delaySeconds = intOf(body == null ? null : body.get("delaySeconds"), 0);
        String url = fileService.urlOf(id);

        List<Map<String, Object>> results = new ArrayList<>();
        for (String sn : sns) {
            // 顺序就是 SOAP 里的元素顺序，别改成无序的 map
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("CommandKey", "file-" + id);
            args.put("FileType", cwmpFileType(fileType));
            args.put("URL", url);
            args.put("FileSize", file.getSize() == null ? 0 : file.getSize());
            args.put("TargetFileName", file.getName());
            args.put("DelaySeconds", delaySeconds);
            RpcDispatchService.DispatchHandle handle =
                    rpcDispatchService.submitAsync(sn, "Download", args, null);
            Map<String, Object> one = new HashMap<>();
            one.put("sn", sn);
            one.put("taskId", handle.getTaskId());
            one.put("queued", handle.isQueued());
            results.add(one);
        }
        log.info("文件下发: fileId={}, 设备数={}, url={}", id, sns.size(), url);
        return Result.success(results);
    }

    /** 台账上的类型转成 CWMP 下载的文件类型。 */
    private static String cwmpFileType(String fileType) {
        String type = fileType == null ? "" : fileType.trim().toUpperCase();
        if ("CONFIG".equals(type) || "VENDOR".equals(type)) {
            return "3 Vendor Configuration File";
        }
        return "1 Firmware Upgrade Image";
    }

    /** 序列号既接受数组也接受逗号分隔。 */
    private static List<String> snsOf(Map<String, Object> body) {
        List<String> sns = new ArrayList<>();
        if (body == null) {
            return sns;
        }
        Object raw = body.get("sns");
        if (raw instanceof Collection<?> items) {
            for (Object item : items) {
                if (item != null && !item.toString().isBlank()) {
                    sns.add(item.toString().trim());
                }
            }
        } else if (raw != null && !raw.toString().isBlank()) {
            for (String part : raw.toString().split("[,;\\s]+")) {
                if (!part.isBlank()) {
                    sns.add(part.trim());
                }
            }
        }
        return sns;
    }

    private static int intOf(Object raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 删除文件，连带清掉磁盘上的内容。 */
    @DeleteMapping("/{id}")
    public Result<Boolean> delete(@PathVariable Long id) {
        CpeFile file = fileMapper.selectById(id);
        if (file == null) {
            return Result.failure("文件不存在");
        }
        fileService.delete(file);
        return Result.success(true);
    }
}
