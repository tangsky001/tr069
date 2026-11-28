<template>
  <div>
    <div class="ont-toolbar">
      <div v-if="ontSummary" class="ont-stat">
        <span>总数：<b>{{ ontSummary.total }}</b></span>
        <span class="ont-stat-online">在线：<b>{{ ontSummary.online }}</b></span>
        <span class="ont-stat-offline">离线：<b>{{ ontSummary.offline }}</b></span>
      </div>
      <el-button size="small" disabled icon="el-icon-position">下发 TR069 VLAN 及 ACS 地址</el-button>
      <el-button size="small" disabled icon="el-icon-plus">增加业务流</el-button>
      <el-button size="small" disabled icon="el-icon-setting">TR069位重配</el-button>
      <el-button size="small" type="primary" icon="el-icon-refresh" @click="$emit('sync')">同步ONT状态</el-button>
    </div>

    <div class="ont-toolbar ont-toolbar-filter">
      <el-select v-model="stateFilter" size="small" clearable placeholder="在线状态" style="width: 130px">
        <el-option label="在线" value="online" />
        <el-option label="离线" value="offline" />
      </el-select>
      <el-select v-model="portFilter" size="small" clearable placeholder="板卡端口" style="width: 190px">
        <el-option v-for="option in ontPortOptions" :key="option.value" :label="option.label" :value="option.value" />
      </el-select>
      <span class="ont-count">共 <b>{{ filteredRows.length }}</b> 条</span>
    </div>

    <el-table
      :data="filteredRows"
      size="small"
      border
      stripe
      @selection-change="rows => selected = rows"
    >
      <el-table-column type="selection" width="42" align="center" />
      <el-table-column label="ID" type="index" width="48" align="center" />
      <el-table-column label="PON" prop="ponType" width="70" align="center" />
      <el-table-column label="端口" width="65" align="center">
        <template slot-scope="{ row }">{{ row.iface }}</template>
      </el-table-column>
      <el-table-column label="ONT ID" width="65" align="center">
        <template slot-scope="{ row }">
          <!-- 点开查硬件能力/状态 -->
          <span class="ont-link" @click="openQuery('capability', row)">{{ row.ontId }}</span>
        </template>
      </el-table-column>
      <el-table-column label="配置状态" width="70">
        <template slot-scope="{ row }">
          <span class="ont-auth">{{ row.authMethod }}</span>
          <div class="ont-config-line">
            <span class="ont-config">{{ row.configText || '-' }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="认证码 (SN)" width="150">
        <template slot-scope="{ row }">
          <!-- 点开查授权信息与业务流 -->
          <div class="ont-sn ont-link" @click="openOntConfig(row)">{{ row.sn }}</div>
          <div class="ont-sub">{{ row.desc }}</div>
        </template>
      </el-table-column>
      <el-table-column label="管理IP" width="140" align="center">
        <template slot-scope="{ row }">{{ ipOf(row) || '-' }}</template>
      </el-table-column>
      <el-table-column label="MAC" width="150" align="center">
        <template slot-scope="{ row }">{{ macOf(row) || '-' }}</template>
      </el-table-column>
      <el-table-column label="业务流" width="75" align="center">
        <template slot-scope="{ row }">
          <span class="ont-link" @click="openServicePorts(row)">查看</span>
        </template>
      </el-table-column>
      <el-table-column label="线路模板" width="75" align="center">
        <template slot-scope="{ row }">
          <span :class="{ 'ont-link': lineText(row) }" @click="toggleOntConfig(row)">
            {{ lineText(row) || '-' }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="业务模板" width="75" align="center">
        <template slot-scope="{ row }">
          <span :class="{ 'ont-link': srvText(row) }" @click="toggleOntConfig(row)">
            {{ srvText(row) || '-' }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="ONT 描述" min-width="120">
        <template slot-scope="{ row }">{{ row.desc || '-' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="60" align="center">
        <template slot-scope="{ row }">
          <span class="ont-state" :class="row.online ? 'ont-state-online' : 'ont-state-offline'">
            {{ row.stateText }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="查询" width="150" align="center">
        <template slot-scope="{ row }">
          <el-tooltip content="ONT 详情（IP / 线路模板 / 业务模板 / 掉线原因）">
            <el-button type="text" icon="el-icon-view" @click="openOntInfo(row)" />
          </el-tooltip>
          <el-tooltip content="光信息（收/发光功率、温度、电压）">
            <el-button type="text" icon="el-icon-odometer" @click="openQuery('optical', row)" />
          </el-tooltip>
          <el-tooltip content="版本信息">
            <el-button type="text" icon="el-icon-info" @click="openQuery('version', row)" />
          </el-tooltip>
          <el-tooltip content="注册历史（上/下线时间与原因）">
            <el-button type="text" icon="el-icon-time" @click="openQuery('register', row)" />
          </el-tooltip>
        </template>
      </el-table-column>
    </el-table>

    <div class="ont-table-foot">
      <span class="ont-selected-hint">
        {{ selected.length ? '已选 ' + selected.length + ' 条' : '勾选 ONT 后可批量操作' }}
      </span>
      <div>
        <el-button
          size="small"
          type="warning"
          :disabled="!selected.length"
          :loading="resetting"
          @click="batchReboot"
        >
          重启ONU
        </el-button>
        <el-button
          size="small"
          type="danger"
          :disabled="!selected.length"
          :loading="deleting"
          @click="batchDelete"
        >
          删除注册
        </el-button>
        <el-button
          size="small"
          type="danger"
          plain
          icon="el-icon-delete"
          title="删除这块业务板全部 PON 口上的 ONT（不需要勾选）"
          :loading="deleting"
          :disabled="!deletableOntTotal"
          @click="confirmDeleteAllOnts"
        >
          删除业务板 ONU
        </el-button>
        <el-button
          size="small"
          type="warning"
          plain
          icon="el-icon-refresh-right"
          title="复位这块业务板全部 PON 口上的 ONU（不需要勾选），口数按板卡快照逐口下发"
          :loading="resetting"
          :disabled="!deletableOntTotal"
          @click="confirmResetBoardOnts"
        >
          重启业务板 ONU
        </el-button>
      </div>
    </div>

    <!-- 按需查询弹窗：字段/值卡片 + 原始回显 -->
    <el-dialog :title="queryTitle" :visible.sync="queryVisible" width="640px" append-to-body>
      <div v-loading="queryLoading" class="ont-query-body">
        <template v-if="queryCards.length">
          <div v-for="(block, idx) in queryCards" :key="idx" class="ont-block-card">
            <div v-if="queryCards.length > 1" class="ont-cfg-caption">记录 {{ idx + 1 }}</div>
            <el-table :data="block" size="small" border>
              <el-table-column label="字段" prop="label" width="210" />
              <el-table-column label="值">
                <template slot-scope="{ row }">{{ row.value }}</template>
              </el-table-column>
            </el-table>
          </div>
        </template>
        <div v-else-if="!queryLoading" class="olt-empty">
          命令没有解析出有效数据，可展开下面的原始回显查看
        </div>
        <el-collapse v-if="queryRaw" class="ont-raw-collapse">
          <el-collapse-item :title="'原始回显：' + queryCommand">
            <pre class="ont-raw">{{ queryRaw }}</pre>
          </el-collapse-item>
        </el-collapse>
      </div>
    </el-dialog>

    <!-- ONT 详情弹窗 -->
    <el-dialog :title="infoTitle" :visible.sync="infoVisible" width="640px" append-to-body>
      <div v-loading="infoLoading" class="ont-query-body">
        <el-table v-if="infoFields.length" :data="infoFields" size="small" border :show-header="false">
          <el-table-column label="字段" prop="label" width="150" />
          <el-table-column label="值">
            <template slot-scope="{ row }">{{ row.value }}</template>
          </el-table-column>
        </el-table>
        <div v-else-if="!infoLoading" class="olt-empty">命令没有解析出有效数据，请稍后重试</div>
        <div class="ont-dialog-foot">
          <el-button size="small" icon="el-icon-search" :disabled="!infoRow" @click="openOntConfig(infoRow)">
            查配置与业务流
          </el-button>
        </div>
      </div>
    </el-dialog>

    <!-- 配置弹窗：授权信息与改授权表单 -->
    <el-dialog :title="cfgTitle" :visible.sync="cfgVisible" width="640px" append-to-body>
      <div v-loading="cfgLoading" class="ont-query-body">
        <template v-if="cfgFields.length">
          <div class="ont-cfg-caption">授权信息</div>
          <el-table :data="cfgFields" size="small" border :show-header="false">
            <el-table-column label="字段" prop="label" width="150" />
            <el-table-column label="值">
              <template slot-scope="{ row }">{{ row.value }}</template>
            </el-table-column>
          </el-table>
        </template>
        <div v-if="!cfgLoading && !cfgFields.length" class="olt-empty">
          命令没有解析出有效数据，请稍后重试
        </div>
        <!-- 提交暂不下发，先只读 -->
        <div class="ont-cfg-caption">改授权</div>
        <el-form size="small" label-position="top" class="ont-auth-form">
          <el-form-item label="机框/板卡/端口/ONT:">
            <el-input :value="cfgCoordinates" readonly />
          </el-form-item>
          <el-form-item label="注册方式:">
            <el-select v-model="cfgAuthType" placeholder="SN（设备序列号）/ MAC" style="width: 100%">
              <el-option label="SN（设备序列号）" value="sn-auth" />
              <el-option label="MAC" value="mac-auth" />
            </el-select>
          </el-form-item>
          <el-form-item label="认证码:">
            <el-input v-model="cfgAuthCode" placeholder="SN 或 MAC" />
          </el-form-item>
        </el-form>
        <div class="ont-dialog-foot ont-dialog-foot-split">
          <el-button type="success" size="small" @click="submitAuth">提交</el-button>
          <div>
            <el-button size="small" icon="el-icon-search" :disabled="!cfgRow" @click="openServicePorts(cfgRow)">
              查业务流
            </el-button>
            <el-button size="small" icon="el-icon-refresh" :disabled="!cfgRow" @click="loadOntConfig(cfgRow)">
              重新查询
            </el-button>
          </div>
        </div>
      </div>
    </el-dialog>

    <!-- 业务流弹窗 -->
    <el-dialog :title="spTitle" :visible.sync="spVisible" width="980px" append-to-body>
      <div v-loading="spLoading" class="ont-query-body">
        <el-table
          v-if="spPorts.length"
          :data="spPorts"
          size="small"
          border
          max-height="360"
          @selection-change="rows => spSelected = rows"
        >
          <el-table-column label="业务流ID" prop="index" width="90" align="center" />
          <el-table-column label="OLT网络侧VLAN" prop="vlanId" width="120" align="center" />
          <el-table-column label="VLAN 类型" width="100" align="center">
            <template slot-scope="{ row }">{{ vlanAttrText(row.vlanAttr) }}</template>
          </el-table-column>
          <el-table-column label="GEM Port" prop="gemport" width="90" align="center" />
          <el-table-column label="光猫用户侧VLAN" width="130" align="center">
            <template slot-scope="{ row }">
              {{ row.userVlan === null || row.userVlan === undefined ? '-' : row.userVlan }}
            </template>
          </el-table-column>
          <el-table-column label="上行流量模版" align="center">
            <template>-</template>
          </el-table-column>
          <el-table-column label="下行流量模版" align="center">
            <template>-</template>
          </el-table-column>
          <el-table-column label="编辑" width="70" align="center">
            <template>
              <el-button
                type="text"
                icon="el-icon-edit"
                title="编辑业务流（暂未开放）"
                @click="notReady('编辑业务流')"
              />
            </template>
          </el-table-column>
          <el-table-column type="selection" width="45" align="center" />
        </el-table>
        <div v-else-if="!spLoading" class="olt-empty">这条 ONT 没有业务流</div>
        <div v-if="spPorts.length" class="ont-dialog-foot ont-dialog-foot-split">
          <div>
            <el-button size="small" type="primary" plain @click="notReady('查询GMP 组播状态')">
              查询GMP 组播状态
            </el-button>
            <el-button size="small" type="primary" plain @click="notReady('查询未知组播策略')">
              查询未知组播策略
            </el-button>
            <el-button size="small" type="danger" :disabled="!spSelected.length" @click="notReady('删除业务流')">
              删除业务流
            </el-button>
            <el-button size="small" type="success" @click="notReady('新增业务流')">
              + 新增业务流
            </el-button>
          </div>
          <el-button size="small" icon="el-icon-refresh" :disabled="!spRow" @click="loadServicePorts(spRow)">
            重新查询
          </el-button>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { queryOnt, fetchOltOptions } from '@/api/olt'

const ACTION_TITLES = {
  optical: '光信息',
  version: '版本信息',
  register: '注册历史',
  capability: '硬件能力/状态'
}

/** 在线时长英文格式翻中文 */
function formatDuration(text) {
  return String(text)
    .replace(/(\d+)\s*day\(s\)/g, '$1天')
    .replace(/(\d+)\s*hour\(s\)/g, '$1小时')
    .replace(/(\d+)\s*minute\(s\)/g, '$1分')
    .replace(/(\d+)\s*second\(s\)/g, '$1秒')
}

/** 详情弹窗值按字段翻译，码表由后端下发 */
function infoValueText(label, value, tables) {
  const text = String(value == null ? '' : value).trim()
  if (!text) return ''
  const infoValues = (tables && tables.ontInfoValues) || {}
  const downReasons = (tables && tables.ontDownReasons) || {}
  if (label === '运行标志' || label === '配置状态' || label === '匹配状态') {
    return infoValues[text.toLowerCase()] || text
  }
  if (label === '最后下线原因') {
    return downReasons[text] || text
  }
  if (label === '在线时长') {
    return formatDuration(text)
  }
  return text
}

/** ONT 配置/状态主表：每条 ONT 一行，带筛选、批量操作与按需查询弹窗 */
export default {
  name: 'OltOntStatusPanel',
  props: {
    /** 后端摊平并排好序的 ONT 行 */
    ontRows: {
      type: Array,
      default: () => []
    },
    /** 板卡端口筛选下拉，value 为 槽位/端口 */
    ontPortOptions: {
      type: Array,
      default: () => []
    },
    /** 有 ONT 的业务板（slotNo/total），「删除业务板 ONU」用 */
    deletableBoards: {
      type: Array,
      default: () => []
    },
    /** 业务板 ONT 总数，0 表示按钮置灰 */
    deletableOntTotal: {
      type: Number,
      default: 0
    },
    oltId: {
      type: [Number, String],
      default: null
    },
    /** ONT 汇总（总数/在线/离线），由父页面算好 */
    ontSummary: {
      type: Object,
      default: null
    },
    emptyText: {
      type: String,
      default: '尚未采集到端口/ONT 数据。点右上角「同步ONT状态」立即采集。'
    },
    /** 删除业务板全部 ONT 的处理函数，由父页面提供 */
    deleteAllHandler: {
      type: Function,
      default: null
    },
    /** 删除单个 ONT 的处理函数，入参 { slot, port, ontId } */
    deleteOntHandler: {
      type: Function,
      default: null
    },
    /** 重启 ONU 的处理函数：{ slot, port, ontId } 复位单个，{ slot, port } 复位整口，{ slot } 复位整板 */
    resetOntHandler: {
      type: Function,
      default: null
    }
  },
  data() {
    return {
      stateFilter: '',
      portFilter: '',
      selected: [],
      /** 删除下发中，防连点 */
      deleting: false,
      /** 重启下发中，防连点 */
      resetting: false,
      queryVisible: false,
      queryLoading: false,
      queryAction: '',
      queryCommand: '',
      queryRaw: '',
      queryBlocks: [],
      infoVisible: false,
      infoLoading: false,
      infoRow: null,
      infoData: null,
      infoCommand: '',
      infoRaw: '',
      cfgVisible: false,
      cfgLoading: false,
      cfgRow: null,
      cfgData: null,
      cfgCommand: '',
      cfgRaw: '',
      cfgAuthType: 'sn-auth',
      cfgAuthCode: '',
      spVisible: false,
      spLoading: false,
      spRow: null,
      spPorts: [],
      spRaw: '',
      spCommand: '',
      spSelected: [],
      /** 回显键值翻译码表，来自后端 /olt/options */
      optionTables: null
    }
  },
  computed: {
    /** 后端下发的翻译码表 */
    codeTables() {
      return (this.optionTables && this.optionTables.codeTables) || {}
    },
    filteredRows() {
      return this.ontRows.filter(row => {
        if (this.stateFilter === 'online' && !row.online) return false
        if (this.stateFilter === 'offline' && row.online) return false
        if (this.portFilter && this.portFilter !== row.slotNo + '/' + row.portNo) return false
        return true
      })
    },
    /** 查询弹窗卡片数据：回显转「字段/值」行，空块丢弃 */
    queryCards() {
      return this.queryBlocks
        .map(block => this.blockRows(block))
        .filter(rows => rows.length)
    },
    queryTitle() {
      return 'ONT ' + (ACTION_TITLES[this.queryAction] || '信息')
    },
    infoTitle() {
      return this.infoRow ? 'ONT 详情 - ' + this.infoRow.iface + ' - ONT ' + this.infoRow.ontId : 'ONT 详情'
    },
    cfgTitle() {
      return this.cfgRow ? 'ONT 配置/业务流 - ' + this.cfgRow.iface + ' - ONT ' + this.cfgRow.ontId : 'ONT 配置/业务流'
    },
    cfgCoordinates() {
      if (!this.cfgRow) return ''
      return '0:' + this.cfgRow.slotNo + ':' + this.cfgRow.portNo + ':' + this.cfgRow.ontId
    },
    spTitle() {
      return this.spRow ? 'ONT 业务流 - ' + this.spRow.iface + ' - ONT ' + this.spRow.ontId : 'ONT 业务流'
    },
    /** 授权信息字段，空的不显示 */
    cfgFields() {
      const data = this.cfgData
      if (!data) return []
      const fields = []
      const push = (label, value) => {
        if (value !== null && value !== undefined && value !== '') {
          fields.push({ label, value })
        }
      }
      const profile = (id, name) => {
        if (id === null || id === undefined) return null
        return name ? id + ' / ' + name : String(id)
      }
      push('认证串 (SN)', data.sn)
      push('ONT 描述', data.desc)
      push('线路模板', profile(data.lineProfileId, data.lineProfileName))
      push('业务模板', profile(data.srvProfileId, data.srvProfileName))
      return fields
    },
    /** 详情字段，空的不显示，值翻中文 */
    infoFields() {
      const data = this.infoData
      if (!data) return []
      const fields = []
      const push = (label, value) => {
        if (value !== null && value !== undefined && value !== '') {
          fields.push({ label, value: infoValueText(label, value, this.codeTables) })
        }
      }
      const profile = (id, name) => {
        if (id === null || id === undefined) return null
        return name ? id + ' / ' + name : String(id)
      }
      push('认证串 (SN)', data.sn)
      push('ONT 描述', data.desc)
      push('IP 地址', data.ip)
      push('线路模板', profile(data.lineProfileId, data.lineProfileName))
      push('业务模板', profile(data.srvProfileId, data.srvProfileName))
      push('运行标志', data.runState)
      push('配置状态', data.configState)
      push('匹配状态', data.matchState)
      push('最后上线时间', data.lastUpTime)
      push('最后下线时间', data.lastDownTime)
      push('最后下线原因', data.lastDownReason)
      push('在线时长', data.onlineDuration)
      return fields
    }
  },
  created() {
    fetchOltOptions()
      .then(res => {
        this.optionTables = res.data || null
      })
      .catch(() => {
        this.optionTables = null
      })
  },
  methods: {
    /** 键翻中文，后端码表没对上的原样返回 */
    keyLabel(key) {
      const trimmed = (key || '').trim()
      const pq = trimmed.match(/^Number of PQs in T-CONT\s+(\d+)$/i)
      if (pq) {
        return 'T-CONT ' + pq[1] + ' 的 PQ 数目'
      }
      const labels = this.codeTables.ontKeyLabels || {}
      return labels[trimmed.toLowerCase()] || trimmed
    },
    /** 回显块转卡片行：字段按原顺序、空键跳过，空块返回空数组 */
    blockRows(block) {
      const rows = []
      Object.keys(block || {}).forEach(key => {
        let value = block[key]
        if (value === null || value === undefined || value === '') return
        value = String(value).trim()
        const label = this.keyLabel(key)
        const authValues = this.codeTables.ontAuthValues || {}
        const capValues = this.codeTables.ontCapValues || {}
        if (label === '认证方式' && authValues[value]) {
          value = authValues[value]
        }
        if (capValues[value.toLowerCase()]) {
          value = capValues[value.toLowerCase()]
        }
        rows.push({ label, value: value || '-' })
      })
      const meaningful = rows.filter(row => row.value !== '-')
      return meaningful.length ? meaningful : []
    },
    lineText(row) {
      if (row.lineProfileId === null || row.lineProfileId === undefined) return ''
      return String(row.lineProfileId)
    },
    srvText(row) {
      if (row.srvProfileId === null || row.srvProfileId === undefined) return ''
      return String(row.srvProfileId)
    },
    ipOf(row) {
      return row.mgmtIp || null
    },
    macOf(row) {
      return row.mac || null
    },
    vlanAttrText(attr) {
      if (!attr) return '-'
      const text = String(attr).trim().toLowerCase()
      const attrs = this.codeTables.vlanAttrs || {}
      return attrs[text] || attr
    },
    notReady(name) {
      this.$message.info(name + '：暂未开放，后续版本接入')
    },
    /** 批量重启勾选 ONU：串行逐条下发，y/n 由后端应答 */
    batchReboot() {
      const rows = (this.selected || []).filter(
        row => row.slotNo != null && row.portNo != null && row.ontId != null
      )
      if (!rows.length) {
        this.$message.warning('勾选的 ONU 缺少槽位/端口/ONT ID，请先「同步ONT状态」刷新数据')
        return
      }
      if (typeof this.resetOntHandler !== 'function') {
        this.$message.warning('当前页面没有接上重启 ONU 的接口')
        return
      }
      const label = rows
        .map(row => '0/' + row.slotNo + '/' + row.portNo + ' ont ' + row.ontId)
        .join('、')
      this.$confirm(
        '将复位 ' + label + '，共 ' + rows.length + ' 个；ONU 业务会中断并重新注册，' +
          '通常几十秒内恢复，确定继续？',
        '重启ONU',
        { type: 'warning', confirmButtonText: '确定重启', cancelButtonText: '取消' }
      )
        .then(() => {
          this.resetting = true
          let failed = 0
          let chain = Promise.resolve()
          rows.forEach(row => {
            chain = chain
              .then(() =>
                this.resetOntHandler({
                  slot: row.slotNo,
                  port: row.portNo,
                  ontId: row.ontId
                })
              )
              .catch(() => {
                failed++
              })
          })
          return chain.finally(() => {
            this.resetting = false
            this.selected = []
            if (failed) {
              this.$message.warning(failed + ' 个复位失败，设备回显见 OLT 操作记录')
            }
          })
        })
        .catch(() => {})
    },
    /** 重启业务板全部 ONU：逐板复位，口数由后端按快照算 */
    confirmResetBoardOnts() {
      const boards = this.deletableBoards
      if (!boards.length) {
        this.$message.warning('当前没有采到 ONT，先「同步ONT状态」再试')
        return
      }
      if (typeof this.resetOntHandler !== 'function') {
        this.$message.warning('当前页面没有接上重启 ONU 的接口')
        return
      }
      const detail = boards
        .map(board => board.text || ('0/' + board.slotNo + '（' + board.total + ' 个）'))
        .join('、')
      this.$confirm(
        '将复位 ' + detail + ' 上全部 ONU 的 PON 口（逐口 ont reset .. all），' +
          '这些 ONU 业务会中断并重新注册，确定继续？',
        '重启业务板 ONU',
        { type: 'warning', confirmButtonText: '确定重启', cancelButtonText: '取消' }
      )
        .then(() => {
          this.resetting = true
          let chain = Promise.resolve()
          boards.forEach(board => {
            chain = chain.then(() => this.resetOntHandler({ slot: board.slotNo })).catch(() => {})
          })
          return chain.finally(() => {
            this.resetting = false
          })
        })
        .catch(() => {})
    },
    /** 批量删除注册：串行逐条删，每条删完重采一次快照 */
    batchDelete() {
      const rows = (this.selected || []).filter(
        row => row.slotNo != null && row.portNo != null && row.ontId != null
      )
      if (!rows.length) {
        this.$message.warning('勾选的 ONU 缺少槽位/端口/ONT ID，请先「同步ONT状态」刷新数据')
        return
      }
      if (typeof this.deleteOntHandler !== 'function') {
        this.$message.warning('当前页面没有接上删除注册的接口')
        return
      }
      const label = rows
        .map(row => '0/' + row.slotNo + '/' + row.portNo + ' ont ' + row.ontId)
        .join('、')
      this.$confirm(
        '将删除 ' + label + '，共 ' + rows.length + ' 个；先拆业务虚口再删注册，' +
          '设备上的注册与业务配置随之消失，确定继续？',
        '删除注册',
        { type: 'warning', confirmButtonText: '确定删除', cancelButtonText: '取消' }
      )
        .then(() => {
          this.deleting = true
          let failed = 0
          let chain = Promise.resolve()
          rows.forEach(row => {
            chain = chain
              .then(() =>
                this.deleteOntHandler({
                  slot: row.slotNo,
                  port: row.portNo,
                  ontId: row.ontId
                })
              )
              .catch(() => {
                failed++
              })
          })
          return chain.finally(() => {
            this.deleting = false
            this.selected = []
            if (failed) {
              this.$message.warning(failed + ' 个删除失败，设备回显见 OLT 操作记录')
            }
          })
        })
        .catch(() => {})
    },
    /** 删除业务板全部 ONU，完成后重采快照 */
    confirmDeleteAllOnts() {
      const boards = this.deletableBoards
      if (!boards.length) {
        this.$message.warning('当前没有采到 ONT，先「同步ONT状态」再试')
        return
      }
      if (typeof this.deleteAllHandler !== 'function') {
        this.$message.warning('当前页面没有接上删除业务板 ONU 的接口')
        return
      }
      const detail = boards.map(board => board.text || ('0/' + board.slotNo + '（' + board.total + ' 个）')).join('、')
      const hint = '将删除 ' + detail + ' 上的全部 ONT，共 ' + this.deletableOntTotal +
        ' 个；设备上的注册与业务配置随之消失，确定继续？'
      this.$confirm(
        hint,
        '删除业务板 ONU',
        { type: 'warning', confirmButtonText: '确定删除', cancelButtonText: '取消' }
      )
        .then(() => {
          this.deleting = true
          return this.deleteAllHandler(boards.map(board => board.slotNo))
            .catch(() => {})
            .finally(() => {
              this.deleting = false
            })
        })
        .catch(() => {})
    },
    /** 点详情图标查 display ont info */
    openOntInfo(row) {
      if (this.oltId == null) return
      if (!this.checkRow(row)) return
      this.infoRow = row
      this.infoVisible = true
      this.loadOntInfo(row)
    },
    loadOntInfo(row) {
      this.infoLoading = true
      this.infoData = null
      this.infoRaw = ''
      this.infoCommand = ''
      return queryOnt(this.oltId, {
        action: 'info',
        slotNo: row.slotNo,
        portNo: row.portNo,
        ontId: row.ontId
      })
        .then(res => {
          const data = res.data || {}
          this.infoData = data.ontInfo || null
          this.infoRaw = data.raw || ''
          this.infoCommand = data.command || ''
        })
        .catch(err => {
          const data = (err && err.response && err.response.data) || {}
          this.$message.error(data.message || 'ONT 详情查询失败，请稍后再试')
        })
        .finally(() => {
          this.infoLoading = false
        })
    },
    /** 点 SN/模板列查 display current-configuration ont */
    openOntConfig(row) {
      if (this.oltId == null) return
      if (!this.checkRow(row)) return
      this.cfgRow = row
      this.cfgVisible = true
      this.loadOntConfig(row)
    },
    /** 同一行模板再点一次收起弹窗，点别的行切换 */
    toggleOntConfig(row) {
      if (this.cfgVisible && this.cfgRow && this.cfgRow.key === row.key) {
        this.cfgVisible = false
        return
      }
      this.openOntConfig(row)
    },
    loadOntConfig(row) {
      if (!row) return
      this.cfgLoading = true
      this.cfgData = null
      this.cfgRaw = ''
      this.cfgCommand = ''
      return queryOnt(this.oltId, {
        action: 'config',
        slotNo: row.slotNo,
        portNo: row.portNo,
        ontId: row.ontId
      })
        .then(res => {
          const data = res.data || {}
          this.cfgData = data.ontInfo || null
          this.cfgRaw = data.raw || ''
          this.cfgCommand = data.command || ''
          this.cfgAuthType = 'sn-auth'
          this.cfgAuthCode = (data.ontInfo && data.ontInfo.sn) || ''
        })
        .catch(err => {
          const data = (err && err.response && err.response.data) || {}
          this.$message.error(data.message || 'ONT 配置查询失败，请稍后再试')
        })
        .finally(() => {
          this.cfgLoading = false
        })
    },
    checkRow(row) {
      if (row.slotNo == null || row.portNo == null || row.ontId == null) {
        this.$message.warning('该行缺少槽位/端口/ONT ID，请先「同步ONT状态」刷新数据后再查询')
        return false
      }
      return true
    },
    /** 业务流列「查看」：单独查 display service-port port 0/x/x ont n */
    openServicePorts(row) {
      if (this.oltId == null) return
      if (!this.checkRow(row)) return
      this.spRow = row
      this.spVisible = true
      this.spPorts = []
      this.spSelected = []
      this.loadServicePorts(row)
    },
    loadServicePorts(row) {
      if (!row) return
      this.spLoading = true
      return queryOnt(this.oltId, {
        action: 'service-port',
        slotNo: row.slotNo,
        portNo: row.portNo,
        ontId: row.ontId
      })
        .then(res => {
          const data = res.data || {}
          this.spPorts = data.servicePorts || []
          this.spRaw = data.raw || ''
          this.spCommand = data.command || ''
        })
        .catch(err => {
          const data = (err && err.response && err.response.data) || {}
          this.$message.error(data.message || '业务流查询失败，请稍后再试')
        })
        .finally(() => {
          this.spLoading = false
        })
    },
    submitAuth() {
      this.$message.info('目前仅支持读数据，写操作暂未开放')
    },
    /** 按需查询：登录设备发命令，块状解析后铺表格 */
    openQuery(action, row) {
      if (this.oltId == null) return
      if (row.slotNo == null || row.portNo == null || row.ontId == null) {
        this.$message.warning('该行缺少槽位/端口/ONT ID，请先「同步ONT状态」刷新数据后再查询')
        return
      }
      this.queryAction = action
      this.queryVisible = true
      this.queryLoading = true
      this.queryBlocks = []
      this.queryRaw = ''
      this.queryCommand = ''
      queryOnt(this.oltId, {
        action,
        slotNo: row.slotNo,
        portNo: row.portNo,
        ontId: row.ontId
      })
        .then(res => {
          const data = res.data || {}
          this.queryBlocks = data.blocks || []
          this.queryRaw = data.raw || ''
          this.queryCommand = data.command || ''
        })
        .catch(err => {
          const data = (err && err.response && err.response.data) || {}
          this.$message.error(data.message || 'ONT 查询失败，请稍后再试')
        })
        .finally(() => {
          this.queryLoading = false
        })
    }
  }
}
</script>

<style lang="scss" scoped>
@import '~@/styles/olt.scss';

.ont-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}
.ont-toolbar-filter {
  justify-content: flex-start;
}
.ont-count {
  color: #909399;
  font-size: 12px;
  margin-left: auto;
}
.ont-auth {
  display: inline-block;
  padding: 0 6px;
  border-radius: 3px;
  background: #ecf5ff;
  color: #409eff;
  font-size: 12px;
}
.ont-config-line {
  margin-top: 2px;
}
.ont-config {
  color: #67c23a;
  font-size: 12px;
}
.ont-sn {
  font-weight: 600;
  color: #409eff;
  font-size: 13px;
}
.ont-table-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 10px;
}
.ont-selected-hint {
  color: #909399;
  font-size: 12px;
}
.ont-link {
  cursor: pointer;
  text-decoration: underline;
  text-decoration-style: dotted;
  text-underline-offset: 2px;
}
.ont-link:hover {
  color: #66b1ff;
}
.ont-link-hint {
  color: #909399;
  font-size: 12px;
}
.ont-tag {
  display: inline-block;
  margin-left: 4px;
  padding: 0 5px;
  border-radius: 3px;
  background: #f0f9eb;
  color: #67c23a;
  font-size: 12px;
}
.ont-dialog-foot {
  margin-top: 10px;
  text-align: right;
}
.ont-dialog-foot-split {
  display: flex;
  justify-content: space-between;
}
.ont-block-card {
  margin-bottom: 14px;
}
.ont-block-card:last-child {
  margin-bottom: 0;
}
.ont-cfg-caption {
  margin: 4px 0 6px;
  color: #606266;
  font-size: 13px;
  font-weight: 600;
}
.ont-sub {
  color: #909399;
  font-size: 12px;
}
.ont-state {
  font-size: 12px;
  font-weight: 600;
}
.ont-state-online {
  color: #67c23a;
}
.ont-state-offline {
  color: #f56c6c;
}
.ont-raw-collapse {
  margin-top: 12px;
}
.ont-raw {
  margin: 0;
  padding: 10px;
  background: #f5f7fa;
  border-radius: 4px;
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 320px;
  overflow: auto;
}
.ont-stat {
  display: inline-flex;
  align-items: center;
  gap: 16px;
  margin-right: 4px;
  padding: 4px 12px;
  font-size: 13px;
  color: #606266;
  background: #f5f7fa;
  border: 1px solid #ebeef5;
  border-radius: 4px;
}
.ont-stat b {
  font-size: 15px;
  color: #303133;
}
.ont-stat-online b {
  color: #67c23a;
}
.ont-stat-offline b {
  color: #909399;
}
</style>
