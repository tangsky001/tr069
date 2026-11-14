const getters = {
  sidebar: state => state.app.sidebar,
  device: state => state.app.device,
  token: state => state.user.token,
  avatar: state => state.user.avatar,
  name: state => state.user.name,
  role: state => state.user.role,
  permissions: state => state.user.permissions,
  source: state => state.user.source,
  // 系统管理员什么都看得见，不必逐条勾权限
  isSuperAdmin: state => state.user.role === 'SUPER_ADMIN'
}
export default getters
