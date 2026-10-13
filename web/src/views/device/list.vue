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

    <el-table
      v-loading="loading"
      :data="displayList"
      border
      fit
      highlight-current-row
      style="width:100%"
      @selection-change="handleSelectionChange"
    >
      <el-table-column type="selection" width="42" align="center" />
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
            >
              <el-tag type="primary" size="mini" class="wan-count">{{ wanList(row.sn).length }}</el-tag>
              <span>条 WAN 连接</span>
            </el-link>
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

    <!-- 右下角批量操作工具条 -->
    <div class="batch-bar">
      <span class="batch-label">模版关键字</span>
      <el-select
        v-model="selectedTemplate"
        filterable
        clearable
        placeholder="== 业务模版 =="
        size="small"
        style="width:160px"
      >
        <el-option-group label="== 配置模板 ==">
          <el-option
            v-for="t in templates"
            :key="t.id"
            :label="t.name"
            :value="t.id"
          >
            <span>{{ t.name }}</span>
            <span class="member-hint">{{ t.categoryLabel || t.category }}</span>
          </el-option>
        </el-option-group>
      </el-select>
      <el-button size="small" type="primary" plain @click="batchDispatchTemplate">批量下发模版</el-button>

      <el-dropdown trigger="click" @command="cmd => handleBatchCommand(cmd)">
        <el-button size="small">一键工单<i class="el-icon-caret-top el-icon--right" /></el-button>
        <el-dropdown-menu slot="dropdown">
          <el-dropdown-item command="worklist-record" icon="el-icon-tickets">工单记录</el-dropdown-item>
          <el-dropdown-item command="worklist-batch" icon="el-icon-s-order" divided>批量开通工单</el-dropdown-item>
          <el-dropdown-item command="worklist-repair" icon="el-icon-s-tools">批量检修工单</el-dropdown-item>
        </el-dropdown-menu>
      </el-dropdown>

      <el-dropdown trigger="click" @command="cmd => handleBatchCommand(cmd)">
        <el-button size="small">更多...<i class="el-icon-caret-top el-icon--right" /></el-button>
        <el-dropdown-menu slot="dropdown">
          <el-dropdown-item command="query-admin-pwd">查询超管密码</el-dropdown-item>
          <el-dropdown-item command="set-admin-pwd">设置超管密码</el-dropdown-item>
          <el-dropdown-item command="query-telnet" divided>开启/查询 Telnet</el-dropdown-item>
          <el-dropdown-item command="close-telnet">关闭 Telnet</el-dropdown-item>
          <el-dropdown-item command="open-wan-access" divided>开启 WAN 侧访问</el-dropdown-item>
          <el-dropdown-item command="close-wan-access">关闭 WAN 侧访问</el-dropdown-item>
          <el-dropdown-item command="toggle-wifi">一键开启/关闭 WiFi</el-dropdown-item>
          <el-dropdown-item command="disable-sip">禁用 SIP 电话</el-dropdown-item>
          <el-dropdown-item command="del-wans">删除所有WAN连接(TR069除外)</el-dropdown-item>
          <el-dropdown-item command="unlock-net">解除上网限制(将重启光猫)</el-dropdown-item>
          <el-dropdown-item command="diagnose">诊断测试</el-dropdown-item>
          <el-dropdown-item command="clear-cache">删除设备缓存</el-dropdown-item>
          <el-dropdown-item command="mark-swap">标记并置换光猫</el-dropdown-item>
          <el-dropdown-item command="factory-clear" divided>恢复出厂并清零（不再接受汇报）</el-dropdown-item>
          <el-dropdown-item command="factory-reset">恢复出厂配置</el-dropdown-item>
          <el-dropdown-item command="deep-factory-reset">深度恢复出厂</el-dropdown-item>
          <el-dropdown-item command="deep-factory-clear">深度恢复出厂并清零（不再接受汇报）</el-dropdown-item>
        </el-dropdown-menu>
      </el-dropdown>

      <el-dropdown trigger="click" @command="cmd => handleAdvancedCommand(cmd)">
        <el-button size="small">高级...<i class="el-icon-caret-top el-icon--right" /></el-button>
        <el-dropdown-menu slot="dropdown">
          <el-dropdown-item command="export">导出设备</el-dropdown-item>
          <el-dropdown-item command="custom-param">下发自定义 TR069 参数值</el-dropdown-item>
          <el-dropdown-item command="debug">调试指令</el-dropdown-item>
          <el-dropdown-item command="telnet">执行 Telnet 命令</el-dropdown-item>
          <el-dropdown-item command="strip-ssid">去除 WiFi SSID 前缀</el-dropdown-item>
          <el-dropdown-item command="fix-sip">修复 SIP 注册错误</el-dropdown-item>
          <el-dropdown-item command="fix-bridge">修复 Internet 桥接/DHCP 透传</el-dropdown-item>
          <el-dropdown-item command="export-config">导出配置文件</el-dropdown-item>
          <el-dropdown-item command="clone-config">克隆配置文件</el-dropdown-item>
          <el-dropdown-item command="force-report">强制上报/上线</el-dropdown-item>
          <el-dropdown-item command="refresh">刷新状态</el-dropdown-item>
        </el-dropdown-menu>
      </el-dropdown>

      <el-dropdown trigger="click" @command="cmd => handleOltCommand(cmd)">
        <el-button size="small">OLT融合管理<i class="el-icon-caret-top el-icon--right" /></el-button>
        <el-dropdown-menu slot="dropdown">
          <el-dropdown-item command="olt-mgr" icon="el-icon-s-platform">OLT/ONU 管理</el-dropdown-item>
          <el-dropdown-item command="olt-topo" icon="el-icon-share" divided>OLT 拓扑（开发中）</el-dropdown-item>
        </el-dropdown-menu>
      </el-dropdown>

      <el-button size="small" type="warning" plain @click="batchReboot">重启</el-button>
    </div>

    <!-- 下发自定义 TR069 参数弹窗 -->
    <el-dialog title="下发自定义 TR069 参数值" :visible.sync="customParamVisible" width="640px">
      <el-form label-width="100px" size="small">
        <el-form-item label="目标设备">
          <span>{{ customParamScope }}</span>
        </el-form-item>
        <el-form-item v-for="(row, idx) in customParamRows" :key="idx">
          <div class="custom-param-row">
            <el-input v-model="row.name" placeholder="参数路径，如 InternetGatewayDevice.DeviceInfo.X_..." style="width:56%" />
            <el-input v-model="row.value" placeholder="值" style="width:22%" />
            <el-select v-model="row.type" style="width:20%">
              <el-option label="xsd:string" value="xsd:string" />
              <el-option label="xsd:boolean" value="xsd:boolean" />
              <el-option label="xsd:int" value="xsd:int" />
              <el-option label="xsd:unsignedInt" value="xsd:unsignedInt" />
            </el-select>
            <el-button type="text" icon="el-icon-delete" @click="customParamRows.splice(idx, 1)" />
          </div>
        </el-form-item>
        <el-form-item>
          <el-button size="mini" icon="el-icon-plus" @click="customParamRows.push({ name: '', value: '', type: 'xsd:string' })">加一行</el-button>
        </el-form-item>
      </el-form>
      <span slot="footer">
        <el-button type="primary" @click="submitCustomParam">下发</el-button>
        <el-button @click="customParamVisible = false">取消</el-button>
      </span>
    </el-dialog>

    <!-- 调试指令弹窗 -->
    <el-dialog title="调试指令" :visible.sync="debugVisible" width="620px">
      <el-form label-width="100px" size="small">
        <el-form-item label="目标设备">
          <span>{{ customParamScope }}</span>
        </el-form-item>
        <el-form-item label="RPC 方法">
          <el-select v-model="debugForm.rpc" filterable allow-create style="width:100%">
            <el-option v-for="m in rpcMethods" :key="m" :label="m" :value="m" />
          </el-select>
        </el-form-item>
        <el-form-item label="参数 JSON">
          <el-input v-model="debugForm.args" type="textarea" :rows="5" placeholder="{&quot;parameterNames&quot;:[&quot;InternetGatewayDevice.&quot;]}" />
        </el-form-item>
      </el-form>
      <span slot="footer">
        <el-button type="primary" @click="submitDebug">下发</el-button>
        <el-button @click="debugVisible = false">取消</el-button>
      </span>
    </el-dialog>

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
import { fetchRpcResult } from '@/api/rpc'
import { fetchConfigTemplates } from '@/api/configTemplate'
import { RPC_METHODS } from '@/api/device'
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
      // 批量操作工具条
      selection: [],
      templates: [],
      selectedTemplate: '',
      customParamVisible: false,
      customParamRows: [{ name: '', value: '', type: 'xsd:string' }],
      debugVisible: false,
      debugForm: { rpc: 'GetParameterValues', args: '{}' },
      rpcMethods: RPC_METHODS,
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
    // 自定义参数/调试指令弹窗里的目标设备描述
    customParamScope() {
      const sns = this.selectedSns()
      if (!sns.length) return '（未勾选设备，请先在列表勾选）'
      return sns.length === 1 ? sns[0] : '已勾选 ' + sns.length + ' 台设备'
    },
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
    }
  },
  created() {
    this.load()
    this.loadOnline()
    this.loadTemplates()
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

    formatTime(value) {
      if (!value) return '-'
      return String(value).replace('T', ' ')
    },

    // ---------------- 右下角批量操作工具条 ----------------
    handleSelectionChange(rows) {
      this.selection = rows
    },
    selectedSns() {
      return this.selection.map(r => r.sn)
    },
    // 批量操作前校验勾选，返回 SN 列表；未勾选提示并返回 null
    requireSelection() {
      const sns = this.selectedSns()
      if (!sns.length) {
        this.$message.warning('请先勾选要操作的设备')
        return null
      }
      return sns
    },
    loadTemplates() {
      fetchConfigTemplates({}).then(res => {
        this.templates = res.data || []
      }).catch(() => {
        this.templates = []
      })
    },
    batchDispatchTemplate() {
      const sns = this.requireSelection()
      if (!sns) return
      if (!this.selectedTemplate) {
        this.$message.warning('请先选择模版关键字')
        return
      }
      // 下发逻辑：按模板意图 + 型号映射生成真实参数，待后端实现后接入
      this.todoPending('批量下发模版（下发逻辑待接入）')
    },
    handleBatchCommand(cmd) {
      if (cmd === 'worklist-record') {
        this.$router.push({ name: 'WorklistInstances' })
        return
      }
      // 恢复出厂类命令走现有 FactoryReset RPC，其余待后端接入
      if (['factory-clear', 'factory-reset', 'deep-factory-reset', 'deep-factory-clear'].includes(cmd)) {
        const sns = this.requireSelection()
        if (!sns) return
        this.$confirm('恢复出厂会清空设备全部配置，对已勾选 ' + sns.length + ' 台设备执行？', '恢复出厂', { type: 'warning' })
          .then(() => sns.forEach(sn => this.quickRpc(sn, 'FactoryReset', {}, true)))
          .catch(() => {})
        return
      }
      this.todoPending('批量操作：' + cmd)
    },
    handleOltCommand(cmd) {
      if (cmd === 'olt-mgr') {
        this.$router.push({ name: 'OltManagement' })
        return
      }
      this.todoPending('OLT 融合管理')
    },
    handleAdvancedCommand(cmd) {
      const sns = this.requireSelection()
      if (!sns) return
      switch (cmd) {
        case 'export':
          this.exportDevices(sns)
          break
        case 'custom-param':
          this.customParamVisible = true
          break
        case 'debug':
          this.debugVisible = true
          break
        case 'refresh':
          this.refreshStatus()
          break
        default:
          this.todoPending('高级操作：' + cmd)
      }
    },
    submitCustomParam() {
      const rows = this.customParamRows.filter(r => r.name)
      if (!rows.length) {
        this.$message.warning('请至少填写一条参数')
        return
      }
      const sns = this.requireSelection()
      if (!sns) return
      const values = rows.map(r => [r.name, r.value, r.type])
      sns.forEach(sn => {
        dispatchRpc(sn, 'SetParameterValues', { parameterValues: values })
          .catch(() => {})
      })
      this.$message.success('已向 ' + sns.length + ' 台设备下发 SetParameterValues')
    },
    submitDebug() {
      const sns = this.requireSelection()
      if (!sns) return
      let args = {}
      try {
        args = JSON.parse(this.debugForm.args || '{}')
      } catch (e) {
        this.$message.error('参数 JSON 格式不正确')
        return
      }
      sns.forEach(sn => this.quickRpc(sn, this.debugForm.rpc, args, true))
      this.debugVisible = false
    },
    exportDevices(sns) {
      const rows = sns.length ? this.list.filter(r => sns.includes(r.sn)) : this.displayList
      const header = ['SN', 'OUI', '运营商', '型号', '硬件版本', '固件版本', '状态', '注册时间', '最近上报']
      const lines = rows.map(r => [
        r.sn, r.oui, r.isp, r.productClass || r.deviceType, r.hardwareVersion,
        r.softwareVersion, this.isOnline(r) ? '在线' : '离线',
        this.formatTime(r.createdAt), this.formatTime(r.lastContactTime)
      ])
      const csv = [header].concat(lines).map(l => l.join(',')).join('\n')
      const blob = new Blob(['\ufeff' + csv], { type: 'text/csv;charset=utf-8' })
      const link = document.createElement('a')
      link.href = URL.createObjectURL(blob)
      link.download = 'devices.csv'
      link.click()
      URL.revokeObjectURL(link.href)
    },
    refreshStatus() {
      this.load()
      this.loadOnline()
      this.$message.success('已刷新列表与在线状态')
    },
    // 批量重启：勾选设备逐台下发 Reboot
    batchReboot() {
      const sns = this.requireSelection()
      if (!sns) return
      this.$confirm('确认重启已勾选的 ' + sns.length + ' 台设备？', '批量重启', { type: 'warning' })
        .then(() => sns.forEach(sn => this.quickRpc(sn, 'Reboot', {}, true)))
        .catch(() => {})
    },
    todoPending(text) {
      this.$message.info(text + '：功能开发中，后端接口接入后启用')
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
/* 右下角批量操作工具条 */
.batch-bar {
  margin-top: 10px;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  flex-wrap: wrap;
}
.batch-bar .batch-label {
  color: #909399;
  font-size: 12px;
  margin-right: 2px;
}
.batch-bar .el-button + .el-button {
  margin-left: 0;
}
.custom-param-row {
  display: flex;
  align-items: center;
  gap: 6px;
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
.mode-tip {
  color: #909399;
  font-size: 12px;
  line-height: 1.4;
  margin-top: 2px;
}
.wan-count {
  margin-right: 4px;
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
