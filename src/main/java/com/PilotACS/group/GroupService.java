package com.PilotACS.group;

import com.PilotACS.group.entity.GroupMember;
import com.PilotACS.group.entity.ResGroup;
import com.PilotACS.group.mapper.GroupMemberMapper;
import com.PilotACS.group.mapper.ResGroupMapper;
import com.PilotACS.group.model.GroupMemberView;
import com.PilotACS.group.model.GroupNodeView;
import com.PilotACS.group.model.GroupOltOption;
import com.PilotACS.olt.entity.OltDevice;
import com.PilotACS.olt.mapper.OltDeviceMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 分组管理服务：默认只有固定根节点「资源根组」，分组节点逐级手动创建落库。
 *
 * <p>建组流程：右键节点 → 弹窗列出该节点区划的下级候选（根节点列 34 省，
 * 省节点列市，市节点列区县；自定义节点只能手输名称）→ 选中后确定才落库 res_group。
 * 节点落库时记录 civil_code（所选区划编号），下一级的候选就由它决定。</p>
 *
 * <p>节点引用 nodeRef：{@code ROOT}（固定根，未绑定设备的默认归属）/ {@code G:id}（已落库分组）。
 * 约束：一台 OLT 只挂一个节点；区划候选必须与父节点的区划层级衔接（省下选市、市下选区县）。</p>
 */
@Service
@RequiredArgsConstructor
public class GroupService {

    private static final String MEMBER_TYPE_OLT = "OLT";
    private static final String GROUP_PREFIX = "G:";

    /** 固定根节点引用：未绑定设备的默认归属 */
    public static final String ROOT_REF = "ROOT";
    public static final String ROOT_NAME = "资源根组";

    private final ResGroupMapper groupMapper;
    private final GroupMemberMapper memberMapper;
    private final OltDeviceMapper oltDeviceMapper;
    private final CivilCodeService civilCodeService;

    /** 分组树：根节点 + 已落库的分组节点（区划节点只有创建过才出现） */
    public List<GroupNodeView> tree() {
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
        // 根节点直属成员 = 未绑定任何节点的设备（默认归根）
        Map<String, Long> counts = countByRef();
        long boundCount = 0;
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            if (!ROOT_REF.equals(entry.getKey())) {
                boundCount += entry.getValue();
            }
        }
        Long totalDevices = oltDeviceMapper.selectCount(new LambdaQueryWrapper<OltDevice>()
                .eq(OltDevice::getDeleted, 0));
        fillCounts(root.getChildren(), counts);
        root.setMemberCount(Math.max((totalDevices == null ? 0 : totalDevices) - boundCount, 0));
        root.setMemberTotal(totalDevices == null ? 0 : totalDevices);
        return List.of(root);
    }

    /**
     * 建组候选：某节点下一级可选的区划列表。
     * 根节点 → 34 个省份；省节点 → 市；市节点 → 区县；区县/自定义节点 → 无候选（只能手输名称）。
     */
    public List<GroupNodeView> candidates(String parentRef) {
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

    /** 直接成员数按节点引用统计 */
    private Map<String, Long> countByRef() {
        Map<String, Long> counts = new HashMap<>();
        for (GroupMember member : memberMapper.selectList(null)) {
            counts.merge(member.getGroupRef(), 1L, Long::sum);
        }
        return counts;
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

    /** 节点引用是否有效：根节点 / G: 前缀查落库记录 */
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

    /** 节点全路径文案：资源根组 / 北京市 / 海淀片区 */
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

    /** 某节点的组内成员（OLT 实时台账 + 状态文案），按绑定时间倒序；根节点返回未绑定设备 */
    public List<GroupMemberView> members(String nodeRef) {
        List<GroupMemberView> rows = new ArrayList<>();
        if (!refExists(nodeRef)) {
            return rows;
        }
        if (ROOT_REF.equals(nodeRef)) {
            return rootMembers();
        }
        String groupText = pathText(nodeRef);
        List<GroupMember> bindings = memberMapper.selectList(new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getGroupRef, nodeRef)
                .orderByDesc(GroupMember::getCreatedAt));
        for (GroupMember binding : bindings) {
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
        for (OltDevice device : devices) {
            if (!boundIds.contains(device.getId())) {
                rows.add(memberView(null, ROOT_REF, ROOT_NAME, device, null, null));
            }
        }
        return rows;
    }

    /** 成员行组装：绑定关系 + 实时台账，展示文案这里算好 */
    private GroupMemberView memberView(Long bindingId, String nodeRef, String groupText,
                                       OltDevice device, LocalDateTime boundAt, String createdBy) {
        GroupMemberView row = new GroupMemberView();
        row.setId(bindingId);
        row.setNodeRef(nodeRef);
        row.setGroupText(groupText);
        row.setOltId(device.getId());
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

    /** 可绑定的 OLT 选项：全部未删除台账 + 各自当前绑定位置，绑定弹窗用 */
    public List<GroupOltOption> bindable(String keyword) {
        List<GroupOltOption> options = new ArrayList<>();
        List<OltDevice> devices = oltDeviceMapper.selectList(new LambdaQueryWrapper<OltDevice>()
                .eq(OltDevice::getDeleted, 0)
                .orderByAsc(OltDevice::getId));
        // OLT 当前所在节点：member_id → group_ref
        Map<Long, String> bound = new HashMap<>();
        for (GroupMember member : memberMapper.selectList(new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getMemberType, MEMBER_TYPE_OLT))) {
            bound.put(member.getMemberId(), member.getGroupRef());
        }
        Map<String, String> pathCache = new HashMap<>();
        for (OltDevice device : devices) {
            if (keyword != null && !keyword.isBlank()
                    && !device.getName().contains(keyword.trim())
                    && !device.getHost().contains(keyword.trim())) {
                continue;
            }
            GroupOltOption option = new GroupOltOption();
            option.setId(device.getId());
            option.setName(device.getName());
            option.setHost(device.getHost());
            option.setVendorText(vendorText(device.getVendor()));
            String status = device.getStatus() == null ? "unknown" : device.getStatus();
            option.setStatusText(statusText(status));
            String boundRef = bound.get(device.getId());
            option.setBoundCode(boundRef);
            option.setBoundText(boundRef == null ? "" : pathCache.computeIfAbsent(boundRef, this::pathText));
            options.add(option);
        }
        return options;
    }

    /**
     * 创建分组（落库）：parentRef 指向父节点（null=根节点下）。
     * 选了区划（civilCode）必须与父节点区划层级衔接（省下选市、市下选区县），
     * 名称缺省用区划名；不选区划则按自定义名称建组。
     */
    public String create(String parentRef, String civilCode, String name, String operator) {
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
        return null;
    }

    /** 重命名分组 */
    public String rename(Long id, String name) {
        ResGroup group = groupMapper.selectById(id);
        if (group == null) {
            return "分组不存在";
        }
        if (name == null || name.isBlank()) {
            return "分组名称不能为空";
        }
        group.setName(name.trim());
        groupMapper.updateById(group);
        return null;
    }

    /** 删除分组：无下级且无绑定成员才允许；根节点不可删 */
    @Transactional
    public String delete(String nodeRef) {
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
            return "分组下还有绑定的设备，先解绑";
        }
        groupMapper.deleteById(id);
        return null;
    }

    /**
     * 绑定：一台 OLT 同时只挂一个节点。
     * 根节点是默认归属——绑到根节点 = 解除现有绑定回到根；已在别的节点时返回失败并带上现有路径文案。
     */
    public String bind(String nodeRef, Long oltId, String operator) {
        if (!refExists(nodeRef)) {
            return "分组节点不存在";
        }
        OltDevice device = oltDeviceMapper.selectById(oltId);
        if (device == null || (device.getDeleted() != null && device.getDeleted() == 1)) {
            return "OLT 设备不存在或已删除";
        }
        GroupMember existing = memberMapper.selectOne(new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getMemberType, MEMBER_TYPE_OLT)
                .eq(GroupMember::getMemberId, oltId)
                .last("LIMIT 1"));
        // 绑到根节点 = 回到默认归属：解除现有绑定即可
        if (ROOT_REF.equals(nodeRef)) {
            if (existing == null) {
                return "该 OLT 未绑定，本就在根节点";
            }
            memberMapper.deleteById(existing.getId());
            return null;
        }
        if (existing != null) {
            if (nodeRef.equals(existing.getGroupRef())) {
                return "该 OLT 已绑定在此节点";
            }
            return "该 OLT 已绑定在「" + pathText(existing.getGroupRef()) + "」，请先解绑再换";
        }
        GroupMember member = new GroupMember();
        member.setGroupRef(nodeRef);
        member.setMemberType(MEMBER_TYPE_OLT);
        member.setMemberId(oltId);
        member.setMemberLabel(device.getName());
        member.setCreatedBy(operator);
        member.setCreatedAt(LocalDateTime.now());
        memberMapper.insert(member);
        return null;
    }

    /** 解绑：按绑定关系主键；解绑后设备回到根节点 */
    public String unbind(Long id) {
        if (id == null) {
            return "绑定关系 id 不能为空";
        }
        return memberMapper.deleteById(id) > 0 ? null : "绑定关系不存在";
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
