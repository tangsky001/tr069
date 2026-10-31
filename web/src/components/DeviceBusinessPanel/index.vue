<template>
  <div class="biz-panel">
    <div class="biz-toolbar">
      <span class="biz-meta">最近采集：{{ formatTime(summary.lastCollectTime) }}</span>
      <!-- 这个按钮是唯一会主动叫醒设备的入口：其余区块（WAN / WLAN / SIP / LAN）
           点开都是读本地快照，不下发任何 RPC -->
      <el-button
        type="primary"
        size="mini"
        icon="el-icon-refresh"
        title="向设备发起一轮读取（会唤醒设备）"
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
          <span class="wan-name">WAN - {{ item.title }}</span>
          <span :class="item.up ? 'status-ok' : 'status-bad'">
            <i :class="item.up ? 'el-icon-success' : 'el-icon-error'" />
            {{ item.uptimeText }}
          </span>
        </div>
        <div class="wan-body">
          <div class="wan-row">
            <span class="k">连接模式/地址类型:</span>
            <span class="v">
              {{ item.connMode }}
              <el-tag v-if="item.ipProtocol" size="mini" :type="ipTagType(item.ipProtocol)">
                {{ item.ipProtocol }}
              </el-tag>
              <el-tag v-if="item.natEnabled" size="mini">NAT</el-tag>
              <el-tag v-if="item.mtu" size="mini">MTU/{{ item.mtu }}</el-tag>
            </span>
          </div>
          <div class="wan-row">
            <span class="k">业务模式:</span>
            <span class="v v-strong">{{ item.service }}</span>
          </div>
          <div class="wan-row">
            <span class="k">端口绑定:</span>
            <span class="v">{{ item.bindPorts || '未绑定' }}</span>
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
              <span class="wifi-ssid">{{ show(item.ssid) }}</span>
              <i v-if="item.encrypted" class="el-icon-lock" />
              <span class="wifi-clients">{{ show(item.params.TotalAssociations, '0') }} 终端</span>
              <span :class="item.up ? 'status-ok' : 'status-bad'">{{ item.statusText }}</span>
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
              <span class="wifi-ssid">{{ show(item.ssid) }}</span>
              <i v-if="item.encrypted" class="el-icon-lock" />
              <span class="wifi-clients">{{ show(item.params.TotalAssociations, '0') }} 终端</span>
              <span :class="item.up ? 'status-ok' : 'status-bad'">{{ item.statusText }}</span>
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
          <i :class="item.registered ? 'el-icon-phone status-ok' : 'el-icon-phone-outline status-bad'" />
          <span class="wifi-ssid">{{ item.number }}</span>
          <span :class="item.registered ? 'status-ok' : 'status-bad'">{{ item.statusText }}</span>
          <span class="wifi-clients">{{ item.server }}</span>
        </div>
      </div>
    </template>
  </div>
</template>

<script>
import { fetchDeviceBusiness, collectDeviceBusiness, pingDiagnostics } from '@/api/device'

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
    // 频段由后端判定：RFBand → 频段参数 → 制式列表 → SSID 名 → 实例号，前端只按结果分行
    wifi24Items() {
      return this.wifiItems.filter(item => item.band === '2.4G')
    },
    wifi58Items() {
      return this.wifiItems.filter(item => item.band === '5.8G')
    },
    lanItems() { return this.summary.lan || [] },
    sipItems() { return this.summary.sip || [] },
    devTitle() {
      // OUI 前 6 位 + SN 由后端拼好，前端不再自己切片
      return this.summary.title || this.sn
    },
    deviceRows() {
      // 按参考图顺序：运行时长 / 注册时间 / 最近上报 / 上报 IP
      return [
        { label: '运行时长', value: this.summary.uptimeText || '-', strong: false },
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
    // 地址协议徽标配色：单栈 IPv4 绿色，IPv6-only 橙色，双栈灰色
    ipTagType(protocol) {
      if (protocol === 'IPv6') return 'warning'
      if (protocol === 'IPv4+IPv6') return 'info'
      return 'success'
    },
    display(value) {
      return value === undefined || value === null || value === '' ? '-' : value
    },
    show(value, fallback) {
      const v = this.display(value)
      return v === '-' ? (fallback || '-') : v
    },
    // 诊断 = 下发 IPPing 诊断对象，设备执行后把结果写回诊断参数
    async doPing(item) {
      const host = (item._host || '').trim()
      if (!host) {
        this.$message.warning('请输入要诊断的 IP 或域名')
        return
      }
      this.pingingPath = item.path
      try {
        // 诊断对象的挂载位置在 TR-098 与 TR-181 下完全不同，由后端按设备数据模型选；
        // 页面只提交目标地址，不再自己拼 CWMP 报文
        await pingDiagnostics(this.sn, host)
        this.$message.success(
          '诊断指令已下发，设备将对 ' + host + ' 发起 4 次 ping；设备执行完成后可在下次上报里读到结果')
      } catch (e) {
        this.$message.error('诊断下发失败：' + (e.message || ''))
      } finally {
        this.pingingPath = ''
      }
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
