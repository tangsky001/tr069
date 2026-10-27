/**
 * OLT 页面共用的小工具。
 *
 * mixin 与各个展示组件都会用到（组件是「纯展示 + 冒泡事件」，不引 mixin），
 * 放在这里避免同一段折算逻辑抄两份。
 */

/**
 * VLAN 列表压成连续区间：1,41,45,46 变成 1、41、45-46。
 *
 * 网管上就是这么写的，一行能塞下几十个 VLAN 时尤其有用。
 */
/**
 * 板卡运行灯：Normal 绿、异常红、没采到灰（面板表头和机框里的板卡状态灯共用）。
 */
export function boardLampState(statusType) {
  if (statusType === 'success') return 'online'
  if (statusType === 'danger' || statusType === 'warning') return 'alarm'
  return 'offline'
}

/**
 * 端口指示灯的三态（板卡面板和机框视图共用同一套判定，别各写一份）：
 *
 * - alarm   红常亮：光模块异常（lamp=warn），或者注册过 ONT 但一个都不在线——光路断了
 * - online  绿闪：有在线 ONT，或者上行口链路在线
 * - offline 灰：没采到 ONT 也没采到链路
 */
export function portLampState(port) {
  if (!port) return 'offline'
  if (port.lamp === 'warn') return 'alarm'
  if ((port.onuTotal || 0) > 0 && (port.onuOnline || 0) === 0) return 'alarm'
  if ((port.onuOnline || 0) > 0 || port.link === 'online') return 'online'
  return 'offline'
}

export function compressVlans(vlans) {
  const nums = (vlans || []).slice().sort((a, b) => a - b)
  const parts = []
  let start = null
  let prev = null
  nums.forEach(num => {
    if (start === null) {
      start = num
      prev = num
      return
    }
    if (num === prev + 1) {
      prev = num
      return
    }
    parts.push(start === prev ? String(start) : start + '-' + prev)
    start = num
    prev = num
  })
  if (start !== null) {
    parts.push(start === prev ? String(start) : start + '-' + prev)
  }
  return parts.join('、')
}
