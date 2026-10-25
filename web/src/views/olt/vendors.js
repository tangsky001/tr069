/**
 * 厂商到详情视图的映射。
 *
 * OLT 各家命令不一样，页面要摆的东西也不一样：华为有板卡、温度、每口 ONT，
 * 鸿升光是整机一体化设备（没有板卡概念），识别不出来的走通用视图。
 * 所以按 vendor 分三个独立的视图组件，各管自己的标签页与说明文案。
 *
 * 台账里的 vendor 是后端 OltVendor 的枚举名（HUAWEI / ZTE / FIBERHOME / HSGQ / GENERIC）。
 *
 * 两种用法：
 * 1. 进详情页——用 {@link detailRoute} 拼路由，跳到 /olt/<厂商>/:id；
 * 2. 在台账页里用下拉切换——用 {@link componentOfVendor} 拿到视图组件，
 *    直接渲染在页面上，切设备当场换视图，不用跳来跳去。
 */
export const VENDOR_VIEWS = {
  HUAWEI: {
    path: 'huawei',
    name: 'OltHuawei',
    label: '华为',
    loader: () => import('@/views/olt/huawei')
  },
  HSGQ: {
    path: 'hsgq',
    name: 'OltHsgq',
    label: '鸿升光',
    loader: () => import('@/views/olt/hsgq')
  }
}

/** 没认出来、以及还没有专属视图的厂商（中兴、烽火）都走这个 */
export const GENERIC_VIEW = {
  path: 'generic',
  name: 'OltGeneric',
  label: '通用',
  loader: () => import('@/views/olt/generic')
}

/**
 * 按 vendor 取对应的视图定义，取不到就用通用视图。
 */
export function viewOfVendor(vendor) {
  if (!vendor) return GENERIC_VIEW
  return VENDOR_VIEWS[String(vendor).toUpperCase()] || GENERIC_VIEW
}

/**
 * 拼详情路由：{ name, params }，交给 $router.push。
 * 这样厂商怎么分时不用在页面里写一堆 if。
 */
export function detailRoute(vendor, id) {
  const view = viewOfVendor(vendor)
  return { name: view.name, params: { id: String(id) }}
}

/**
 * 按 vendor 取视图组件（异步组件）。
 *
 * 台账页下拉切换 OLT 时用它：切到华为就渲染华为面板，切到鸿升光就渲染整机面板，
 * 页面原地换，不用路由跳转。
 */
export function componentOfVendor(vendor) {
  return viewOfVendor(vendor).loader
}
