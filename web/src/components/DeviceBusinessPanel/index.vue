<template>
  <div class="biz-panel">
    <div class="biz-toolbar">
      <span class="biz-meta">最近采集：{{ formatTime(summary.lastCollectTime) }}</span>
      <el-button
        type="primary"
        size="mini"
        icon="el-icon-refresh"
        :loading="collecting"
        @click="collect"
      >重新采集</el-button>
    </div>

    <el-alert
      v-if="message"
      :title="message"
      :type="messageType"
      show-icon
      :closable="false"
      class="biz-alert"
    />

    <!-- ============ 设备信息卡（ITMS 悬浮详情样式） ============ -->
    <div class="dev-card">
      <div class="dev-card-title">{{ devTitle }} 详情</div>
      <div class="dev-card-body">
        <div v-for="row in deviceRows" :key="row.label" class="dev-row">
          <span class="k">{{ row.label }}:</span>
          <span class="v" :class="{ 'v-strong': row.strong }">{{ row.value }}</span>
        </div>
      </div>
    </div>

    <!-- ============ WAN 连接卡片 ============ -->
    <div class="section-title">WAN 连接（{{ wanItems.length }}）</div>
    <template v-if="wanItems.length">
      <el-card v-for="item in wanItems" :key="item.path" shadow="never" class="wan-card">
        <div slot="header" class="wan-head">
          <span class="wan-name">WAN - {{ wanTitle(item) }}</span>
          <span :class="isUp(item) ? 'status-ok' : 'status-bad'">
            <i :class="isUp(item) ? 'el-icon-success' : 'el-icon-error'" />
            {{ uptime(item.params.Uptime) }}
          </span>
        </div>
        <div class="wan-body">
          <div class="wan-row">
            <span class="k">连接模式/地址类型:</span>
            <span class="v">
              {{ connMode(item.params) }}
              <el-tag v-if="item.params.ExternalIPAddress" size="mini" type="success">IPv4</el-tag>
              <el-tag v-if="isNat(item.params)" size="mini">NAT</el-tag>
              <el-tag v-if="item.params.ExternalMTU" size="mini">MTU/{{ item.params.ExternalMTU }}</el-tag>
            </span>
          </div>
          <div class="wan-row">
            <span class="k">业务模式:</span>
            <span class="v v-strong">{{ serviceMode(item) }}</span>
          </div>
          <div class="wan-row">
            <span class="k">MAC地址:</span>
            <span class="v">{{ show(item.params.MACAddress) }}</span>
          </div>
          <div class="wan-row">
            <span class="k">VLAN ID:</span>
            <span class="v v-strong">{{ show(item.params.VLANIDMark) }}</span>
          </div>
          <div class="wan-row">
            <span class="k">IP地址/子网掩码:</span>
            <span class="v">
              <span class="v-ip">{{ show(item.params.ExternalIPAddress) }}</span>
              / {{ show(item.params.SubnetMask) }}
            </span>
          </div>
          <div class="wan-row">
            <span class="k">网关:</span>
            <span class="v">{{ show(item.params.DefaultGateway) }}</span>
          </div>
          <div v-if="item.params.UserName" class="wan-row">
            <span class="k">拨号账号:</span>
            <span class="v">{{ show(item.params.UserName) }}</span>
          </div>
          <div class="wan-row diag-row">
            <span class="k">输入IP或域名:</span>
            <el-input
              v-model="item._host"
              size="mini"
              placeholder="www.baidu.com"
              style="width:180px"
              @keyup.enter.native="doPing(item)"
            />
            <el-button
              type="success"
              size="mini"
              :loading="pingingPath === item.path"
              @click="doPing(item)"
            >诊断</el-button>
          </div>
        </div>
        <el-collapse class="more-collapse">
          <el-collapse-item title="全部参数">
            <div v-for="(value, name) in item.params" :key="name" class="all-param-row">
              <span class="k">{{ name }}</span>
              <span class="v">{{ display(value) }}</span>
            </div>
          </el-collapse-item>
        </el-collapse>
      </el-card>
    </template>
    <el-empty
      v-else
      :description="collecting ? '采集中，设备回应后自动显示' : '暂无 WAN 数据，点击右上角重新采集'"
      :image-size="60"
    />

    <!-- ============ LAN 网口卡片 ============ -->
    <div class="section-title">LAN 网口（{{ lanItems.length }}）</div>
    <div v-if="lanItems.length" class="lan-bar">
      <div
        v-for="item in lanItems"
        :key="item.path"
        class="lan-chip"
        :class="{ 'lan-up': item.connected }"
        :title="'MAC: ' + (item.mac || '-')"
      >
        <svg class="lan-icon" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
          <rect x="3" y="8" width="18" height="11" rx="1.5" fill="none" stroke="currentColor" stroke-width="1.8" />
          <line x1="7" y1="8" x2="7" y2="4" stroke="currentColor" stroke-width="1.5" />
          <line x1="12" y1="8" x2="12" y2="4" stroke="currentColor" stroke-width="1.5" />
          <line x1="17" y1="8" x2="17" y2="4" stroke="currentColor" stroke-width="1.5" />
        </svg>
        <div class="lan-label">LAN{{ item.instance }}</div>
        <div class="lan-status">{{ item.connected ? '已连接' : '未连接' }}</div>
      </div>
    </div>
    <el-empty
      v-else
      :description="collecting ? '采集中，设备回应后自动显示' : '未采集到网口信息，点击右上角重新采集'"
      :image-size="60"
    />

    <!-- ============ WLAN 卡片：按频段分成 2.4G / 5.8G 上下两行 ============ -->
    <template v-if="wifiItems.length">
      <div class="section-title">WLAN（{{ wifiItems.length }}）</div>
      <div class="wifi-band-row">
        <span class="wifi-band-label">2.4G:</span>
        <div class="wifi-bar">
          <template v-if="wifi24Items.length">
            <div v-for="item in wifi24Items" :key="item.path" class="wifi-chip">
              <span class="wifi-ssid">{{ show(item.params.SSID) }}</span>
              <i v-if="wifiEncrypted(item.params)" class="el-icon-lock" />
              <span class="wifi-clients">{{ show(item.params.TotalAssociations, '0') }} 终端</span>
              <span :class="isUp(item) ? 'status-ok' : 'status-bad'">{{ wanStatus(item.params.Status) }}</span>
            </div>
          </template>
          <span v-else class="wifi-band-empty">-</span>
        </div>
      </div>
      <div class="wifi-band-row">
        <span class="wifi-band-label">5.8G:</span>
        <div class="wifi-bar">
          <template v-if="wifi58Items.length">
            <div v-for="item in wifi58Items" :key="item.path" class="wifi-chip">
              <span class="wifi-ssid">{{ show(item.params.SSID) }}</span>
              <i v-if="wifiEncrypted(item.params)" class="el-icon-lock" />
              <span class="wifi-clients">{{ show(item.params.TotalAssociations, '0') }} 终端</span>
              <span :class="isUp(item) ? 'status-ok' : 'status-bad'">{{ wanStatus(item.params.Status) }}</span>
            </div>
          </template>
          <span v-else class="wifi-band-empty">-</span>
        </div>
      </div>
    </template>

    <!-- ============ SIP 卡片 ============ -->
    <template v-if="sipItems.length">
      <div class="section-title">SIP 语音（{{ sipItems.length }}）</div>
      <div class="wifi-bar">
        <div v-for="item in sipItems" :key="item.path" class="wifi-chip">
          <i :class="sipRegistered(item.params) ? 'el-icon-phone status-ok' : 'el-icon-phone-outline status-bad'" />
          <span class="wifi-ssid">{{ sipDisplay(item.params) }}</span>
          <span :class="sipRegistered(item.params) ? 'status-ok' : 'status-bad'">
            {{ show(item.params.SIPRegisterStatus || item.params.Status, '未知') }}
          </span>
          <span class="wifi-clients">{{ sipServer(item.params) }}</span>
        </div>
      </div>
    </template>
  </div>
</template>

<script>
import { fetchDeviceBusiness, collectDeviceBusiness, dispatchRpc } from '@/api/device'
import { wifiBandOf } from '@/utils/wifi'

// 后端每 5 分钟自动刷新一次 LAN 网口状态，这里按 1 分钟回读业务快照：
// 页面开着就能看到网口插拔变化，不必手动点「重新采集」
const AUTO_REFRESH_MS = 60000

export default {
  name: 'DeviceBusinessPanel',
  props: {
    sn: { type: String, required: true }
  },
  data() {
    return {
      loading: false,
      collecting: false,
      summary: {},
      pingingPath: '',
      refreshTimer: null
    }
  },
  computed: {
    wanItems() { return this.summary.wan || [] },
    // 详情页保留设备全部 WiFi 能力：启用的、未启用的都列出来，不做过滤
    wifiItems() { return this.summary.wifi || [] },
    wifi24Items() {
      return this.wifiItems.filter(item => wifiBandOf(item) === '2.4G')
    },
    wifi58Items() {
      return this.wifiItems.filter(item => wifiBandOf(item) === '5.8G')
    },
    lanItems() { return this.summary.lan || [] },
    sipItems() { return this.summary.sip || [] },
    devTitle() {
      const info = this.summary.device || []
      // 组合第一条 WAN 设备里的 OUI 前缀，拿不到就用纯 SN
      const oui = this.paramValue(info, 'ManufacturerOUI') || this.paramValue(info, 'Manufacturer')
      return oui ? String(oui).slice(0, 6) + '-' + this.sn : this.sn
    },
    deviceRows() {
      const info = this.summary.device || []
      const upTime = this.paramValue(info, 'UpTime')
      // 按参考图顺序：运行时长 / 注册时间 / 最近上报 / 上报 IP
      return [
        { label: '运行时长', value: upTime ? this.uptime(upTime) : '-', strong: false },
        { label: '注册时间', value: this.formatTs(this.summary.registerTime), strong: false },
        { label: '最近上报', value: this.formatTs(this.summary.lastContactTime), strong: false },
        { label: '上报IP', value: this.summary.ip || '-', strong: true }
      ]
    },
    message() {
      if (this.summary.collectStatus === 'empty') {
        return this.summary.message || '能力树为空，请先在设备详情页做能力树采集'
      }
      if (this.summary.collectStatus === 'timeout') {
        return '设备未在时限内全部回应，以下是已刷新到的部分结果'
      }
      if (this.summary.collectStatus === 'fail') {
        return '设备采集失败，展示的是库里最近一次的快照'
      }
      return ''
    },
    messageType() {
      const map = { empty: 'warning', timeout: 'warning', fail: 'error' }
      return map[this.summary.collectStatus] || 'info'
    }
  },
  created() {
    this.load()
    // 页面不可见时不回读，省掉无意义请求；切回来下一轮自动补上
    this.refreshTimer = setInterval(() => {
      if (!document.hidden) {
        this.reload()
      }
    }, AUTO_REFRESH_MS)
  },
  beforeDestroy() {
    if (this.refreshTimer) {
      clearInterval(this.refreshTimer)
      this.refreshTimer = null
    }
  },
  methods: {
    paramValue(list, name) {
      const item = (list || []).find(x => x.params && x.params[name] !== undefined)
      return item ? item.params[name] : undefined
    },
    display(value) {
      return value === undefined || value === null || value === '' ? '-' : value
    },
    show(value, fallback) {
      const v = this.display(value)
      return v === '-' ? (fallback || '-') : v
    },
    isUp(item) {
      const s = String(item.params.ConnectionStatus || item.params.Status || '').toLowerCase()
      return s === 'connected' || s === 'up'
    },
    wanTitle(item) {
      // 设备报了 Name（如 1_TR069_R_VID_46）就用它，否则用实例序号
      if (item.params.Name) return item.params.Name
      const m = item.path.match(/(WANPPPConnection|WANIPConnection)\.(\d+)\.$/)
      return m ? m[2] : item.path
    },
    connMode(params) {
      const type = String(params.ConnectionType || '')
      const mode = type.toLowerCase().includes('bridged') ? '桥接' : '路由'
      const addr = params.AddressingType || (type.toLowerCase().includes('ppp') ? 'PPPoE' : '-')
      return mode + ' / ' + addr
    },
    isNat(params) {
      // CWMP 布尔回来是 "1"/"0" 字符串，只认 === true 会把开着 NAT 的连接显示成关闭
      return this.boolOf(params.NATEnabled, false)
    },
    /** 识别 CWMP 的布尔写法："1"/"0"、true/false、Up/Down、Enabled/Disabled 都算。 */
    boolOf(value, fallback = true) {
      if (value === undefined || value === null || value === '') return fallback
      const s = String(value).trim().toLowerCase()
      if (['0', 'false', 'no', 'off', 'disabled', 'down'].includes(s)) return false
      if (['1', 'true', 'yes', 'on', 'enabled', 'up'].includes(s)) return true
      return fallback
    },
    // 业务模式：优先设备报的 ServiceList，没有就从连接名里猜（TR069/VOIP/INTERNET）
    serviceMode(item) {
      const params = item.params
      const declared = params.ServiceList || params['X_CT-COM_ServiceList']
      if (declared) return declared
      const name = String(params.Name || item.path).toUpperCase()
      if (name.includes('TR069')) return 'TR069'
      if (name.includes('VOIP') || name.includes('VOICE')) return 'VOIP'
      if (name.includes('IPTV') || name.includes('OTHER')) return 'IPTV'
      if (name.includes('INTERNET')) return 'INTERNET'
      return '-'
    },
    uptime(seconds) {
      const n = parseInt(seconds, 10)
      if (!n || n < 0) return ''
      const d = Math.floor(n / 86400)
      const h = Math.floor((n % 86400) / 3600)
      const m = Math.floor((n % 3600) / 60)
      const s = n % 60
      return (d ? d + '天' : '') + h + '小时' + m + '分' + s + '秒'
    },
    // 诊断 = 下发 IPPing 诊断对象，设备执行后把结果写回诊断参数
    async doPing(item) {
      const host = (item._host || '').trim()
      if (!host) {
        this.$message.warning('请输入要诊断的 IP 或域名')
        return
      }
      const base = String(this.summary.rootNode || 'InternetGatewayDevice.').startsWith('Device.')
        ? 'Device.IP.Diagnostics.IPPing.'
        : 'InternetGatewayDevice.IPPingDiagnostics.'
      this.pingingPath = item.path
      try {
        await dispatchRpc(this.sn, 'SetParameterValues', {
          ParameterKey: 'acs-ui-ping',
          ParameterList: [
            { Name: base + 'Host', Value: host, Value_type: 'string' },
            { Name: base + 'NumberOfRepetitions', Value: '4', Value_type: 'unsignedInt' },
            { Name: base + 'DiagnosticsState', Value: 'Requested', Value_type: 'string' }
          ]
        })
        this.$message.success(
          '诊断指令已下发，设备将对 ' + host + ' 发起 4 次 ping；设备执行完成后可在下次上报里读到结果')
      } catch (e) {
        this.$message.error('诊断下发失败：' + (e.message || ''))
      } finally {
        this.pingingPath = ''
      }
    },
    wifiEncrypted(params) {
      const enc = String(params.BasicEncryptionModes || params.BeaconType || '').toLowerCase()
      return !(enc.includes('none') || enc.includes('open'))
    },
    wanStatus(status) {
      const s = String(status || '').toLowerCase()
      if (s === 'up' || s === 'enabled') return '正常'
      if (s === 'disabled') return '已禁用'
      return status || '未知'
    },
    sipRegistered(params) {
      const s = String(params.SIPRegisterStatus || params.Status || '').toLowerCase()
      return s === 'registered' || s === 'ok' || s === '成功'
    },
    sipServer(params) {
      const host = params.RegistrarServer || params.ProxyServer
      if (!host) return '-'
      const port = params.RegistrarServerPort || params.ProxyServerPort
      return host + (port ? ':' + port : '')
    },
    /**
     * SIP 线路展示：优先用认证名/用户名/号码；没有就从 URI 里截 @ 前面的号码。
     * 设备上有时只有 AuthUserName 和 URI，没有 UserName，不能直接拿 URI 当号码，
     * 否则号码/用户名会显示成 "2026@192.168.xxx" 这种带域名的东西。
     */
    sipDisplay(params) {
      const name = params.AuthUserName || params.UserName || params.DirectoryNumber || params.PhoneNumber
      if (name) return name
      const uri = params.URI || ''
      const m = uri.match(/^([^@]+)@/)
      return m ? m[1] : (uri || '未配置')
    },
    async load() {
      this.loading = true
      try {
        const res = await fetchDeviceBusiness(this.sn)
        this.summary = res.data || {}
      } finally {
        this.loading = false
      }
    },
    // 定时回读：不置 loading（避免整页闪一下），失败也不弹提示，下一轮继续
    async reload() {
      try {
        const res = await fetchDeviceBusiness(this.sn)
        this.summary = res.data || {}
      } catch (e) {
        // 静默失败：定时刷新不该打扰正在看页面的人
      }
    },
    async collect() {
      this.collecting = true
      try {
        const res = await collectDeviceBusiness(this.sn)
        this.summary = res.data || {}
        if (this.summary.collectStatus === 'success') {
          this.$message.success('业务参数已刷新')
        }
        this.$emit('collected', this.summary)
      } catch (e) {
        this.$message.error('采集失败：' + (e.message || ''))
      } finally {
        this.collecting = false
      }
    },
    formatTime(value) {
      if (!value) return '从未采集'
      return String(value).replace('T', ' ')
    },
    formatTs(value) {
      if (!value) return '未记录'
      return String(value).replace('T', ' ')
    }
  }
}
</script>

<style scoped>
.biz-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.biz-meta {
  color: #909399;
  font-size: 12px;
}
.biz-alert {
  margin-bottom: 8px;
}
/* ---- 设备信息卡 ---- */
.dev-card {
  border: 1px solid #d7e7d0;
  background: #f4faf1;
  border-radius: 4px;
  margin-bottom: 12px;
}
.dev-card-title {
  font-weight: 600;
  color: #303133;
  padding: 8px 14px;
  border-bottom: 1px solid #e3efd9;
}
.dev-card-body {
  display: flex;
  flex-wrap: wrap;
  padding: 8px 14px;
}
.dev-row {
  width: 50%;
  padding: 3px 0;
  font-size: 13px;
}
.dev-row .k {
  color: #67a04e;
  margin-right: 8px;
}
.dev-row .v {
  color: #303133;
  word-break: break-all;
}
.v-strong {
  font-weight: 600;
}
/* ---- WAN 卡片 ---- */
.section-title {
  font-weight: 600;
  color: #303133;
  margin: 14px 0 8px;
}
.wan-card {
  margin-bottom: 10px;
}
.wan-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.wan-name {
  font-weight: 600;
  color: #2f7d32;
}
.status-ok {
  color: #67c23a;
  font-size: 12px;
}
.status-bad {
  color: #f56c6c;
  font-size: 12px;
}
.wan-row {
  display: flex;
  padding: 5px 0;
  border-bottom: 1px solid #f2f6fc;
  font-size: 13px;
  align-items: center;
}
.wan-row .k {
  width: 150px;
  flex-shrink: 0;
  color: #67a04e;
}
.wan-row .v {
  color: #303133;
}
.v-ip {
  color: #2f7d32;
  font-weight: 600;
}
.diag-row {
  border-bottom: none;
  margin-top: 6px;
}
.more-collapse {
  border-top: 1px solid #ebeef5;
  margin-top: 6px;
}
.all-param-row {
  display: flex;
  padding: 2px 0;
  font-size: 12px;
  border-bottom: 1px dashed #ebeef5;
}
.all-param-row .k {
  width: 260px;
  color: #909399;
  flex-shrink: 0;
}
.all-param-row .v {
  color: #303133;
  word-break: break-all;
}
/* ---- WLAN / SIP 状态条 ---- */
.wifi-band-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 6px;
}
.wifi-band-label {
  width: 46px;
  flex-shrink: 0;
  color: #2f7d32;
  font-size: 13px;
  font-weight: 600;
  line-height: 32px;
}
.wifi-band-empty {
  color: #909399;
  font-size: 13px;
  line-height: 32px;
}
.wifi-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.wifi-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 1px solid #d7e7d0;
  background: #f4faf1;
  border-radius: 4px;
  padding: 6px 12px;
  font-size: 13px;
  color: #303133;
}
.wifi-band {
  font-weight: 600;
  color: #2f7d32;
}
.wifi-ssid {
  font-weight: 600;
}
.wifi-clients {
  color: #909399;
  font-size: 12px;
}
/* ---- LAN 网口 ---- */
.lan-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
.lan-chip {
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  width: 90px;
  padding: 10px 0;
  border: 1px solid #dcdfe6;
  border-radius: 6px;
  background: #f5f7fa;
  color: #909399;
  transition: all .2s;
}
.lan-chip.lan-up {
  border-color: #67c23a;
  background: #f0f9eb;
  color: #67c23a;
}
.lan-icon {
  width: 28px;
  height: 28px;
  margin-bottom: 4px;
}
.lan-label {
  font-size: 13px;
  font-weight: 600;
}
.lan-status {
  font-size: 11px;
  margin-top: 2px;
}
</style>
