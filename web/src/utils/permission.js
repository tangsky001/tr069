import store from '@/store'

/**
 * 有没有这个权限点。
 *
 * <p>系统管理员恒为真：它的权限是代码里给的，不在清单里，
 * 逐条勾出来的清单追不上它。</p>
 *
 * <p>这里只管按钮露不露出来。真正挡人的是后端，按 URL 判的——
 * 就算这里的判定被绕过去，点了也是 403。</p>
 */
export function hasPerm(code) {
  if (!code) {
    return true
  }
  if (store.getters.isSuperAdmin) {
    return true
  }
  return (store.getters.permissions || []).indexOf(code) >= 0
}

/** 能看这个模块（页面）。 */
export function canView(module) {
  return hasPerm(module + ':view')
}

/** 能改这个模块（写操作按钮）。 */
export function canManage(module) {
  return hasPerm(module + ':manage')
}
