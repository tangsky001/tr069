<template>
  <div class="app-container">
    <el-card shadow="never" class="filter-card">
      <el-form :inline="true" :model="query" size="small">
        <el-form-item label="序列号">
          <el-input v-model="query.sn" placeholder="支持模糊匹配" clearable style="width:180px" />
        </el-form-item>
        <el-form-item label="运营商">
          <el-select v-model="query.isp" clearable placeholder="全部" style="width:140px">
            <el-option label="电信 CT" value="CT" />
            <el-option label="联通 CU" value="CU" />
            <el-option label="移动 CMCC" value="CMCC" />
            <el-option label="通用 standard" value="standard" />
          </el-select>
        </el-form-item>
        <el-form-item label="设备类型">
          <el-input v-model="query.deviceType" placeholder="如 ADSL_4+1" clearable style="width:160px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="el-icon-search" @click="handleSearch">查询</el-button>
          <el-button icon="el-icon-refresh" @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
      <div class="online-bar">
        当前在线会话：<b>{{ onlineCount }}</b> 台
        <el-button type="text" icon="el-icon-refresh" @click="loadOnline">刷新</el-button>
        <el-button type="text" @click="onlineVisible = true">查看明细</el-button>
      </div>
    </el-card>

    <el-table v-loading="loading" :data="displayList" border fit highlight-current-row style="width:100%">
      <!-- 设备标识：只放 SN 与在线状态，其余字段归「终端信息」列 -->
      <el-table-column label="设备标识" min-width="190">
        <template slot-scope="{ row }">
          <div class="cell-main">
            <el-link type="primary" @click="openDetail(row)">{{ row.sn }}</el-link>
            <el-tag :type="isOnline(row) ? 'success' : 'info'" size="mini">
              {{ isOnline(row) ? '在线' : '离线' }}
            </el-tag>
          </div>
          <div class="cell-sub">{{ row.oui || '-' }} · {{ row.isp || '-' }} {{ row.ispVersion || '' }}</div>
        </template>
      </el-table-column>

      <!-- 终端信息：厂家 / 型号 / 硬件固件版本 / PON 类型 + 光功率，独立组件承载 -->
      <el-table-column label="终端信息" min-width="200">
        <device-identity-cell
          slot="header"
          mode="header"
          :sort-key="sortKey"
          :sort-order="sortOrder"
          :optic-filter="opticFilter"
          @sort="sortBy"
          @update:opticFilter="v => opticFilter = v"
        />
        <template slot-scope="{ row }">
          <device-identity-cell :row="row" :biz="bizOf(row.sn)" />
        </template>
      </el-table-column>

      <el-table-column label="业务状态" align="center">
        <el-table-column label="WAN 连接" min-width="130">
          <template slot-scope="{ row }">
            <el-link
              v-if="wanList(row.sn).length"
              type="primary"
              @click="openWanParams(row.sn)"
            >{{ wanList(row.sn).length }}WAN连接参数</el-link>
            <span v-else class="cell-empty">-</span>
          </template>
        </el-table-column>
        <el-table-column label="WIFI 信息" min-width="140">
          <template slot-scope="{ row }">
            <template v-if="wifiList(row.sn).length">
              <el-tag
                v-for="item in wifiList(row.sn)"
                :key="item.path"
                size="mini"
                class="wifi-tag"
              >{{ item.params.SSID || '-' }}</el-tag>
            </template>
            <span v-else class="cell-empty">-</span>
          </template>
        </el-table-column>
        <el-table-column label="SIP 信息" min-width="150">
          <template slot-scope="{ row }">
            <template v-if="sipList(row.sn).length">
              <div v-for="item in sipList(row.sn)" :key="item.path" class="biz-row">
                <el-tag size="mini" :type="sipRegistered(item.params) ? 'success' : 'danger'">
                  {{ sipStatusText(item.params) }}
                </el-tag>
                <span class="biz-name">{{ item.params.UserName || '-' }}</span>
              </div>
            </template>
            <span v-else class="cell-empty">-</span>
          </template>
        </el-table-column>
        <el-table-column label="设备状态" width="80" align="center">
          <template slot-scope="{ row }">
            <el-tag :type="isOnline(row) ? 'success' : 'danger'" size="mini">
              {{ isOnline(row) ? '在线' : '离线' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="当前位置IP" min-width="120">
          <template slot-scope="{ row }">
            <span>{{ wanIp(row.sn) }}</span>
          </template>
        </el-table-column>
      </el-table-column>

      <el-table-column label="注册信息" align="center">
        <el-table-column label="注册时间" width="150">
          <template slot-scope="{ row }">
            <span>{{ formatTime(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="最近上报" width="150">
          <template slot-scope="{ row }">
            <span>{{ formatTime(row.lastContactTime) }}</span>
          </template>
        </el-table-column>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template slot-scope="{ row }">
          <el-button type="text" size="mini" @click="openBiz(row.sn)">下发业务</el-button>
          <el-dropdown trigger="click" size="mini" @command="cmd => handleDeviceCommand(cmd, row)">
            <el-button type="text" size="mini">
              业务/操作<i class="el-icon-arrow-down el-icon--right" />
            </el-button>
            <el-dropdown-menu slot="dropdown">
              <el-dropdown-item command="business" icon="el-icon-connection">业务信息</el-dropdown-item>
              <el-dropdown-item command="probe" icon="el-icon-search">诊断(GetRPCMethods)</el-dropdown-item>
              <el-dropdown-item command="reboot" icon="el-icon-refresh-right" divided>重启设备</el-dropdown-item>
              <el-dropdown-item command="factoryReset" icon="el-icon-delete">恢复出厂</el-dropdown-item>
            </el-dropdown-menu>
          </el-dropdown>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="pager"
      :current-page.sync="query.page"
      :page-size.sync="query.size"
      :page-sizes="[10, 20, 50, 100]"
      :total="total"
      layout="total, sizes, prev, pager, next, jumper"
      @size-change="handleSearch"
      @current-change="load"
    />

    <el-dialog title="在线会话明细" :visible.sync="onlineVisible" width="900px">
      <el-table :data="onlineList" border size="small" max-height="460">
        <el-table-column label="序列号" prop="sn" min-width="180" show-overflow-tooltip />
        <el-table-column label="OUI" prop="oui" width="90" />
        <el-table-column label="状态" prop="state" width="110" />
        <el-table-column label="软件版本" prop="softwareVersion" width="130" show-overflow-tooltip />
        <el-table-column label="待发消息" prop="pendingCount" width="90" />
        <el-table-column label="最近上报" min-width="170">
          <template slot-scope="{ row }">
            <span>{{ formatTime(row.lastContactTime) }}</span>
          </template>
        </el-table-column>
      </el-table>
      <span slot="footer">
        <el-button @click="onlineVisible = false">关闭</el-button>
      </span>
    </el-dialog>

    <el-dialog
      :title="'设备业务信息 - ' + businessSn"
      :visible.sync="businessVisible"
      width="960px"
    >
      <device-business-panel v-if="businessVisible" :sn="businessSn" />
    </el-dialog>

    <el-dialog title="一键下发" :visible.sync="bizVisible" width="620px">
      <el-form label-width="110px" size="small">
        <el-form-item label="设备序列号">
          <span class="sn-text">{{ bizForm.sn }}</span>
        </el-form-item>
        <el-form-item label="模板" required>
          <el-select
            v-model="bizForm.templateSel"
            filterable
            placeholder="业务模板整包下发，配置模板单条下发"
            style="width:100%"
          >
            <el-option-group label="== 业务模板 ==">
              <el-option
                v-for="t in bizTemplates"
                :key="'biz' + t.template.id"
                :label="t.template.name"
                :value="'biz:' + t.template.id"
              >
                <span>{{ t.template.name }}</span>
                <span class="member-hint">{{ (t.items || []).length }} 个配置模板</span>
              </el-option>
            </el-option-group>
            <el-option-group label="== 配置模板 ==">
              <el-option
                v-for="t in cfgTemplates"
                :key="'cfg' + t.key"
                :label="t.name"
                :value="'cfg:' + t.key"
              >
                <span>{{ t.name }}</span>
                <span class="member-hint">{{ t.group || t.deviceType || '' }}</span>
              </el-option>
            </el-option-group>
          </el-select>
        </el-form-item>
      </el-form>

      <div v-if="bizProgress.id" class="biz-progress">
        <div>
          状态：<el-tag size="mini" :type="bizStatusType(bizProgress.status)">{{ bizStatusText(bizProgress.status) }}</el-tag>
          进度：{{ bizProgress.finishedCount || 0 }} / {{ bizProgress.totalCount || 0 }}
        </div>
        <el-table :data="bizItems" border size="small" max-height="240" style="margin-top:8px">
          <el-table-column label="配置模板" prop="templateKey" min-width="260" show-overflow-tooltip />
          <el-table-column label="结果" width="90">
            <template slot-scope="{ row }">
              <el-tag size="mini" :type="row.success ? 'success' : 'danger'">{{ row.success ? '成功' : '失败' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="说明" prop="error" min-width="150" show-overflow-tooltip />
        </el-table>
      </div>

      <span slot="footer">
        <el-button @click="closeBiz">关闭</el-button>
        <el-button type="primary" :loading="bizExecuting" @click="doBizExecute">一键下发</el-button>
      </span>
    </el-dialog>

    <el-dialog
      :title="'WAN 连接参数 - ' + wanDialogSn"
      :visible.sync="wanVisible"
      width="760px"
      top="5vh"
    >
      <div v-if="!wanList(wanDialogSn).length" class="cell-empty">该设备暂无 WAN 连接数据，请先在业务信息里重新采集</div>
      <el-card
        v-for="item in wanList(wanDialogSn)"
        :key="item.path"
        shadow="never"
        class="wan-param-card"
      >
        <div slot="header" class="wan-param-head">
          <span class="wan-param-name">WAN - {{ item.params.Name || wanShort(item.path) }}</span>
          <el-tag size="mini" :type="wanTagType(item.params.ConnectionStatus)">
            {{ wanText(item.params.ConnectionStatus) }}
          </el-tag>
          <span v-if="uptimeText(item.params.Uptime)" class="wan-uptime">{{ uptimeText(item.params.Uptime) }}</span>
        </div>
        <div class="wan-param-row">
          <span class="k">连接模式/地址类型:</span>
          <span class="v">{{ connMode(item.params) }}</span>
        </div>
        <div class="wan-param-row">
          <span class="k">业务模式:</span>
          <span class="v v-strong">{{ serviceMode(item) }}</span>
        </div>
        <div class="wan-param-row">
          <span class="k">MAC地址:</span>
          <span class="v">{{ showVal(item.params.MACAddress) }}</span>
        </div>
        <div class="wan-param-row">
          <span class="k">VLAN ID:</span>
          <span class="v v-strong">{{ showVal(item.params.VLANIDMark) }}</span>
        </div>
        <div class="wan-param-row">
          <span class="k">IP地址/子网掩码:</span>
          <span class="v">
            <span class="v-ip">{{ showVal(item.params.ExternalIPAddress) }}</span>
            / {{ showVal(item.params.SubnetMask) }}
          </span>
        </div>
        <div class="wan-param-row">
          <span class="k">网关:</span>
          <span class="v">{{ showVal(item.params.DefaultGateway) }}</span>
        </div>
        <div v-if="item.params.UserName" class="wan-param-row">
          <span class="k">拨号账号:</span>
          <span class="v">{{ item.params.UserName }}</span>
        </div>
        <el-collapse class="wan-more">
          <el-collapse-item title="全部参数">
            <div v-for="(value, name) in item.params" :key="name" class="wan-all-row">
              <span class="k">{{ name }}</span>
              <span class="v">{{ showVal(value) }}</span>
            </div>
          </el-collapse-item>
        </el-collapse>
      </el-card>
      <span slot="footer">
        <el-button @click="wanVisible = false">关闭</el-button>
      </span>
    </el-dialog>

    <el-dialog
      :title="detailTitle"
      :visible.sync="detailVisible"
      width="620px"
      top="6vh"
    >
      <div class="detail-list">
        <div v-for="row in detailRows(detailRow)" :key="row.label" class="detail-row">
          <span class="k">{{ row.label }}:</span>
          <span class="v" :class="{ 'v-strong': row.strong }">{{ row.value }}</span>
        </div>
      </div>
      <span slot="footer">
        <el-button type="primary" @click="goDetail(detailRow && detailRow.sn)">完整详情</el-button>
        <el-button @click="detailVisible = false">关闭</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import { fetchDevicePage, fetchOnlineDevices, dispatchRpc, fetchDeviceBusinessBatch } from '@/api/device'
import { fetchTemplates, executeWorklist } from '@/api/worklist'
import { fetchRpcResult } from '@/api/rpc'
import { fetchBizTemplates, executeBizTemplate, fetchBizExecution } from '@/api/bizTemplate'
import DeviceBusinessPanel from '@/components/DeviceBusinessPanel'
import DeviceIdentityCell from '@/components/DeviceIdentityCell.vue'
import { ponTypeOf, opticStateOf } from '@/utils/deviceOptic'

export default {
  name: 'DeviceList',
  components: { DeviceBusinessPanel, DeviceIdentityCell },
  data() {
    return {
      loading: false,
      list: [],
      total: 0,
      bizMap: {},
      wanVisible: false,
      wanDialogSn: '',
      detailVisible: false,
      detailRow: null,
      sortKey: '',
      sortOrder: '',
      opticFilter: '',
      onlineCount: 0,
      onlineList: [],
      onlineVisible: false,
      businessVisible: false,
      businessSn: '',
      bizVisible: false,
      bizExecuting: false,
      bizTemplates: [],
      cfgTemplates: [],
      bizForm: { sn: '', templateSel: null },
      bizProgress: {},
      bizTimer: null,
      query: {
        page: 1,
        size: 20,
        sn: '',
        isp: '',
        deviceType: ''
      }
    }
  },
  computed: {
    detailTitle() {
      const row = this.detailRow
      if (!row) return '设备详情'
      return (row.oui ? row.oui + '-' : '') + row.sn + ' 详情'
    },
    // 光模块筛选 + 表头字段排序，只作用于当前页数据
    displayList() {
      let rows = this.list
      if (this.opticFilter) {
        rows = rows.filter(r => opticStateOf(this.bizOf(r.sn)) === this.opticFilter)
      }
      if (this.sortKey) {
        const key = this.sortKey
        const dir = this.sortOrder === 'asc' ? 1 : -1
        rows = [...rows].sort((a, b) =>
          this.sortVal(a, key).localeCompare(this.sortVal(b, key), 'zh') * dir)
      }
      return rows
    },
    bizItems() {
      const raw = this.bizProgress.result
      if (!raw) return []
      try {
        return JSON.parse(raw)
      } catch (e) {
        return []
      }
    }
  },
  created() {
    this.load()
    this.loadOnline()
  },
  beforeDestroy() {
    this.stopBizPolling()
  },
  methods: {
    async load() {
      this.loading = true
      try {
        const res = await fetchDevicePage(this.query)
        const data = res.data || {}
        this.list = data.items || []
        this.total = data.total || 0
        this.loadBiz()
      } catch (e) {
        this.list = []
        this.total = 0
      } finally {
        this.loading = false
      }
    },
    // 整页设备的业务概要一次拉回，列表直接铺开 WAN/WIFI/IP，不再逐行请求
    async loadBiz() {
      const sns = this.list.map(d => d.sn)
      if (!sns.length) {
        this.bizMap = {}
        return
      }
      try {
        const res = await fetchDeviceBusinessBatch(sns)
        this.bizMap = res.data || {}
      } catch (e) {
        this.bizMap = {}
      }
    },
    bizOf(sn) {
      return this.bizMap[sn] || {}
    },
    wanList(sn) {
      return this.bizOf(sn).wan || []
    },
    wifiList(sn) {
      return this.bizOf(sn).wifi || []
    },
    sipList(sn) {
      return this.bizOf(sn).sip || []
    },
    sipRegistered(params) {
      const s = String(params.SIPRegisterStatus || '').toLowerCase()
      return s === 'registered' || s === 'ok' || s === 'up' || s === '成功'
    },
    sipStatusText(params) {
      const raw = params.SIPRegisterStatus
      if (!raw) return '未配置'
      return this.sipRegistered(params) ? '已注册' : '未注册'
    },
    // WAN 实例的公网地址，取第一个非空的
    wanIp(sn) {
      for (const item of this.wanList(sn)) {
        const ip = item.params.ExternalIPAddress
        if (ip && ip !== '0.0.0.0') return ip
      }
      return '-'
    },
    isOnline(row) {
      const status = String(row.status || '').toLowerCase()
      return status.includes('online') || status.includes('在线') || row.online === true
    },
    // ...WANPPPConnection.1. -> PPP 1，设备没上报 Name 时的兜底叫法
    wanShort(path) {
      const matched = path.match(/WAN(PPP|IP)Connection\.(\d+)\.$/)
      return matched ? 'WAN ' + matched[1] + ' ' + matched[2] : 'WAN'
    },
    wanTagType(status) {
      const s = String(status || '').toLowerCase()
      if (s === 'connected' || s === 'up') return 'success'
      if (s === 'disconnected' || s === 'down') return 'danger'
      return 'info'
    },
    wanText(status) {
      const s = String(status || '').toLowerCase()
      if (s === 'connected' || s === 'up') return '已连接'
      if (s === 'disconnected' || s === 'down') return '未连接'
      return status || '-'
    },

    // ---------------- WAN 连接参数弹窗 ----------------
    openWanParams(sn) {
      this.wanDialogSn = sn
      this.wanVisible = true
    },

    // ---------------- 设备详情弹窗（设备标识列点 SN 打开） ----------------
    openDetail(row) {
      this.detailRow = row
      this.detailVisible = true
    },
    detailRows(row) {
      if (!row) return []
      const biz = this.bizOf(row.sn)
      const mac = this.paramValue(biz.device || [], 'MACAddress')
      const rows = [
        { label: 'OUI', value: row.oui },
        { label: '设备 SN', value: row.sn },
        { label: 'MAC 地址', value: mac },
        { label: '运营商', value: (row.isp || '') + (row.ispVersion ? ' ' + row.ispVersion : '') },
        { label: '设备能力', value: this.capabilityText(biz) },
        { label: '硬件版本', value: row.hardwareVersion },
        { label: '固件版本', value: row.softwareVersion },
        { label: '设备型号', value: row.productClass || row.deviceType },
        { label: 'LAN IP', value: this.wanIp(row.sn) },
        { label: '远程管理地址', value: row.connectionRequestUrl },
        { label: 'CPE USER', value: row.cpeToAcsName },
        { label: 'CWMP 版本', value: row.cwmpVersion },
        { label: 'WiFi SSID', value: (biz.wifi || []).map(w => w.params.SSID).filter(Boolean).join(' / ') },
        { label: '注册时间', value: this.formatTime(row.createdAt) },
        { label: '最近上报', value: this.formatTime(row.lastContactTime) }
      ]
      return rows.filter(r => r.value && r.value !== '-' && r.value !== '从未采集')
    },
    // 从业务快照拼设备能力：WiFi 频段 + SIP 电话
    capabilityText(biz) {
      const parts = []
      const wifi = biz.wifi || []
      if (wifi.some(w => this.wifiBand(w.params) === '2.4G')) parts.push('2.4G WiFi')
      if (wifi.some(w => this.wifiBand(w.params) === '5.8G')) parts.push('5.8G WiFi')
      if ((biz.sip || []).length) parts.push('1电话')
      return parts.join('、') || null
    },
    paramValue(list, name) {
      const item = (list || []).find(x => x.params && x.params[name] !== undefined)
      return item ? item.params[name] : undefined
    },
    connMode(params) {
      const type = String(params.ConnectionType || '')
      const mode = type.toLowerCase().includes('bridged') ? '桥接' : '路由'
      const addr = params.AddressingType || (type.toLowerCase().includes('ppp') ? 'PPPoE' : '-')
      return mode + ' / ' + addr
    },
    serviceMode(item) {
      const params = item.params
      const declared = params.ServiceList || params['X_CT-COM_ServiceList']
      if (declared) return declared
      const name = String(params.Name || item.path).toUpperCase()
      if (name.includes('TR069')) return 'TR069'
      if (name.includes('VOIP') || name.includes('VOICE')) return 'VOIP'
      if (name.includes('IPTV')) return 'IPTV'
      if (name.includes('INTERNET')) return 'INTERNET'
      return '-'
    },
    uptimeText(seconds) {
      const n = parseInt(seconds, 10)
      if (!n || n < 0) return ''
      const d = Math.floor(n / 86400)
      const h = Math.floor((n % 86400) / 3600)
      const m = Math.floor((n % 3600) / 60)
      return (d ? d + '天' : '') + h + '小时' + m + '分'
    },
    showVal(value) {
      return value === undefined || value === null || value === '' ? '-' : value
    },
    wifiBand(params) {
      const std = String(params.Standard || '').toLowerCase()
      return /a|ac|ax/.test(std) ? '5.8G' : '2.4G'
    },

    sortVal(row, key) {
      if (key === 'pon') return ponTypeOf(row, this.bizOf(row.sn))
      return String(row[key] || '')
    },
    // 表头字段排序，由终端信息列组件抛出
    sortBy(key) {
      if (this.sortKey !== key) {
        this.sortKey = key
        this.sortOrder = 'asc'
      } else if (this.sortOrder === 'asc') {
        this.sortOrder = 'desc'
      } else {
        this.sortKey = ''
        this.sortOrder = ''
      }
    },
    async loadOnline() {
      try {
        const res = await fetchOnlineDevices()
        this.onlineList = res.data || []
        this.onlineCount = this.onlineList.length
      } catch (e) {
        this.onlineList = []
        this.onlineCount = 0
      }
    },
    handleSearch() {
      this.query.page = 1
      this.load()
    },
    resetQuery() {
      this.query = { page: 1, size: 20, sn: '', isp: '', deviceType: '' }
      this.load()
    },
    // 完整详情：跳原来的设备详情页（能力树采集、RPC、参数树在那里）
    goDetail(sn) {
      if (!sn) return
      this.detailVisible = false
      this.$router.push({ name: 'DeviceDetail', params: { sn }})
    },

    // ---------------- 业务信息与快捷操作 ----------------
    handleDeviceCommand(cmd, row) {
      if (cmd === 'business') {
        this.openBusiness(row.sn)
        return
      }
      if (cmd === 'probe') {
        this.quickRpc(row.sn, 'GetRPCMethods', {}, false)
        return
      }
      if (cmd === 'reboot') {
        this.$confirm('确认重启设备 ' + row.sn + '？', '重启设备', { type: 'warning' })
          .then(() => this.quickRpc(row.sn, 'Reboot', {}, true))
          .catch(() => {})
        return
      }
      if (cmd === 'factoryReset') {
        this.$confirm('恢复出厂会清空设备全部配置，确认继续？', '恢复出厂', { type: 'warning' })
          .then(() => this.quickRpc(row.sn, 'FactoryReset', {}, true))
          .catch(() => {})
      }
    },
    openBusiness(sn) {
      this.businessSn = sn
      this.businessVisible = true
    },
    /**
     * 下发一条简单 RPC 并轮询到出结果为止。
     * 列表页不做复杂展示，成功/失败提示即可，任务号留给需要追查的场景。
     */
    async quickRpc(sn, rpcName, args, withTaskId) {
      try {
        const res = await dispatchRpc(sn, rpcName, args)
        const data = res.data || {}
        if (!data.queued) {
          this.$message.error('指令未受理（设备离线或队列已满）')
          return
        }
        this.$message.success('指令已受理' + (withTaskId ? '，任务号 ' + data.taskId : ''))
        this.pollQuickRpc(data.taskId)
      } catch (e) {
        this.$message.error('下发失败：' + (e.message || ''))
      }
    },
    pollQuickRpc(taskId) {
      let count = 0
      const timer = setInterval(async() => {
        count += 1
        try {
          const res = await fetchRpcResult(taskId)
          const data = res.data
          if (data && data.status === 'success') {
            clearInterval(timer)
            this.$message.success('执行成功（任务号 ' + taskId + '）')
          } else if (data && data.status === 'fail') {
            clearInterval(timer)
            this.$message.warning('执行失败：' + (data.faultString || data.faultCode || ''))
          }
        } catch (e) { /* 单次失败不打断 */ }
        if (count >= 60) {
          clearInterval(timer)
        }
      }, 3000)
    },

    // ---------------- 一键下发（业务模板整包 / 配置模板单条） ----------------
    async openBiz(sn) {
      this.bizForm = { sn, templateSel: null }
      this.bizProgress = {}
      this.bizVisible = true
      if (this.bizTemplates.length === 0) {
        try {
          const res = await fetchBizTemplates()
          this.bizTemplates = res.data || []
        } catch (e) {
          this.bizTemplates = []
        }
      }
      if (this.cfgTemplates.length === 0) {
        try {
          const res = await fetchTemplates()
          this.cfgTemplates = res.data || []
        } catch (e) {
          this.cfgTemplates = []
        }
      }
    },
    async doBizExecute() {
      const sel = this.bizForm.templateSel
      if (!sel) {
        this.$message.warning('请选择模板')
        return
      }
      this.bizExecuting = true
      try {
        if (sel.startsWith('biz:')) {
          const res = await executeBizTemplate({
            templateId: Number(sel.slice(4)),
            sn: this.bizForm.sn
          })
          this.$message.success('业务模板已提交下发')
          this.startBizPolling(res.data.executionId)
        } else {
          // 配置模板走工单通道：提交即返回工单号，进度在工作单页看
          const key = sel.slice(4)
          const res = await executeWorklist({
            sn: this.bizForm.sn,
            templateKey: key,
            username: ''
          })
          const wlId = res.data && res.data.worklistId
          this.$message.success('工单已提交' + (wlId != null ? '（工单号 ' + wlId + '）' : ''))
          this.bizProgress = {
            status: 'running',
            templateName: key,
            sn: this.bizForm.sn,
            totalCount: 1,
            finishedCount: 0
          }
        }
      } catch (e) {
        this.$message.error((e && e.message) || '下发失败')
      } finally {
        this.bizExecuting = false
      }
    },
    startBizPolling(id) {
      this.stopBizPolling()
      const tick = async() => {
        try {
          const res = await fetchBizExecution(id)
          this.bizProgress = res.data || {}
          if (this.bizProgress.status !== 'running') {
            this.stopBizPolling()
          }
        } catch (e) {
          this.stopBizPolling()
        }
      }
      tick()
      this.bizTimer = setInterval(tick, 3000)
    },
    stopBizPolling() {
      if (this.bizTimer) {
        clearInterval(this.bizTimer)
        this.bizTimer = null
      }
    },
    closeBiz() {
      this.stopBizPolling()
      this.bizVisible = false
    },
    bizStatusText(status) {
      const map = { running: '执行中', success: '全部成功', partial: '部分成功', fail: '失败' }
      return map[status] || status || '-'
    },
    bizStatusType(status) {
      const map = { running: '', success: 'success', partial: 'warning', fail: 'danger' }
      return map[status] || 'info'
    },
    formatTime(value) {
      if (!value) return '-'
      return String(value).replace('T', ' ')
    }
  }
}
</script>

<style scoped>
.filter-card {
  margin-bottom: 16px;
}
.online-bar {
  color: #606266;
  font-size: 13px;
}
.pager {
  margin-top: 16px;
  text-align: right;
}
.sn-text {
  color: #303133;
  font-weight: 600;
}
.member-hint {
  float: right;
  color: #8492a6;
  font-size: 12px;
}
.biz-progress {
  border-top: 1px solid #ebeef5;
  padding-top: 10px;
  font-size: 13px;
  color: #606266;
}
/* 设备标识列：主行 + 两行补充信息 */
.cell-main {
  font-weight: 600;
  color: #303133;
}
.cell-sub {
  color: #909399;
  font-size: 12px;
  margin-top: 2px;
}
.cell-empty {
  color: #c0c4cc;
}
.biz-row {
  display: flex;
  align-items: center;
  margin-bottom: 3px;
}
.biz-name {
  margin-left: 6px;
  font-size: 12px;
  color: #606266;
}
.wifi-tag {
  margin: 0 4px 4px 0;
}
/* ---- WAN 连接参数弹窗 ---- */
.wan-param-card {
  margin-bottom: 10px;
}
.wan-param-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.wan-param-name {
  font-weight: 600;
  color: #2f7d32;
}
.wan-uptime {
  color: #909399;
  font-size: 12px;
}
.wan-param-row {
  display: flex;
  padding: 5px 0;
  border-bottom: 1px solid #f2f6fc;
  font-size: 13px;
  align-items: center;
}
.wan-param-row .k {
  width: 150px;
  flex-shrink: 0;
  color: #67a04e;
}
.wan-param-row .v {
  color: #303133;
  word-break: break-all;
}
.v-strong {
  font-weight: 600;
}
.v-ip {
  color: #2f7d32;
  font-weight: 600;
}
.wan-more {
  border-top: 1px solid #ebeef5;
  margin-top: 6px;
}
.wan-all-row {
  display: flex;
  padding: 2px 0;
  font-size: 12px;
  border-bottom: 1px dashed #ebeef5;
}
.wan-all-row .k {
  width: 260px;
  color: #909399;
  flex-shrink: 0;
}
.wan-all-row .v {
  color: #303133;
  word-break: break-all;
}
/* ---- 设备详情弹窗 ---- */
.detail-row {
  display: flex;
  padding: 6px 0;
  border-bottom: 1px dashed #ebeef5;
  font-size: 13px;
}
.detail-row .k {
  width: 130px;
  flex-shrink: 0;
  color: #909399;
}
.detail-row .v {
  color: #303133;
  word-break: break-all;
}
.detail-row .v-strong {
  font-weight: 600;
}
</style>
