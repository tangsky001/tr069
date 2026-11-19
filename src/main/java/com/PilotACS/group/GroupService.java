/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.415
 *
 */

package com.PilotACS.group;

import com.PilotACS.cpe.model.BrandName;
import com.PilotACS.group.config.GroupScopeContext;
import com.PilotACS.group.entity.GroupMember;
import com.PilotACS.group.entity.ResGroup;
import com.PilotACS.group.mapper.GroupMemberMapper;
import com.PilotACS.group.mapper.ResGroupMapper;
import com.PilotACS.acl.DeviceAclService;
import com.PilotACS.common.security.LoginUser;
import com.PilotACS.common.security.UserContext;
import com.PilotACS.group.model.GroupMemberView;
import com.PilotACS.group.model.GroupMemberBrief;
import com.PilotACS.group.model.GroupNodeView;
import com.PilotACS.group.model.ProvinceMapVO;
import com.PilotACS.common.log.Logs;
import com.PilotACS.service.SysOperLogService;
import com.PilotACS.olt.entity.OltDevice;
import com.PilotACS.olt.mapper.OltDeviceMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 分组管理服务：默认只有固定根节点「资源根组」，分组节点逐级手动创建落库。 */
@Service
@RequiredArgsConstructor
public class GroupService {

    public static final String MEMBER_TYPE_OLT = "OLT";
    public static final String MEMBER_TYPE_ONU = "ONU";
    private static final String GROUP_PREFIX = "G:";

    /** 固定根节点引用：未绑定设备的默认归属 */
    public static final String ROOT_REF = "ROOT";
    public static final String ROOT_NAME = "资源根组";

    private final ResGroupMapper groupMapper;
    private final GroupMemberMapper memberMapper;
    private final OltDeviceMapper oltDeviceMapper;
    private final CivilCodeService civilCodeService;
    /** 光猫台账（cpe_device，MySQL）：ONU 树/成员装配用 */
    private final com.PilotACS.cpe.store.CpeStore cpeStore;
    /** 运营商自动识别（参数树私有节点） */
    private final com.PilotACS.cpe.service.ModelProfileService modelProfileService;

    /** 设备级 ACL：树/成员清单按用户可见性过滤 */
    private final DeviceAclService deviceAclService;
    private final SysOperLogService operLogService;

    /** 分组树：根节点 + 已落库的分组节点（区划节点只有创建过才出现）。 */
    public List<GroupNodeView> tree() {
        return tree(MEMBER_TYPE_OLT);
    }

    public List<GroupNodeView> tree(String memberType) {
        String type = normalizeMemberType(memberType);
        return GroupScopeContext.with(type, () -> doTree(type));
    }

    /** 地图上有设备的省统一用的高亮蓝 */
    private static final String PROVINCE_DEVICE_COLOR = "#5b9bd5";
    /** 地图上无设备的省统一用的中性灰 */
    private static final String PROVINCE_EMPTY_COLOR = "#eef1f6";

    /** 省份地图汇总：以区划骨架的 34 省为全集（没建过分组的省也要下发颜色，否则地图整幅灰白）， */
    public ProvinceMapVO provinceMap(String memberType) {
        String type = normalizeMemberType(memberType);
        List<GroupNodeView> tree = GroupScopeContext.with(type, () -> doTree(type));
        GroupNodeView root = tree.isEmpty() ? null : tree.get(0);
        // 树里出现过的省级节点（区划节点只有创建过才落库，所以不能只遍历树）
        Map<String, GroupNodeView> treeByName = new HashMap<>();
        if (root != null && root.getChildren() != null) {
            for (GroupNodeView node : root.getChildren()) {
                if (node.getLevel() == 1 && node.getName() != null) {
                    treeByName.put(node.getName(), node);
                }
            }
        }

        List<ProvinceMapVO.Province> rows = new ArrayList<>();
        List<CivilCodeNode> allProvinces = civilCodeService.children(null);
        int index = 0;
        for (CivilCodeNode province : allProvinces) {
            GroupNodeView hit = treeByName.get(province.getName());
            long value = hit == null ? 0 : hit.getMemberTotal();
            ProvinceMapVO.Province row = new ProvinceMapVO.Province();
            row.setName(province.getName());
            row.setValue(value);
            row.setColor(value > 0 ? PROVINCE_DEVICE_COLOR : pastelOf(index, province.getName()));
            rows.add(row);
            index++;
        }
        // 有设备的省按设备数降序排在前面（右侧清单直接按顺序渲染），无设备的殿后
        rows.sort(Comparator.comparingLong(ProvinceMapVO.Province::getValue).reversed());

        ProvinceMapVO vo = new ProvinceMapVO();
        vo.setProvinces(rows);
        return vo;
    }

    /** 无设备省份的随机观感底色：色相按黄金角（137.5°）散开——相邻省色相必然差得远， */
    private static String pastelOf(int index, String name) {
        int seed = index * 31 + (name == null ? 0 : name.hashCode());
        int hue = (int) Math.round((index * 137.508 + Math.floorMod(seed, 17)) % 360);
        int sat = 55 + Math.floorMod(seed, 3) * 10;
        int light = 78 + Math.floorMod(seed, 4) * 4;
        return hslToHex(hue, sat / 100.0, light / 100.0);
    }

    /** HSL(h:0-360, s/l:0-1) → #RRGGBB，供前端地图直接使用 */
    private static String hslToHex(int h, double s, double l) {
        double c = (1 - Math.abs(2 * l - 1)) * s;
        double hp = (h % 360) / 60.0;
        double x = c * (1 - Math.abs(hp % 2 - 1));
        double r1;
        double g1;
        double b1;
        if (hp < 1) {
            r1 = c;
            g1 = x;
            b1 = 0;
        } else if (hp < 2) {
            r1 = x;
            g1 = c;
            b1 = 0;
        } else if (hp < 3) {
            r1 = 0;
            g1 = c;
            b1 = x;
        } else if (hp < 4) {
            r1 = 0;
            g1 = x;
            b1 = c;
        } else if (hp < 5) {
            r1 = x;
            g1 = 0;
            b1 = c;
        } else {
            r1 = c;
            g1 = 0;
            b1 = x;
        }
        double m = l - c / 2;
        int r = (int) Math.round((r1 + m) * 255);
        int g = (int) Math.round((g1 + m) * 255);
        int b = (int) Math.round((b1 + m) * 255);
        return String.format("#%02x%02x%02x", r, g, b);
    }

    private List<GroupNodeView> doTree(String type) {
        // 设备级 ACL：受限用户看不到未授权设备（null = 不限制）；OLT 按 id、ONU 按 SN
        Set<Long> hidden = MEMBER_TYPE_OLT.equals(type) ? deviceAclService.hiddenOltIds(UserContext.get()) : null;
        Set<String> hiddenOnuRefs = MEMBER_TYPE_ONU.equals(type)
                ? deviceAclService.hiddenOnuRefs(UserContext.get()) : null;
        List<ResGroup> groups = groupMapper.selectList(new LambdaQueryWrapper<ResGroup>()
                .orderByAsc(ResGroup::getSort)
                .orderByAsc(ResGroup::getId));
        Map<Long, GroupNodeView> viewById = new LinkedHashMap<>();
        for (ResGroup group : groups) {
            GroupNodeView view = GroupNodeView.group(group);
            // 层级标注：按节点自己的区划编号推（省/市/区县），纯自定义节点为 0
            if (group.getCivilCode() != null) {
                CivilCodeNode civil = civilCodeService.node(group.getCivilCode());
                view.setLevel(civil == null ? 0 : civil.getLevel());
            }
            viewById.put(group.getId(), view);
        }
        List<GroupNodeView> topLevel = new ArrayList<>();
        for (GroupNodeView view : viewById.values()) {
            GroupNodeView parent = view.getParentId() == null ? null : viewById.get(view.getParentId());
            if (parent == null) {
                topLevel.add(view);
            } else {
                parent.getChildren().add(view);
            }
        }
        // 根节点：已落库的顶级分组挂在它下面
        GroupNodeView root = new GroupNodeView();
        root.setNodeRef(ROOT_REF);
        root.setKind("root");
        root.setName(ROOT_NAME);
        root.setLevel(0);
        root.getChildren().addAll(topLevel);
        // 节点直属设备名称清单（悬停提示用）：member 表里存了 label，按类型过滤
        List<GroupMember> memberRows = memberMapper.selectList(new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getMemberType, type));
        if (hidden != null) {
            memberRows.removeIf(m -> hidden.contains(m.getMemberId()));
        }
        if (hiddenOnuRefs != null) {
            memberRows.removeIf(m -> m.getMemberRef() != null && hiddenOnuRefs.contains(m.getMemberRef()));
        }
        Map<String, Long> counts = countByRef(memberRows);
        Map<String, List<GroupMemberBrief>> membersByRef = new HashMap<>();
        for (GroupMember member : memberRows) {
            membersByRef.computeIfAbsent(member.getGroupRef(), k -> new ArrayList<>())
                    .add(briefOf(member));
        }
        fillCounts(root.getChildren(), counts);
        fillMembers(root.getChildren(), membersByRef);
        // 根节点直属成员 = 未绑定任何节点的设备（默认归根）
        List<GroupMemberBrief> rootMembers = new ArrayList<>();
        if (MEMBER_TYPE_OLT.equals(type)) {
            // OLT：总数来自台账，根成员 = 台账里没写归属关系的设备
            long boundCount = 0;
            for (Map.Entry<String, Long> entry : counts.entrySet()) {
                if (!ROOT_REF.equals(entry.getKey())) {
                    boundCount += entry.getValue();
                }
            }
            Long totalDevices = oltDeviceMapper.selectCount(new LambdaQueryWrapper<OltDevice>()
                    .eq(OltDevice::getDeleted, 0));
            if (totalDevices != null && totalDevices > boundCount) {
                Set<Long> boundIds = new HashSet<>();
                for (GroupMember member : memberRows) {
                    boundIds.add(member.getMemberId());
                }
                for (OltDevice device : oltDeviceMapper.selectList(new LambdaQueryWrapper<OltDevice>()
                        .eq(OltDevice::getDeleted, 0))) {
                    if (!boundIds.contains(device.getId()) && (hidden == null || !hidden.contains(device.getId()))) {
                        rootMembers.add(new GroupMemberBrief() {{
                            setId(device.getId());
                            setRef(String.valueOf(device.getId()));
                            setName(device.getName());
                        }});
                    }
                }
            }
            int visibleTotal = totalDevices == null ? 0
                    : (int) (totalDevices - (hidden == null ? 0 : hidden.size()));
            root.setMemberCount(Math.max(visibleTotal - boundCount, 0));
            root.setMemberTotal(Math.max(visibleTotal, 0));
        } else {
            // ONU：台账在 cpe_device；根成员 = 未归属任何分组的光猫（含显式 ROOT 归属），全部走真实台账
            List<Map<String, Object>> devices = cpeStore.allDevices();
            Set<String> boundRefs = new HashSet<>();
            for (GroupMember member : memberRows) {
                if (member.getMemberRef() != null && !ROOT_REF.equals(member.getGroupRef())) {
                    boundRefs.add(member.getMemberRef());
                }
            }
            for (GroupMember member : memberRows) {
                if (ROOT_REF.equals(member.getGroupRef())) {
                    rootMembers.add(briefOf(member));
                }
            }
            for (Map<String, Object> device : devices) {
                String sn = String.valueOf(device.get("sn"));
                if (!boundRefs.contains(sn)
                        && (hiddenOnuRefs == null || !hiddenOnuRefs.contains(sn))) {
                    rootMembers.add(new GroupMemberBrief() {{
                        setRef(sn);
                        setName(onuDisplayName(device, sn));
                    }});
                }
            }
            int visibleTotal = devices.size() - (hiddenOnuRefs == null ? 0
                    : (int) devices.stream().map(d -> String.valueOf(d.get("sn")))
                    .filter(hiddenOnuRefs::contains).count());
            root.setMemberCount(rootMembers.size());
            root.setMemberTotal(Math.max(visibleTotal, 0));
        }
        root.setMemberNames(rootMembers.stream().map(GroupMemberBrief::getName).collect(Collectors.toList()));
        root.setMembers(rootMembers);
        return List.of(root);
    }

    /** 成员类型归一：空/未知 → OLT（老接口默认行为不变） */
    public static String normalizeMemberType(String memberType) {
        return MEMBER_TYPE_ONU.equalsIgnoreCase(memberType) ? MEMBER_TYPE_ONU : MEMBER_TYPE_OLT;
    }

    /** 成员简要信息：OLT 用 member_id，ONU 用 member_ref（光猫标识） */
    private GroupMemberBrief briefOf(GroupMember member) {
        GroupMemberBrief brief = new GroupMemberBrief();
        if (MEMBER_TYPE_ONU.equals(member.getMemberType())) {
            brief.setId(null);
            brief.setRef(member.getMemberRef());
            brief.setName(member.getMemberLabel() != null ? member.getMemberLabel() : member.getMemberRef());
        } else {
            brief.setId(member.getMemberId());
            brief.setRef(member.getMemberRef() != null ? member.getMemberRef() : String.valueOf(member.getMemberId()));
            brief.setName(member.getMemberLabel());
        }
        return brief;
    }

    /** 建组候选：某节点下一级可选的区划列表。 */
    public List<GroupNodeView> candidates(String parentRef) {
        return candidates(parentRef, MEMBER_TYPE_OLT);
    }

    public List<GroupNodeView> candidates(String parentRef, String memberType) {
        return GroupScopeContext.with(memberType, () -> doCandidates(parentRef));
    }

    private List<GroupNodeView> doCandidates(String parentRef) {
        String civilCode = ROOT_REF.equals(parentRef) ? null : civilOfNode(parentRef);
        if (civilCode == null && !ROOT_REF.equals(parentRef)) {
            // 自定义节点没有区划锚点，没有下级区划候选
            return List.of();
        }
        List<GroupNodeView> options = new ArrayList<>();
        for (CivilCodeNode child : civilCodeService.children(civilCode)) {
            GroupNodeView view = GroupNodeView.civil(child.getCode(), child.getName(), child.getLevel());
            view.setCivilCode(child.getCode());
            options.add(view);
        }
        return options;
    }

    /** 节点的区划编号：ROOT 视为无锚点（候选=省份），G: 查落库记录 */
    private String civilOfNode(String nodeRef) {
        if (nodeRef == null || !nodeRef.startsWith(GROUP_PREFIX)) {
            return null;
        }
        Long id = parseGroupId(nodeRef);
        ResGroup group = id == null ? null : groupMapper.selectById(id);
        return group == null ? null : group.getCivilCode();
    }

    /** 直接成员数按节点引用统计（入参行已按类型/可见性过滤） */
    private Map<String, Long> countByRef(List<GroupMember> memberRows) {
        Map<String, Long> counts = new HashMap<>();
        for (GroupMember member : memberRows) {
            counts.merge(member.getGroupRef(), 1L, Long::sum);
        }
        return counts;
    }

    /** 节点直属设备清单（不向下累加） */
    private void fillMembers(List<GroupNodeView> nodes, Map<String, List<GroupMemberBrief>> membersByRef) {
        for (GroupNodeView node : nodes) {
            List<GroupMemberBrief> members = membersByRef.get(node.getNodeRef());
            node.setMembers(members == null ? new ArrayList<>() : members);
            node.setMemberNames(members == null ? new ArrayList<>() :
                    members.stream().map(GroupMemberBrief::getName).collect(Collectors.toList()));
            fillMembers(node.getChildren(), membersByRef);
        }
    }

    /** 汇总数自底向上累加 */
    private long fillCounts(List<GroupNodeView> nodes, Map<String, Long> counts) {
        long total = 0;
        for (GroupNodeView node : nodes) {
            node.setMemberCount(counts.getOrDefault(node.getNodeRef(), 0L));
            node.setMemberTotal(node.getMemberCount() + fillCounts(node.getChildren(), counts));
            total += node.getMemberTotal();
        }
        return total;
    }

    /** 节点引用是否有效：根节点 / G: 前缀查落库记录（须在 scope 上下文内调用，默认 OLT 域） */
    public boolean refExists(String nodeRef) {
        if (nodeRef == null || nodeRef.isBlank()) {
            return false;
        }
        if (ROOT_REF.equals(nodeRef)) {
            return true;
        }
        if (nodeRef.startsWith(GROUP_PREFIX)) {
            return parseGroupId(nodeRef) != null && groupMapper.selectById(parseGroupId(nodeRef)) != null;
        }
        return false;
    }

    private Long parseGroupId(String nodeRef) {
        try {
            return Long.valueOf(nodeRef.substring(GROUP_PREFIX.length()));
        } catch (Exception e) {
            return null;
        }
    }

    /** 节点全路径文案：资源根组 / 北京市 / 海淀片区（须在 scope 上下文内调用，默认 OLT 域） */
    public String pathText(String nodeRef) {
        if (nodeRef == null || nodeRef.isBlank()) {
            return "";
        }
        if (ROOT_REF.equals(nodeRef)) {
            return ROOT_NAME;
        }
        List<String> names = new ArrayList<>();
        names.add(ROOT_NAME);
        if (nodeRef.startsWith(GROUP_PREFIX)) {
            Long groupId = parseGroupId(nodeRef);
            ResGroup current = groupId == null ? null : groupMapper.selectById(groupId);
            int guard = 0;
            while (current != null && guard++ < 20) {
                names.add(1, current.getName());
                current = current.getParentId() == null ? null : groupMapper.selectById(current.getParentId());
            }
        }
        return String.join(" / ", names);
    }

    /** 某节点的组内成员，按绑定时间倒序；根节点返回未绑定设备。 */
    public List<GroupMemberView> members(String nodeRef) {
        return members(nodeRef, MEMBER_TYPE_OLT);
    }

    public List<GroupMemberView> members(String nodeRef, String memberType) {
        String type = normalizeMemberType(memberType);
        return GroupScopeContext.with(type, () -> doMembers(nodeRef, type));
    }

    private List<GroupMemberView> doMembers(String nodeRef, String type) {
        List<GroupMemberView> rows = new ArrayList<>();
        if (!refExists(nodeRef)) {
            return rows;
        }
        if (ROOT_REF.equals(nodeRef)) {
            return MEMBER_TYPE_ONU.equals(type) ? rootOnuMembers() : rootMembers();
        }
        String groupText = pathText(nodeRef);
        List<GroupMember> bindings = memberMapper.selectList(new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getMemberType, type)
                .eq(GroupMember::getGroupRef, nodeRef)
                .orderByDesc(GroupMember::getCreatedAt));
        if (MEMBER_TYPE_ONU.equals(type)) {
            // ONU：按 SN 关联 cpe_device 真实台账（在线状态/版本/IP 等后端算好），并做 ACL 可见性过滤
            Set<String> hiddenRefs = deviceAclService.hiddenOnuRefs(UserContext.get());
            List<String> sns = bindings.stream()
                    .map(GroupMember::getMemberRef).filter(java.util.Objects::nonNull).toList();
            Map<String, Map<String, Object>> bySn = new HashMap<>();
            for (Map<String, Object> device : cpeStore.devicesBySns(sns)) {
                bySn.put(String.valueOf(device.get("sn")), device);
            }
            for (GroupMember binding : bindings) {
                String sn = binding.getMemberRef();
                if (hiddenRefs != null && sn != null && hiddenRefs.contains(sn)) {
                    continue;
                }
                rows.add(onuMemberView(binding, nodeRef, groupText, bySn.get(sn)));
            }
            return rows;
        }
        Set<Long> hidden = deviceAclService.hiddenOltIds(UserContext.get());
        for (GroupMember binding : bindings) {
            if (hidden != null && hidden.contains(binding.getMemberId())) {
                continue;
            }
            OltDevice device = oltDeviceMapper.selectById(binding.getMemberId());
            if (device == null || (device.getDeleted() != null && device.getDeleted() == 1)) {
                continue;
            }
            rows.add(memberView(binding.getId(), nodeRef, groupText, device,
                    binding.getCreatedAt(), binding.getCreatedBy()));
        }
        return rows;
    }

    /** 根节点成员 = 未绑定任何节点的 OLT（默认归属） */
    private List<GroupMemberView> rootMembers() {
        List<GroupMemberView> rows = new ArrayList<>();
        Set<Long> boundIds = new HashSet<>();
        for (GroupMember member : memberMapper.selectList(new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getMemberType, MEMBER_TYPE_OLT))) {
            boundIds.add(member.getMemberId());
        }
        List<OltDevice> devices = oltDeviceMapper.selectList(new LambdaQueryWrapper<OltDevice>()
                .eq(OltDevice::getDeleted, 0)
                .orderByAsc(OltDevice::getId));
        Set<Long> hidden = deviceAclService.hiddenOltIds(UserContext.get());
        for (OltDevice device : devices) {
            if (!boundIds.contains(device.getId()) && (hidden == null || !hidden.contains(device.getId()))) {
                rows.add(memberView(null, ROOT_REF, ROOT_NAME, device, null, null));
            }
        }
        return rows;
    }

    /** ONU 根节点成员 = 全部光猫（含归属在子分组的），真实台账展示；实际归属看 actualGroupText */
    private List<GroupMemberView> rootOnuMembers() {
        List<GroupMemberView> rows = new ArrayList<>();
        Map<String, GroupMember> bindingBySn = onuBindingsBySn();
        Set<String> hiddenRefs = deviceAclService.hiddenOnuRefs(UserContext.get());
        for (Map<String, Object> device : cpeStore.allDevices()) {
            String sn = String.valueOf(device.get("sn"));
            if (hiddenRefs != null && hiddenRefs.contains(sn)) {
                continue;
            }
            rows.add(onuMemberView(bindingBySn.get(sn), ROOT_REF, ROOT_NAME, device));
        }
        return rows;
    }

    /** ONU 全部归属记录按 SN 归一（同 SN 取最新一条） */
    private Map<String, GroupMember> onuBindingsBySn() {
        Map<String, GroupMember> bySn = new HashMap<>();
        for (GroupMember binding : memberMapper.selectList(new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getMemberType, MEMBER_TYPE_ONU)
                .orderByDesc(GroupMember::getCreatedAt))) {
            if (binding.getMemberRef() != null) {
                bySn.putIfAbsent(binding.getMemberRef(), binding);
            }
        }
        return bySn;
    }

    /** ONU 成员行组装：归属记录 + 真实台账（deviceRow 可能为 null，如台账已被清理） */
    private GroupMemberView onuMemberView(GroupMember binding, String nodeRef, String groupText,
                                          Map<String, Object> device) {
        GroupMemberView row = new GroupMemberView();
        row.setId(binding == null ? null : binding.getId());
        row.setNodeRef(nodeRef);
        row.setGroupText(groupText);
        row.setActualGroupText(binding == null || binding.getGroupRef() == null
                ? ROOT_NAME : pathText(binding.getGroupRef()));
        row.setOltId(null);
        String sn = device != null ? String.valueOf(device.get("sn"))
                : (binding == null ? null : binding.getMemberRef());
        row.setMemberRef(sn);
        row.setName(device != null ? onuDisplayName(device, sn)
                : (binding != null && binding.getMemberLabel() != null ? binding.getMemberLabel() : sn));
        row.setHost(device == null ? null : (String) device.get("connection_request_url"));
        row.setVendorText(device == null || isBlank(device.get("manufacturer"))
                ? "—" : orBrand(String.valueOf(device.get("manufacturer"))));
        String status = device == null ? null : String.valueOf(device.get("status"));
        row.setStatus(status);
        row.setStatusText(statusText(status));
        row.setStatusLevel(statusLevel(status));
        if (device != null) {
            row.setSoftwareVersion((String) device.get("software_version"));
            Object roomNo = device.get("room_no");
            row.setRoomNo(roomNo == null ? "" : String.valueOf(roomNo));
            Object remark = device.get("remark");
            row.setRemark(remark == null ? "" : String.valueOf(remark));
            String isp = String.valueOf(device.get("isp"));
            if (isp == null || "null".equals(isp) || isp.isBlank()) {
                isp = modelProfileService.detectIsp(sn);
            }
            row.setIsp(isp);
            Object last = device.get("last_inform_ms");
            row.setLastInformText(last == null ? "—" : fmtMs(((Number) last).longValue()));
            row.setIp(cpeStore.loadBizParams(sn).entrySet().stream()
                    .filter(e -> e.getKey().endsWith("ExternalIPAddress"))
                    .map(Map.Entry::getValue).findFirst().orElse(null));
        }
        row.setBoundAt(binding == null ? null : binding.getCreatedAt());
        row.setCreatedBy(binding == null ? null : binding.getCreatedBy());
        return row;
    }

    /** 光猫显示名：优先终端型号，缺省 SN */
    private String onuDisplayName(Map<String, Object> device, String sn) {
        Object productClass = device.get("product_class");
        return productClass == null || String.valueOf(productClass).isBlank() ? sn : String.valueOf(productClass);
    }

    /** 厂商显示：全称（Huawei Technologies Co., Ltd / CIOT）→ 中文简称，未登记原样返回 */
    private String orBrand(String manufacturer) {
        String label = BrandName.label(manufacturer);
        return label != null ? label : BrandName.text(manufacturer);
    }

    private boolean isBlank(Object v) {
        return v == null || String.valueOf(v).isBlank();
    }

    /** 毫秒时间戳 → 展示文案 */
    private String fmtMs(long ms) {
        return java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms),
                java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    /** 成员行组装：绑定关系 + 实时台账，展示文案这里算好 */
    private GroupMemberView memberView(Long bindingId, String nodeRef, String groupText,
                                       OltDevice device, LocalDateTime boundAt, String createdBy) {
        GroupMemberView row = new GroupMemberView();
        row.setId(bindingId);
        row.setNodeRef(nodeRef);
        row.setGroupText(groupText);
        row.setOltId(device.getId());
        row.setMemberRef(String.valueOf(device.getId()));
        row.setName(device.getName());
        row.setHost(device.getHost());
        row.setVendorText(vendorText(device.getVendor()));
        String status = device.getStatus() == null ? "unknown" : device.getStatus();
        row.setStatus(status);
        row.setStatusText(statusText(status));
        row.setStatusLevel(statusLevel(status));
        row.setBoundAt(boundAt);
        row.setCreatedBy(createdBy);
        return row;
    }

    /** 创建分组（落库）：parentRef 指向父节点（null=根节点下）。 */
    public String create(String parentRef, String civilCode, String name, String operator) {
        return create(parentRef, civilCode, name, operator, MEMBER_TYPE_OLT);
    }

    public String create(String parentRef, String civilCode, String name, String operator, String memberType) {
        return GroupScopeContext.with(memberType,
                () -> doCreate(parentRef, civilCode, name, operator));
    }

    private String doCreate(String parentRef, String civilCode, String name, String operator) {
        Long parentId = null;
        if (parentRef != null && !parentRef.isBlank() && !ROOT_REF.equals(parentRef)) {
            if (!parentRef.startsWith(GROUP_PREFIX)) {
                return "父节点引用不合法：" + parentRef;
            }
            parentId = parseGroupId(parentRef);
            if (parentId == null || groupMapper.selectById(parentId) == null) {
                return "父分组不存在";
            }
        }
        // 父节点的区划锚点：决定子节点可选的区划层级
        ResGroup parentGroup = parentId == null ? null : groupMapper.selectById(parentId);
        String parentCivil = parentGroup == null ? null : parentGroup.getCivilCode();

        String finalName;
        if (civilCode != null && !civilCode.isBlank()) {
            civilCode = civilCode.trim();
            CivilCodeNode civil = civilCodeService.node(civilCode);
            if (civil == null) {
                return "区划不存在：" + civilCode;
            }
            // 层级衔接校验：所选区划的上级必须正好是父节点的区划（根节点下只能建省级）
            List<CivilCodeNode> chain = civilCodeService.path(civilCode);
            String expectedParent = chain.size() >= 2 ? chain.get(chain.size() - 2).getCode() : null;
            if (parentCivil == null ? expectedParent != null : !parentCivil.equals(expectedParent)) {
                return "区划层级不衔接：「" + civil.getName() + "」不能挂在当前节点下";
            }
            finalName = name == null || name.isBlank() ? civil.getName() : name.trim();
        } else {
            if (name == null || name.isBlank()) {
                return "请选择区划或填写分组名称";
            }
            finalName = name.trim();
        }
        // 同级同名提醒式拦截，避免手抖建出两个一样的组
        Long same = groupMapper.selectCount(new LambdaQueryWrapper<ResGroup>()
                .eq(ResGroup::getName, finalName)
                .eq(parentId == null, ResGroup::getParentId, null)
                .eq(parentId != null, ResGroup::getParentId, parentId));
        if (same != null && same > 0) {
            return "同级已有同名分组：" + finalName;
        }
        ResGroup group = new ResGroup();
        group.setParentId(parentId);
        group.setCivilCode(civilCode == null || civilCode.isBlank() ? null : civilCode);
        group.setName(finalName);
        group.setSort(same == null ? 0 : same.intValue());
        group.setCreatedBy(operator);
        group.setCreatedAt(LocalDateTime.now());
        groupMapper.insert(group);
        operLogService.log("分组", "新建分组", "「" + finalName + "」" + (parentId == null ? "（根节点下）" : "（父节点 id=" + parentId + "）") + "，创建人=" + operator);
        Logs.info(Logs.Module.GROUP, "新建分组", "名称", finalName, "创建人", operator);
        return null;
    }

    /** 重命名分组 */
    public String rename(Long id, String name) {
        return rename(id, name, MEMBER_TYPE_OLT);
    }

    public String rename(Long id, String name, String memberType) {
        return GroupScopeContext.with(memberType, () -> doRename(id, name));
    }

    private String doRename(Long id, String name) {
        ResGroup group = groupMapper.selectById(id);
        if (group == null) {
            return "分组不存在";
        }
        if (name == null || name.isBlank()) {
            return "分组名称不能为空";
        }
        group.setName(name.trim());
        groupMapper.updateById(group);
        operLogService.log("分组", "重命名分组", "id=" + id + " 「" + name.trim() + "」");
        Logs.info(Logs.Module.GROUP, "重命名分组", "id", id, "新名称", name.trim());
        return null;
    }

    /** 删除分组：无下级且无绑定成员才允许；根节点不可删 */
    @Transactional
    public String delete(String nodeRef) {
        return delete(nodeRef, MEMBER_TYPE_OLT);
    }

    @Transactional
    public String delete(String nodeRef, String memberType) {
        return GroupScopeContext.with(memberType, () -> doDelete(nodeRef));
    }

    private String doDelete(String nodeRef) {
        if (ROOT_REF.equals(nodeRef)) {
            return "根节点不可删除";
        }
        if (nodeRef == null || !nodeRef.startsWith(GROUP_PREFIX)) {
            return "节点引用不合法";
        }
        Long id = parseGroupId(nodeRef);
        ResGroup group = id == null ? null : groupMapper.selectById(id);
        if (group == null) {
            return "分组不存在";
        }
        Long children = groupMapper.selectCount(new LambdaQueryWrapper<ResGroup>()
                .eq(ResGroup::getParentId, id));
        if (children != null && children > 0) {
            return "分组下还有子分组，先删除子分组";
        }
        Long members = memberMapper.selectCount(new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getGroupRef, nodeRef));
        if (members != null && members > 0) {
            return "分组下还有设备，先把设备删除或重新添加到别的分组";
        }
        groupMapper.deleteById(id);
        operLogService.log("分组", "删除分组", "「" + group.getName() + "」(id=" + id + ")");
        Logs.info(Logs.Module.GROUP, "删除分组", "名称", group.getName(), "id", id);
        return null;
    }

    /** 设备添加即落组：OltService 新增 OLT 时调用。 */
    public String attachAfterCreate(String groupRef, Long oltId, String label) {
        return GroupScopeContext.with(MEMBER_TYPE_OLT, () -> doAttachAfterCreate(groupRef, oltId, label));
    }

    private String doAttachAfterCreate(String groupRef, Long oltId, String label) {
        if (groupRef == null || groupRef.isBlank() || ROOT_REF.equals(groupRef)) {
            return null;
        }
        if (!refExists(groupRef)) {
            return "归属分组不存在：" + groupRef;
        }
        GroupMember member = new GroupMember();
        member.setGroupRef(groupRef);
        member.setMemberType(MEMBER_TYPE_OLT);
        member.setMemberId(oltId);
        member.setMemberRef(String.valueOf(oltId));
        member.setMemberLabel(label);
        member.setCreatedAt(LocalDateTime.now());
        memberMapper.insert(member);
        return null;
    }

    /** 通用归属落库：光猫等设备模块接入时调用。 */
    public String attach(String memberType, String memberRef, String label, String groupRef) {
        String type = normalizeMemberType(memberType);
        return GroupScopeContext.with(type, () -> doAttach(type, memberRef, label, groupRef));
    }

    private String doAttach(String type, String memberRef, String label, String groupRef) {
        if (memberRef == null || memberRef.isBlank()) {
            return "设备标识不能为空";
        }
        if (groupRef == null || groupRef.isBlank() || ROOT_REF.equals(groupRef)) {
            return null;
        }
        if (!refExists(groupRef)) {
            return "归属分组不存在：" + groupRef;
        }
        Long count = memberMapper.selectCount(new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getMemberType, type)
                .eq(GroupMember::getMemberRef, memberRef));
        if (count != null && count > 0) {
            return "该设备已有归属分组，请先移动而不是重复添加";
        }
        GroupMember member = new GroupMember();
        member.setGroupRef(groupRef);
        member.setMemberType(type);
        member.setMemberId(0L);
        member.setMemberRef(memberRef);
        member.setMemberLabel(label);
        member.setCreatedAt(LocalDateTime.now());
        memberMapper.insert(member);
        Logs.info(Logs.Module.GROUP, "设备归属", "类型", type, "标识", memberRef, "分组", groupRef);
        return null;
    }

    /** 移动设备到目标分组：ROOT = 移回根节点（删除归属关系）。 */
    public String move(String targetRef, Long oltId, String operator) {
        return GroupScopeContext.with(MEMBER_TYPE_OLT, () -> doMove(targetRef, oltId, operator));
    }

    private String doMove(String targetRef, Long oltId, String operator) {
        if (!refExists(targetRef)) {
            return "目标分组不存在";
        }
        OltDevice device = oltDeviceMapper.selectById(oltId);
        if (device == null || (device.getDeleted() != null && device.getDeleted() == 1)) {
            return "OLT 设备不存在或已删除";
        }
        GroupMember existing = memberMapper.selectOne(new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getMemberType, MEMBER_TYPE_OLT)
                .eq(GroupMember::getMemberId, oltId)
                .last("LIMIT 1"));
        // 移回根节点 = 回到默认归属：删掉归属关系即可
        if (ROOT_REF.equals(targetRef)) {
            if (existing == null) {
                return "该设备已在根节点";
            }
            memberMapper.deleteById(existing.getId());
            operLogService.log("分组", "移动设备", "「" + device.getName() + "」→ 根节点，操作人=" + operator);
            Logs.info(Logs.Module.GROUP, "移动设备", "设备", device.getName(), "目标", "根节点");
            return null;
        }
        if (existing != null) {
            if (targetRef.equals(existing.getGroupRef())) {
                return "该设备已在此分组";
            }
            existing.setGroupRef(targetRef);
            memberMapper.updateById(existing);
            operLogService.log("分组", "移动设备", "「" + device.getName() + "」→ " + targetRef + "，操作人=" + operator);
            Logs.info(Logs.Module.GROUP, "移动设备", "设备", device.getName(), "目标", targetRef);
            return null;
        }
        GroupMember member = new GroupMember();
        member.setGroupRef(targetRef);
        member.setMemberType(MEMBER_TYPE_OLT);
        member.setMemberId(oltId);
        member.setMemberRef(String.valueOf(oltId));
        member.setMemberLabel(device.getName());
        member.setCreatedBy(operator);
        member.setCreatedAt(LocalDateTime.now());
        memberMapper.insert(member);
        operLogService.log("分组", "移动设备", "「" + device.getName() + "」→ " + targetRef + "，操作人=" + operator);
        Logs.info(Logs.Module.GROUP, "移动设备", "设备", device.getName(), "目标", targetRef);
        return null;
    }

    /** 通用移动：光猫等设备模块接入时调用。 */
    public String moveMember(String targetRef, String memberType, String memberRef, String operator) {
        String type = normalizeMemberType(memberType);
        return GroupScopeContext.with(type, () -> doMoveMember(targetRef, type, memberRef, operator));
    }

    private String doMoveMember(String targetRef, String type, String memberRef, String operator) {
        if (!refExists(targetRef)) {
            return "目标分组不存在";
        }
        if (memberRef == null || memberRef.isBlank()) {
            return "设备标识不能为空";
        }
        if (MEMBER_TYPE_OLT.equals(type)) {
            // OLT：标识就是 olt_device.id，走台账校验的老逻辑
            try {
                return move(targetRef, Long.valueOf(memberRef), operator);
            } catch (NumberFormatException e) {
                return "OLT 设备标识不合法：" + memberRef;
            }
        }
        GroupMember existing = memberMapper.selectOne(new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getMemberType, type)
                .eq(GroupMember::getMemberRef, memberRef)
                .last("LIMIT 1"));
        String label = existing != null && existing.getMemberLabel() != null ? existing.getMemberLabel() : memberRef;
        // 移回根节点 = 回到默认归属：删掉归属关系即可
        if (ROOT_REF.equals(targetRef)) {
            if (existing == null) {
                return "该设备已在根节点";
            }
            memberMapper.deleteById(existing.getId());
            operLogService.log("分组", "移动设备", "「" + label + "」→ 根节点，操作人=" + operator);
            Logs.info(Logs.Module.GROUP, "移动设备", "类型", type, "设备", label, "目标", "根节点");
            return null;
        }
        if (existing != null) {
            if (targetRef.equals(existing.getGroupRef())) {
                return "该设备已在此分组";
            }
            existing.setGroupRef(targetRef);
            memberMapper.updateById(existing);
            operLogService.log("分组", "移动设备", "「" + label + "」→ " + targetRef + "，操作人=" + operator);
            Logs.info(Logs.Module.GROUP, "移动设备", "类型", type, "设备", label, "目标", targetRef);
            return null;
        }
        GroupMember member = new GroupMember();
        member.setGroupRef(targetRef);
        member.setMemberType(type);
        member.setMemberId(0L);
        member.setMemberRef(memberRef);
        member.setMemberLabel(label);
        member.setCreatedBy(operator);
        member.setCreatedAt(LocalDateTime.now());
        memberMapper.insert(member);
        operLogService.log("分组", "移动设备", "「" + label + "」→ " + targetRef + "，操作人=" + operator);
        Logs.info(Logs.Module.GROUP, "移动设备", "类型", type, "设备", label, "目标", targetRef);
        return null;
    }

    /** 设备删除时清理归属关系，避免孤儿记录 */
    public void onDeviceDeleted(Long oltId) {
        GroupScopeContext.with(MEMBER_TYPE_OLT, () -> memberMapper.delete(new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getMemberType, MEMBER_TYPE_OLT)
                .eq(GroupMember::getMemberId, oltId)));
    }

    /** 通用清理：设备模块删除设备时调用（ONU 按业务标识清） */
    public void onMemberDeleted(String memberType, String memberRef) {
        String type = normalizeMemberType(memberType);
        GroupScopeContext.with(type, () -> doMemberDeleted(type, memberRef));
    }

    private void doMemberDeleted(String type, String memberRef) {
        LambdaQueryWrapper<GroupMember> wrapper = new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getMemberType, type);
        if (MEMBER_TYPE_OLT.equals(type)) {
            try {
                wrapper.eq(GroupMember::getMemberId, Long.valueOf(memberRef));
            } catch (NumberFormatException e) {
                return;
            }
        } else {
            wrapper.eq(GroupMember::getMemberRef, memberRef);
        }
        memberMapper.delete(wrapper);
    }

    private String vendorText(String vendor) {
        if (vendor == null || vendor.isBlank()) {
            return "—";
        }
        return switch (vendor.toUpperCase()) {
            case "HUAWEI" -> "华为";
            case "HSGQ" -> "鸿升光";
            case "ZTE" -> "中兴";
            case "FIBERHOME" -> "烽火";
            case "GENERIC" -> "通用";
            default -> vendor;
        };
    }

    private String statusText(String status) {
        return switch (status == null ? "" : status.toUpperCase()) {
            case "ONLINE" -> "在线";
            case "OFFLINE" -> "离线";
            default -> "未采集";
        };
    }

    private String statusLevel(String status) {
        return switch (status == null ? "" : status.toUpperCase()) {
            case "ONLINE" -> "success";
            case "OFFLINE" -> "danger";
            default -> "info";
        };
    }
}
