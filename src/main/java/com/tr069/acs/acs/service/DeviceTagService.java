package com.tr069.acs.acs.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tr069.acs.domain.entity.CpeDeviceTag;
import com.tr069.acs.mapper.CpeDeviceTagMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 设备标签：按型号 / 运营商之外的维度分群，预设也能按标签命中。
 *
 * <p>标签存在一张 (sn, tag) 的表里，而不是在设备上加一个逗号拼起来的字段：
 * 「哪些设备带这个标签」是标签这边的高频问法，拼字段只能靠模糊匹配，
 * 标签名互相包含时会串。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceTagService {

    private final CpeDeviceTagMapper tagMapper;

    /** 这台设备的全部标签。 */
    public List<String> tagsOf(String sn) {
        if (sn == null || sn.isBlank()) {
            return List.of();
        }
        return tagMapper.selectList(new LambdaQueryWrapper<CpeDeviceTag>()
                        .eq(CpeDeviceTag::getSn, sn)
                        .orderByAsc(CpeDeviceTag::getTag))
                .stream()
                .map(CpeDeviceTag::getTag)
                .filter(Objects::nonNull)
                .toList();
    }

    /** 打标签，已经打过的跳过。 */
    public int add(String sn, Collection<String> tags) {
        if (sn == null || sn.isBlank() || tags == null) {
            return 0;
        }
        int added = 0;
        for (String raw : tags) {
            String tag = normalize(raw);
            if (tag == null) {
                continue;
            }
            Long exists = tagMapper.selectCount(new LambdaQueryWrapper<CpeDeviceTag>()
                    .eq(CpeDeviceTag::getSn, sn)
                    .eq(CpeDeviceTag::getTag, tag));
            if (exists != null && exists > 0) {
                continue;
            }
            CpeDeviceTag row = new CpeDeviceTag();
            row.setSn(sn);
            row.setTag(tag);
            try {
                tagMapper.insert(row);
                added++;
            } catch (DuplicateKeyException e) {
                // 并发下别的实例刚打过这个标签，忽略
            }
        }
        return added;
    }

    /** 取消标签。 */
    public int remove(String sn, Collection<String> tags) {
        if (sn == null || sn.isBlank() || tags == null) {
            return 0;
        }
        List<String> list = new ArrayList<>();
        for (String raw : tags) {
            String tag = normalize(raw);
            if (tag != null) {
                list.add(tag);
            }
        }
        if (list.isEmpty()) {
            return 0;
        }
        return tagMapper.delete(new LambdaQueryWrapper<CpeDeviceTag>()
                .eq(CpeDeviceTag::getSn, sn)
                .in(CpeDeviceTag::getTag, list));
    }

    /**
     * 把这个标签从所有设备身上摘掉，标签名打错时用。
     *
     * <p>删的是这个标签本身，不是删设备。</p>
     */
    public int removeEverywhere(String tag) {
        String name = normalize(tag);
        if (name == null) {
            return 0;
        }
        return tagMapper.delete(new LambdaQueryWrapper<CpeDeviceTag>()
                .eq(CpeDeviceTag::getTag, name));
    }

    /** 带这个标签的设备序列号。 */
    public List<String> devicesOf(String tag) {
        String name = normalize(tag);
        if (name == null) {
            return List.of();
        }
        return tagMapper.selectList(new LambdaQueryWrapper<CpeDeviceTag>()
                        .eq(CpeDeviceTag::getTag, name))
                .stream()
                .map(CpeDeviceTag::getSn)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /** 全部标签及每个标签下的设备数。 */
    public List<Map<String, Object>> allTags() {
        // 聚合列只能用字符串写，lambda 拼不出 count(*)
        return tagMapper.selectMaps(new QueryWrapper<CpeDeviceTag>()
                .select("tag, count(*) as deviceCount")
                .groupBy("tag")
                .orderByAsc("tag"));
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String tag = raw.trim();
        return tag.isEmpty() || tag.length() > 64 ? null : tag;
    }
}
