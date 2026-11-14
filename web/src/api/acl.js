import request from '@/utils/request'

/**
 * 设备/分组级 ACL API：
 * 给指定用户授予某分组/某台设备的 查看/操作/编辑/删除 权限；
 * 后端在 /olt/{id}** 与 OltService 内统一拦截，前端只负责配置与展示。
 */

// 可授权的用户清单（下拉用）
export function fetchAclUsers() {
  return request({ url: '/acl/users', method: 'get' })
}

// 某范围（scopeType: device/group，scopeId: 设备 id 或 ROOT/G:id）的授权清单
export function fetchAclList(scopeType, scopeId) {
  return request({ url: '/acl/list', method: 'get', params: { scopeType, scopeId }})
}

// 授权（同用户+同范围覆盖更新）
export function grantAcl(data) {
  return request({ url: '/acl/grant', method: 'post', data })
}

// 撤销授权（按 ACL 记录 id）
export function revokeAcl(id) {
  return request({ url: '/acl/revoke', method: 'post', data: { id }})
}
