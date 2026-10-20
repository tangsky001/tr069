/**
 * 判断一条 WLAN 实例工作在哪个频段。
 *
 * <p>最容易踩的坑是拿 Standard 下手：它报的是「这个实例用哪种调制制式」，
 * 不是频段。华为/电信固件把 2.4G 和 5G 的实例全报成 {@code ax}（ax 两个频段都支持），
 * 于是 /a|ac|ax/ 一律命中，7 个 SSID 全被塞进 5.8G，2.4G 那行永远是空的。</p>
 *
 * <p>可靠顺序：电信 RFBand 私有参数 → 标准频段参数 → 支持制式列表 → SSID 命名 → 实例号。</p>
 */
export function wifiBandOf(item) {
  const params = (item && item.params) || {}
  const path = (item && item.path) || ''

  // 1) X_CT-COM_RFBand：电信规范里 0 = 2.4G，1 = 5G。
  //    华为、中兴、烽火都报这个，是这几个里最准的一个
  const rfBand = params['X_CT-COM_RFBand']
  if (rfBand !== undefined && rfBand !== null && rfBand !== '') {
    const v = String(rfBand).trim()
    if (v === '0') return '2.4G'
    if (v === '1') return '5.8G'
  }

  // 2) 设备直接报的频段
  const band = String(params.OperatingFrequencyBand || params.FrequencyBand || '').toLowerCase()
  if (band.includes('5')) return '5.8G'
  if (band.includes('2.4') || band.includes('24g') || band.includes('2g')) return '2.4G'

  // 3) 支持制式列表：b,g,n,ax 是 2.4G 射频，a,n,ac,ax 是 5G 射频。
  //    只在报成列表时可信——单值 ax 两个频段都支持，判不出来
  const std = String(params.X_HW_SupportedStandards || params.Standard || '').toLowerCase()
  if (std.includes(',')) {
    const parts = std.split(/[,|\s]+/).filter(Boolean)
    const has24 = parts.some(p => p === 'b' || p === 'g' || p === '11b' || p === '11g')
    const has5 = parts.some(p => p === 'a' || p === 'ac' || p === 'ax' || p === '11a' || p === '11ac')
    if (has24 && !has5) return '2.4G'
    if (has5 && !has24) return '5.8G'
  }

  // 4) SSID 名带 5G 后缀，运营商基本都这么命名
  const ssid = String(params.SSID || '')
  if (/-?5g(\b|$)/i.test(ssid)) return '5.8G'

  // 5) 兜底按实例号：电信/移动光猫约定前 4 个 SSID 是 2.4G，第 5 个起是 5G
  const m = path.match(/(?:WLANConfiguration|SSID)\.(\d+)\./)
  if (m && Number(m[1]) >= 5) return '5.8G'
  return '2.4G'
}
