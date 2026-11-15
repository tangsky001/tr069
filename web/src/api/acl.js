import request from '@/utils/request'

/**
 * 设备/分组级 ACL API：
 * 给指定用户授予某分组/某台设备的 查看/操作/编辑/删除 权限；
 * 后端在 /olt/{id}** 与 OltService 内统一拦截，前端只负责配置与展示。
 * memberType=OLT/ONU 是表域：OLT 与光猫权限各存各表（olt_res_acl / onu_res_acl），默认 OLT。
 */

// 可授权的用户清单（下拉用）
export function fetchAclUsers() {
  return request({ url: '/acl/users', method: 'get' })
}

// 某范围（scopeType: device/group，scopeId: 设备 id 或 ROOT/G:id）的授权清单
export function fetchAclList(scopeType, scopeId, memberType) {
  return request({ url: '/acl/list', method: 'get', params: { scopeType, scopeId, memberType }})
}

// 授权（同用户+同范围覆盖更新）；memberType 走 query 参数（后端 @RequestParam）
export function grantAcl(data) {
  return request({
    url: '/acl/grant',
    method: 'post',
    params: { memberType: data.memberType },
    data
  })
}

// 撤销授权（按 ACL 记录 id）
export function revokeAcl(id, memberType) {
  return request({ url: '/acl/revoke', method: 'post', params: { memberType }, data: { id }})
}
