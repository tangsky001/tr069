/**
 * 设备 PON 类型 / 光模块功率的判定。
 *
 * <p>独立出来是因为列表页、详情弹窗都可能要展示同一套结论，避免各处各写一份规则。</p>
 */

/**
 * PON 类型：优先看设备型号里写没写（GPON_4+1 / EPON...），快照里有 PON 参数也算，
 * 都拿不到就按 GE（以太网上联）设备兜底。
 *
 * @param {Object} row 设备行（需 deviceType / productClass）
 * @param {Object} biz 该设备的业务快照（summary 结构）
 * @returns {string} GPON / EPON / PON / GE
 */
export function ponTypeOf(row, biz) {
  const text = [row && row.deviceType, row && row.productClass].join(' ')
  if (/epon/i.test(text)) return 'EPON'
  if (/gpon/i.test(text)) return 'GPON'
  const pon = (biz && biz.pon) || {}
  const keys = Object.keys(pon).join(' ')
  if (/epon/i.test(keys)) return 'EPON'
  if (/gpon/i.test(keys)) return 'GPON'
  if (keys) return 'PON'
  return 'GE'
}

/**
 * 光模块状态：快照里存在光功率参数即视为有数据。
 *
 * @returns {string} ok / none
 */
export function opticStateOf(biz) {
  const pon = (biz && biz.pon) || {}
  return Object.keys(pon).some(k => /power/i.test(k)) ? 'ok' : 'none'
}

/**
 * 光功率文案：{收} / {发} dBm。没有功率参数返回空串，由调用方决定显不显示。
 */
export function opticTextOf(biz) {
  const pon = (biz && biz.pon) || {}
  let rx = null
  let tx = null
  for (const [k, v] of Object.entries(pon)) {
    if (!/power/i.test(k)) continue
    const val = powerDbm(v)
    if (val === null) continue
    if (rx === null && /rx|receive/i.test(k)) rx = val
    else if (tx === null && /tx|transmit/i.test(k)) tx = val
  }
  if (rx === null && tx === null) return ''
  if (rx === null) rx = tx
  if (tx === null) tx = rx
  return rx + ' / ' + tx + ' dBm'
}

/**
 * 厂商多以 0.1µW 上报光功率（如 457 ≈ -13.4dBm），这里换算成 dBm；
 * 设备直接报 dBm 或已是小数值的按 dBm 原样用。
 *
 * @returns {string|null} 一位小数字符串，无法解析返回 null
 */
export function powerDbm(value) {
  if (value === undefined || value === null || value === '') return null
  const s = String(value).trim()
  if (/dbm/i.test(s)) {
    const d = parseFloat(s)
    return isNaN(d) ? null : d.toFixed(1)
  }
  const n = parseFloat(s)
  if (isNaN(n)) return null
  const mw = Math.abs(n) > 100 ? n * 0.0001 : n
  if (mw <= 0) return null
  return (10 * Math.log10(mw)).toFixed(1)
}
