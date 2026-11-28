/** 厂商到详情视图的映射：仅华为独立界面，其它厂商走第三方界面 */
export const VENDOR_VIEWS = {
  HUAWEI: {
    path: 'huawei',
    name: 'OltHuawei',
    label: '华为',
    loader: () => import('@/views/olt/huawei')
  }
}

/** 第三方界面：鸿升光、中兴、烽火、以及没认出厂商的设备都走这个 */
export const GENERIC_VIEW = {
  path: 'generic',
  name: 'OltGeneric',
  label: '第三方',
  loader: () => import('@/views/olt/generic')
}

/** 按 vendor 取视图定义，取不到用通用视图 */
export function viewOfVendor(vendor) {
  if (!vendor) return GENERIC_VIEW
  return VENDOR_VIEWS[String(vendor).toUpperCase()] || GENERIC_VIEW
}

/** 拼详情路由（{ name, params }），交给 $router.push */
export function detailRoute(vendor, id) {
  const view = viewOfVendor(vendor)
  return { name: view.name, params: { id: String(id) }}
}

/** 按 vendor 取视图组件（异步组件），台账页切换 OLT 时原地渲染 */
export function componentOfVendor(vendor) {
  return viewOfVendor(vendor).loader
}
