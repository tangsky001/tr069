package com.tr069.acs.api.controller;

import com.tr069.acs.acs.service.DeviceTagService;
import com.tr069.acs.common.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 设备标签：按型号 / 运营商之外的维度给设备分群。
 *
 * <p>标签本身不做权限与业务含义，就是一串名字：试点组、VIP、故障批次都往这里放，
 * 预设再按标签命中。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/device-tags")
@RequiredArgsConstructor
public class DeviceTagController {

    private final DeviceTagService tagService;

    /** 全部标签，带每个标签下的设备数。 */
    @GetMapping
    public Result<List<Map<String, Object>>> all() {
        return Result.success(tagService.allTags());
    }

    /** 带这个标签的设备。 */
    @GetMapping("/tag/{tag}/devices")
    public Result<List<String>> devices(@PathVariable String tag) {
        return Result.success(tagService.devicesOf(tag));
    }

    /** 删掉这个标签：把它从所有设备身上摘掉，标签名打错时用。 */
    @DeleteMapping("/tag/{tag}")
    public Result<Integer> removeTag(@PathVariable String tag) {
        int removed = tagService.removeEverywhere(tag);
        log.info("删除标签: tag={}, 摘掉={}", tag, removed);
        return Result.success(removed);
    }

    /** 这台设备身上的标签。 */
    @GetMapping("/{sn}")
    public Result<List<String>> tagsOf(@PathVariable String sn) {
        return Result.success(tagService.tagsOf(sn));
    }

    /** 打标签：请求体是标签数组，如 ["试点组","VIP"]。 */
    @PutMapping("/{sn}")
    public Result<Integer> add(@PathVariable String sn,
                               @RequestBody(required = false) Collection<String> tags) {
        int added = tagService.add(sn, tags);
        log.info("设备打标签: sn={}, 新增={}", sn, added);
        return Result.success(added);
    }

    /** 取消标签：请求体同上，只取消传进来的那些。 */
    @DeleteMapping("/{sn}")
    public Result<Integer> remove(@PathVariable String sn,
                                  @RequestBody(required = false) Collection<String> tags) {
        return Result.success(tagService.remove(sn, tags));
    }

    /**
     * 批量打标签：给一批设备都打上这几个标签。
     *
     * <p>已经在身上的不会重复，返回设备上新增标签的总条数——
     * 它常常小于 设备数 × 标签数，因为多数设备早就打过了。</p>
     *
     * @param body {"sns":["SN1","SN2"],"tags":["试点组"]}，sns 也接受逗号分隔
     */
    @PostMapping("/batch")
    public Result<Map<String, Integer>> addBatch(@RequestBody(required = false) Map<String, Object> body) {
        List<String> sns = snsOf(body);
        List<String> tags = tagsOf(body);
        if (sns.isEmpty() || tags.isEmpty()) {
            return Result.failure("缺少 sns 或 tags");
        }
        int added = 0;
        for (String sn : sns) {
            added += tagService.add(sn, tags);
        }
        log.info("批量打标签: 设备数={}, 标签={}, 新增={}", sns.size(), tags, added);
        return Result.success(Map.of("devices", sns.size(), "added", added));
    }

    /** 批量取消标签：请求体同上。 */
    @DeleteMapping("/batch")
    public Result<Map<String, Integer>> removeBatch(
            @RequestBody(required = false) Map<String, Object> body) {
        List<String> sns = snsOf(body);
        List<String> tags = tagsOf(body);
        if (sns.isEmpty() || tags.isEmpty()) {
            return Result.failure("缺少 sns 或 tags");
        }
        int removed = 0;
        for (String sn : sns) {
            removed += tagService.remove(sn, tags);
        }
        log.info("批量取消标签: 设备数={}, 标签={}, 取消={}", sns.size(), tags, removed);
        return Result.success(Map.of("devices", sns.size(), "removed", removed));
    }

    /** 序列号既接受数组也接受逗号分隔。 */
    private static List<String> snsOf(Map<String, Object> body) {
        return stringsOf(body, "sns");
    }

    private static List<String> tagsOf(Map<String, Object> body) {
        return stringsOf(body, "tags");
    }

    private static List<String> stringsOf(Map<String, Object> body, String key) {
        List<String> values = new ArrayList<>();
        if (body == null) {
            return values;
        }
        Object raw = body.get(key);
        if (raw instanceof Collection<?> items) {
            for (Object item : items) {
                if (item != null && !item.toString().isBlank()) {
                    values.add(item.toString().trim());
                }
            }
        } else if (raw != null && !raw.toString().isBlank()) {
            for (String part : raw.toString().split("[,;\\s]+")) {
                if (!part.isBlank()) {
                    values.add(part.trim());
                }
            }
        }
        return values;
    }
}
