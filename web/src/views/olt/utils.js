/**
 * OLT 页面共用的小工具。
 *
 * mixin 与各个展示组件都会用到（组件是「纯展示 + 冒泡事件」，不引 mixin），
 * 放在这里避免同一段折算逻辑抄两份。
 */

/**
 * 板卡运行灯：三态由后端按状态等级换算好下发（board.lampState），页面直接显示。
 *
 * <p>原来这里还按 statusType 兜底推一次灯色，等于把「什么状态算异常」的规则
 * 复制到页面；现在后端没给就是没给，按灭灯处理，暴露问题而不是猜。</p>
 */
export function boardLampState(lampState) {
  return lampState || 'offline'
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
  // 三态由后端在 detail 组装时判定下发（光模块异常 / 光路断 → alarm）。
  // 前端不再按 onuTotal / link 现算：后端没给 lampState 就是后端问题，
  // 端口显示灰灯（offline）暴露出来；占位补出来的空端口本就没有业务数据，也是灰灯。
  return port.lampState || 'offline'
}
