/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.359
 *
 */

package com.PilotACS.acl;

import com.PilotACS.common.log.Logs;
import com.PilotACS.group.GroupService;
import com.PilotACS.group.config.GroupScopeContext;
import com.PilotACS.common.security.LoginUser;
import com.PilotACS.common.security.UserContext;
import com.PilotACS.service.SysOperLogService;
import com.PilotACS.group.entity.GroupMember;
import com.PilotACS.group.entity.ResGroup;
import com.PilotACS.group.mapper.GroupMemberMapper;
import com.PilotACS.group.mapper.ResGroupMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 设备/分组级 ACL 判定与维护。 */
@Service
@RequiredArgsConstructor
public class DeviceAclService {

    public static final String SCOPE_DEVICE = "device";
    public static final String SCOPE_GROUP = "group";
    public static final String GROUP_PREFIX = "G:";
    public static final String ROOT_REF = "ROOT";

    private static final Set<String> ACTIONS = Set.of("view", "operate", "update", "delete");

    private final ResAclMapper aclMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final ResGroupMapper groupMapper;
    private final SysOperLogService operLogService;

    /** 管理员（超管/运维管理员）不受 ACL 限制 */
    public boolean isAdmin(LoginUser user) {
        return user != null && (user.isSuperAdmin() || "OPS_ADMIN".equals(user.getRole()));
    }

    /** 单台设备 + 动作是否允许 */
    public boolean allowed(LoginUser user, Long oltId, String action) {
        if (user == null || isAdmin(user) || !ACTIONS.contains(action)) {
            return true;
        }
        return !denied(aclMapper.selectList(null), user, "OLT", String.valueOf(oltId), action);
    }

    /** 光猫（按 SN）+ 动作是否允许 */
    public boolean allowedOnu(LoginUser user, String sn, String action) {
        if (user == null || isAdmin(user) || !ACTIONS.contains(action)) {
            return true;
        }
        return GroupScopeContext.with(GroupScopeContext.ONU, () ->
                !denied(aclMapper.selectList(null), user, GroupService.MEMBER_TYPE_ONU, sn, action));
    }

    /** 返回该用户看不到的光猫 SN 集合 */
    public Set<String> hiddenOnuRefs(LoginUser user) {
        if (user == null || isAdmin(user)) {
            return null;
        }
        return GroupScopeContext.with(GroupScopeContext.ONU, () -> doHiddenRefs(user, GroupService.MEMBER_TYPE_ONU));
    }

    /** 通用可见性集合（须在对应表域上下文内调用） */
    private Set<String> doHiddenRefs(LoginUser user, String memberType) {
        List<ResAcl> all = aclMapper.selectList(null);
        if (all.isEmpty()) {
            return null;
        }
        Set<String> hidden = new HashSet<>();
        for (ResAcl acl : all) {
            if (SCOPE_DEVICE.equals(acl.getScopeType())) {
                if (denied(all, user, memberType, acl.getScopeId(), "view")) {
                    hidden.add(acl.getScopeId());
                }
            }
        }
        for (GroupMember member : groupMemberMapper.selectList(new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getMemberType, memberType))) {
            String ref = GroupService.MEMBER_TYPE_ONU.equals(memberType)
                    ? member.getMemberRef() : String.valueOf(member.getMemberId());
            if (ref != null && denied(all, user, memberType, ref, "view")) {
                hidden.add(ref);
            }
        }
        return hidden;
    }

    /** 返回该用户看不到的设备 id 集合 */
    public Set<Long> hiddenOltIds(LoginUser user) {
        if (user == null || isAdmin(user)) {
            return null;
        }
        List<ResAcl> all = aclMapper.selectList(null);
        if (all.isEmpty()) {
            return null;
        }
        Set<Long> hidden = new HashSet<>();
        for (ResAcl acl : all) {
            if (SCOPE_DEVICE.equals(acl.getScopeType())) {
                try {
                    long oltId = Long.parseLong(acl.getScopeId());
                    if (denied(all, user, "OLT", acl.getScopeId(), "view")) {
                        hidden.add(oltId);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        for (GroupMember member : groupMemberMapper.selectList(new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getMemberType, "OLT"))) {
            if (denied(all, user, "OLT", String.valueOf(member.getMemberId()), "view")) {
                hidden.add(member.getMemberId());
            }
        }
        return hidden;
    }

    /** ACL 判定核心：设备级 → 分组链自下而上 */
    private boolean denied(List<ResAcl> all, LoginUser user, String memberType, String ref, String action) {
        Long uid = user.getUserId();
        boolean covered = false;
        boolean granted = false;
        for (ResAcl acl : all) {
            if (SCOPE_DEVICE.equals(acl.getScopeType()) && acl.getScopeId().equals(ref)) {
                covered = true;
                if (uid != null && uid.equals(acl.getPrincipalId()) && hasAction(acl.getPerms(), action)) {
                    granted = true;
                }
            }
        }
        if (covered) {
            return !granted;
        }
        String nodeRef = groupRefOfRef(memberType, ref);
        while (nodeRef != null) {
            covered = false;
            granted = false;
            for (ResAcl acl : all) {
                if (SCOPE_GROUP.equals(acl.getScopeType()) && acl.getScopeId().equals(nodeRef)) {
                    covered = true;
                    if (uid != null && uid.equals(acl.getPrincipalId()) && hasAction(acl.getPerms(), action)) {
                        granted = true;
                    }
                }
            }
            if (covered) {
                return !granted;
            }
            nodeRef = parentRefOf(nodeRef);
        }
        return false;
    }

    /** 设备当前归属的分组节点引用 */
    public String groupRefOf(Long oltId) {
        GroupMember member = groupMemberMapper.selectOne(new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getMemberType, "OLT")
                .eq(GroupMember::getMemberId, oltId)
                .last("LIMIT 1"));
        return member == null ? ROOT_REF : member.getGroupRef();
    }

    /** 通用归属查询 */
    private String groupRefOfRef(String memberType, String ref) {
        LambdaQueryWrapper<GroupMember> wrapper = new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getMemberType, memberType);
        if (GroupService.MEMBER_TYPE_ONU.equals(memberType)) {
            wrapper.eq(GroupMember::getMemberRef, ref);
        } else {
            Long id;
            try {
                id = Long.parseLong(ref);
            } catch (NumberFormatException e) {
                return ROOT_REF;
            }
            wrapper.eq(GroupMember::getMemberId, id);
        }
        GroupMember member = groupMemberMapper.selectOne(wrapper.last("LIMIT 1"));
        return member == null ? ROOT_REF : member.getGroupRef();
    }

    /** 分组节点的父引用 */
    private String parentRefOf(String nodeRef) {
        if (nodeRef == null || !nodeRef.startsWith(GROUP_PREFIX)) {
            return null;
        }
        Long id;
        try {
            id = Long.parseLong(nodeRef.substring(GROUP_PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
        ResGroup group = groupMapper.selectById(id);
        if (group == null || group.getParentId() == null) {
            return null;
        }
        return GROUP_PREFIX + group.getParentId();
    }

    private boolean hasAction(String perms, String action) {
        if (perms == null || perms.isBlank()) {
            return false;
        }
        for (String p : perms.split(",")) {
            if (action.equals(p.trim())) {
                return true;
            }
        }
        return false;
    }

    /** 某范围的授权清单 */
    public List<ResAcl> listByScope(String scopeType, String scopeId, String memberType) {
        String scope = GroupScopeContext.normalize(memberType);
        return GroupScopeContext.with(scope, () -> aclMapper.selectList(new LambdaQueryWrapper<ResAcl>()
                .eq(ResAcl::getScopeType, scopeType)
                .eq(ResAcl::getScopeId, scopeId)
                .orderByAsc(ResAcl::getId)));
    }

    /** 授权 */
    public String grant(Long principalId, String scopeType, String scopeId, List<String> perms, String operator) {
        return grant(principalId, scopeType, scopeId, perms, operator, GroupScopeContext.OLT);
    }

    /** 授权（指定表域） */
    public String grant(Long principalId, String scopeType, String scopeId, List<String> perms,
                        String operator, String memberType) {
        String scope = GroupScopeContext.normalize(memberType);
        return GroupScopeContext.with(scope, () -> doGrant(principalId, scopeType, scopeId, perms, operator));
    }

    private String doGrant(Long principalId, String scopeType, String scopeId, List<String> perms, String operator) {
        if (principalId == null) {
            return "请选择用户";
        }
        if (!SCOPE_DEVICE.equals(scopeType) && !SCOPE_GROUP.equals(scopeType)) {
            return "范围类型不合法：" + scopeType;
        }
        if (scopeId == null || scopeId.isBlank()) {
            return "范围引用不能为空";
        }
        List<String> valid = new ArrayList<>();
        if (perms != null) {
            for (String p : perms) {
                if (p != null && ACTIONS.contains(p.trim()) && !valid.contains(p.trim())) {
                    valid.add(p.trim());
                }
            }
        }
        if (valid.isEmpty()) {
            return "至少授予一个动作";
        }
        ResAcl existing = aclMapper.selectOne(new LambdaQueryWrapper<ResAcl>()
                .eq(ResAcl::getPrincipalId, principalId)
                .eq(ResAcl::getScopeType, scopeType)
                .eq(ResAcl::getScopeId, scopeId)
                .last("LIMIT 1"));
        if (existing == null) {
            ResAcl acl = new ResAcl();
            acl.setPrincipalId(principalId);
            acl.setScopeType(scopeType);
            acl.setScopeId(scopeId);
            acl.setPerms(String.join(",", valid));
            acl.setCreatedBy(operator);
            acl.setCreatedAt(LocalDateTime.now());
            aclMapper.insert(acl);
        } else {
            existing.setPerms(String.join(",", valid));
            aclMapper.updateById(existing);
        }
        operLogService.log("设备权限", "授权", "用户 id=" + principalId + "，范围=" + scopeType + ":" + scopeId + "，动作=" + String.join("/", valid) + "，操作人=" + operator);
        Logs.info(Logs.Module.ACL, "授权", "用户", principalId, "范围", scopeType + ":" + scopeId, "动作", String.join("/", valid));
        return null;
    }

    /** 撤销授权 */
    public String revoke(Long id, String memberType) {
        String scope = GroupScopeContext.normalize(memberType);
        return GroupScopeContext.with(scope, () -> doRevoke(id));
    }

    private String doRevoke(Long id) {
        if (id == null || aclMapper.selectById(id) == null) {
            return "授权记录不存在";
        }
        ResAcl removed = aclMapper.selectById(id);
        aclMapper.deleteById(id);
        LoginUser op = UserContext.get();
        operLogService.log("设备权限", "撤销授权", "acl id=" + id + "，范围=" + (removed == null ? "-" : removed.getScopeType() + ":" + removed.getScopeId()) + "，操作人=" + (op == null ? "system" : op.getUsername()));
        Logs.info(Logs.Module.ACL, "撤销授权", "id", id);
        return null;
    }
}
