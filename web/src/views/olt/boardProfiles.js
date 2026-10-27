/**
 * 板卡面板档案。
 *
 * 板卡示意图不再用图片：面板由 components/OltBoardPanel.vue 用 SVG 现画
 * ——深色运维风格、金属灰底板、板卡丝印、端口横排、每个口一个圆形状态灯
 * （绿闪=在线、红常亮=告警、灰=离线），灯下面跟 ONU 在线数与接收光功率。
 * 这里只描述「这块板的面板长什么样 / 几个口 / 口是什么类型」，新板卡加一条即可。
 *
 * panel 取值：
 *   pon     —— PON 业务板（GPON / EPON，8 口横排）
 *   ge      —— 主控板、以太网上行板（RJ45 GE 口）
 *   xge     —— 10GE 上联板（SFP+ 光口）
 *   generic —— 未知型号，按端口数画通用面板
 *
 * ports / portMode 只在设备回显里拿不到端口信息时兜底：华为 display board 0
 * 只回板卡名与状态，不带端口数，靠这里补齐，避免面板空着。
 */
const PROFILES = [
  {
    code: 'H802SCUN',
    label: 'SCUN 主控板（4 个管理/上行 GE 口）',
    panel: 'ge',
    portMode: 'GE',
    // 现场确认 SCUN 板有 4 个 GE 口（P0 0/6/0 .. P3 0/6/3），不是没有端口
    ports: 4,
    controlBoard: true,
    keywords: ['SCUN']
  },
  {
    code: 'H801X2CS',
    label: 'X2CS 上联板（2 路 10GE 光口）',
    panel: 'xge',
    portMode: '10GE',
    ports: 2,
    controlBoard: false,
    keywords: ['X2CS']
  },
  {
    // 鸿升光是一体机，没有独立板卡型号：整机上来的「板卡」用设备型号 HSGQ-E04 表示
    code: 'HSGQ-E04',
    label: 'HSGQ-E04 EPON 整机（4 口 EPON）',
    panel: 'pon',
    portMode: 'EPON',
    ports: 4,
    controlBoard: false,
    keywords: ['HSGQ-E04']
  },
  {
    // XGBD 必须排在 GPBD 之前：'GPBD' 关键字同样能命中 H801XGBD，先用精确的 XGBD 匹配
    code: 'H801XGBD',
    label: 'XGBD 8 口 GPON 业务板（10GE 上联）',
    panel: 'pon',
    portMode: 'GPON',
    ports: 8,
    controlBoard: false,
    keywords: ['XGBD']
  },
  {
    code: 'H807GPBD',
    label: 'GPBD 8 口 GPON 业务板',
    panel: 'pon',
    portMode: 'GPON',
    ports: 8,
    controlBoard: false,
    keywords: ['GPBD']
  },
  {
    code: 'H808EPSD',
    label: 'EPSD 8 口 PON 业务板',
    panel: 'pon',
    portMode: 'EPON',
    ports: 8,
    controlBoard: false,
    keywords: ['EPSD']
  }
]

/** 按板卡型号匹配面板档案，匹配不到返回 null。 */
export function matchBoardProfile(boardType) {
  if (!boardType) return null
  const type = String(boardType).toUpperCase()
  return PROFILES.find(profile => profile.keywords.some(key => type.includes(key))) || null
}

/**
 * 没配档案的板卡：按端口类型推断面板样式，让主控板、上联板也有像样的面板。
 * 认不出来（端口类型为空）就退回 generic，按端口数画一排通用口。
 */
export function panelKindOf(portMode) {
  const mode = String(portMode || '').toUpperCase()
  if (!mode) return 'generic'
  if (mode.indexOf('GPON') >= 0 || mode.indexOf('EPON') >= 0 || mode.indexOf('PON') >= 0) {
    return 'pon'
  }
  if (mode.indexOf('XGE') >= 0 || mode.indexOf('10GE') >= 0 || mode.indexOf('SFP') >= 0) {
    return 'xge'
  }
  if (mode.indexOf('GE') >= 0 || mode.indexOf('FE') >= 0 || mode.indexOf('ETH') >= 0) {
    return 'ge'
  }
  return 'generic'
}

export default PROFILES
