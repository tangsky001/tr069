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
