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
    label: 'SCUN 主控板（4 个管理/上行 GE 口）',
    image: '/olt/olt_H802SCUN.png',
    portMode: 'GE',
    // 现场确认 SCUN 板有 4 个 GE 口（P0 0/6/0 .. P3 0/6/3），不是没有端口
    ports: 4,
    controlBoard: true,
    keywords: ['SCUN']
  },
  {
    code: 'H801X2CS',
    label: 'X2CS 上联板（2 路 10GE 光口）',
    image: '/olt/olt_H801X2CS.png',
    portMode: '10GE',
    ports: 2,
    controlBoard: false,
    keywords: ['X2CS']
  },
  {
    // 鸿升光是一体机，没有独立板卡型号：整机上来的「板卡」用设备型号 HSGQ-E04 表示，
    // 示意图文件名就是设备型号（public/olt/HSGQ-E04.png），所以必须在这里显式配 image
    code: 'HSGQ-E04',
    label: 'HSGQ-E04 EPON 整机（4 口 EPON）',
    image: '/olt/HSGQ-E04.png',
    // 整机面板照是横向的（770x125），跟竖条板卡图不是一个摆法，横着按原尺寸铺开
    wide: true,
    portMode: 'EPON',
    ports: 4,
    controlBoard: false,
    keywords: ['HSGQ-E04']
  },
  {
    // XGBD 必须排在 GPBD 之前：'GPBD' 关键字同样能命中 H801XGBD，先用精确的 XGBD 匹配
    code: 'H801XGBD',
    label: 'XGBD 8 口 GPON 业务板（10GE 上联）',
    image: '/olt/olt_H801XGBD.png',
    portMode: 'GPON',
    ports: 8,
    controlBoard: false,
    keywords: ['XGBD']
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
