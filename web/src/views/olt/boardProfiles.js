/**
 * 板卡示意图档案。
 *
 * 图片放在 public/olt/ 下，构建后按 /olt/xxx.png 访问（public 内容原样拷到 dist 根）。
 * 命名约定：olt_<板卡型号大写>.png，例如 olt_H807GPBD.png；
 * 页面会先查下面这张表，查不到就按约定路径去找，找不到（404）显示「暂无该型号示意图」。
 * 也就是说，新板卡只要把图按名字丢进 public/olt/，不用改代码。
 *
 * ports / portMode 只在设备回显里拿不到端口信息时兜底：华为 display board 0
 * 只回板卡名与状态，不带端口数，靠这里补齐，避免面板空着。
 */
const PROFILES = [
  {
    code: 'H802SCUN',
    label: 'SCUN 主控板',
    image: '/olt/olt_H802SCUN.png',
    portMode: 'GE',
    ports: 0,
    controlBoard: true,
    keywords: ['SCUN']
  },
  {
    code: 'H807GPBD',
    label: 'GPBD 8 口 GPON 业务板',
    image: '/olt/olt_H807GPBD.png',
    portMode: 'GPON',
    ports: 8,
    controlBoard: false,
    keywords: ['GPBD']
  }
]

/**
 * 按板卡型号匹配示意图，匹配不到返回 null。
 *
 * 板卡名各家写法不同（H807GPBD / GPBD），所以用关键字包含匹配。
 */
export function matchBoardProfile(boardType) {
  if (!boardType) return null
  const type = String(boardType).toUpperCase()
  return PROFILES.find(profile => profile.keywords.some(key => type.includes(key))) || null
}

export default PROFILES
