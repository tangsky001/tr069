/** OLT 页面共用的小工具 */

/** 板卡运行灯：三态由后端换算下发，页面直接显示；后端没给就按灭灯处理 */
export function boardLampState(lampState) {
  return lampState || 'offline'
}

/** 端口指示灯三态（板卡面板与机框视图共用）：alarm 红常亮 / online 绿闪 / offline 灰 */
export function portLampState(port) {
  if (!port) return 'offline'
  return port.lampState || 'offline'
}
