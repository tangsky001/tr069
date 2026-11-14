import request from '@/utils/request'

// 权限点清单，按模块分组
export function fetchPermissions() {
  return request({ url: '/system/permissions', method: 'get' })
}

// 角色列表，带权限与每个角色下的人数
export function fetchRoles() {
  return request({ url: '/system/roles', method: 'get' })
}

export function createRole(data) {
  return request({ url: '/system/roles', method: 'post', data })
}

// 整份覆盖：传进去的就是这个角色全部的权限
export function saveRolePermissions(code, permissions) {
  return request({
    url: `/system/roles/${encodeURIComponent(code)}/permissions`,
    method: 'put',
    data: { permissions }
  })
}

export function removeRole(code) {
  return request({ url: `/system/roles/${encodeURIComponent(code)}`, method: 'delete' })
}

// 用户列表：每行带 canDelete / canToggleStatus / canChangeRole 与登录来源、时间文案
export function fetchUsers() {
  return request({ url: '/system/users', method: 'get' })
}

// 账号与密码策略：最小长度、登录名规则、输入提示都由后端给，页面不写死
export function fetchPasswordPolicy() {
  return request({ url: '/system/password-policy', method: 'get' })
}

export function createUser(data) {
  return request({ url: '/system/users', method: 'post', data })
}

export function updateUser(id, data) {
  return request({ url: `/system/users/${id}`, method: 'put', data })
}

export function removeUser(id) {
  return request({ url: `/system/users/${id}`, method: 'delete' })
}

// 登录日志，失败的也在里面
export function fetchLoginLogs(params) {
  return request({ url: '/system/login-logs', method: 'get', params })
}
