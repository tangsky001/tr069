<template>
  <div>
    <!-- 顶部操作条：左边 ONT 汇总，右边写操作（先占位）+ 实时刷新 -->
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

    <!-- 筛选行 -->
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
      <el-table-column label="端口" width="90" align="center">
        <template slot-scope="{ row }">{{ row.iface }}</template>
      </el-table-column>
      <el-table-column label="ONT ID" width="80" align="center">
        <template slot-scope="{ row }">
          <!-- ONT ID 可点：点开查 display ont capability 0/<槽位> <端口> <ONT ID>，硬件能力/状态 -->
          <span class="ont-link" @click="openQuery('capability', row)">{{ row.ontId }}</span>
        </template>
      </el-table-column>
      <el-table-column label="配置状态" width="170">
        <template slot-scope="{ row }">
          <span class="ont-auth">{{ row.authMethod }}</span>
          <div class="ont-config-line">
            <span class="ont-config">{{ row.configText || '-' }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="认证码 (SN)" width="150">
        <template slot-scope="{ row }">
          <!-- SN 可点：点开查 display current-configuration ont，授权信息 + 业务流都在这一份里 -->
          <div class="ont-sn ont-link" @click="openOntConfig(row)">{{ row.sn }}</div>
          <div class="ont-sub">{{ row.desc }}</div>
        </template>
      </el-table-column>
      <!-- 管理 IP / MAC / 线路 / 业务模板：同步采集时后端已随单台详情采回，直接显示 -->
      <el-table-column label="管理IP" width="150" align="center">
        <template slot-scope="{ row }">{{ ipOf(row) || '-' }}</template>
      </el-table-column>
      <el-table-column label="MAC" width="170" align="center">
        <template slot-scope="{ row }">{{ macOf(row) || '-' }}</template>
      </el-table-column>
      <el-table-column label="业务流" width="90" align="center">
        <template slot-scope="{ row }">
          <span class="ont-link" @click="openServicePorts(row)">查看</span>
        </template>
      </el-table-column>
      <el-table-column label="线路模板" width="80" align="center">
        <template slot-scope="{ row }">
          <span :class="{ 'ont-link': lineText(row) }" @click="toggleOntConfig(row)">
            {{ lineText(row) || '-' }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="业务模板" width="80" align="center">
        <template slot-scope="{ row }">
          <span :class="{ 'ont-link': srvText(row) }" @click="toggleOntConfig(row)">
            {{ srvText(row) || '-' }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="ONT 描述" min-width="120">
        <template slot-scope="{ row }">{{ row.desc || '-' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="70" align="center">
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

    <!-- 底部批量操作：勾选行后生效，写通道还没接，点了只提示；最后一个是整块业务板的清理 -->
    <div class="ont-table-foot">
      <span class="ont-selected-hint">
        {{ selected.length ? '已选 ' + selected.length + ' 条' : '勾选 ONT 后可批量操作' }}
      </span>
      <div>
        <el-button size="small" type="warning" :disabled="!selected.length" @click="batchReboot">
          重启ONU
        </el-button>
        <el-button size="small" type="danger" :disabled="!selected.length" @click="batchDelete">
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
      </div>
    </div>

    <!-- 按需查询弹窗：每条记录一张「字段 / 值」竖排卡片（英文标签翻成中文）+ 原始回显 -->
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

    <!-- ONT 详情弹窗：display ont info，IP / 线路模板 / 业务模板 / 掉线原因，只展示数据 -->
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

    <!-- 配置弹窗：点 SN 或模板列时查 display current-configuration ont（授权信息），
         业务流在单独的「ONT 业务流」弹窗里看 -->
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
        <!-- 改授权：标签在上、通栏输入框，提交目前只读、写操作暂未开放 -->
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

    <!-- 业务流弹窗：点业务流列「查看」时单独查 display service-port port 0/x/x ont n，只放业务流表 -->
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

/** 在线时长英文格式翻中文：1 day(s), 10 hour(s) → 1天, 10小时（纯格式化） */
function formatDuration(text) {
  return String(text)
    .replace(/(\d+)\s*day\(s\)/g, '$1天')
    .replace(/(\d+)\s*hour\(s\)/g, '$1小时')
    .replace(/(\d+)\s*minute\(s\)/g, '$1分')
    .replace(/(\d+)\s*second\(s\)/g, '$1秒')
}

/**
 * 详情弹窗按字段翻译值：状态 / 掉线原因 / 在线时长。
 * 码表（哪种状态叫什么、掉线原因怎么解释）由后端 /olt/options 下发，这里只查表。
 */
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

/**
 * ONT 配置/状态主表：每条 ONT 一行（板卡 × 端口 × ONT 摊平），带筛选与按需查询。
 *
 * 数据来自轮询采集（display ont info 0 <槽位> all）；光信息、版本、注册历史这三类
 * 是块状明细，几百条 ONT 逐个采不现实，点行上的图标时才登录设备查一次
 * （display ont optical-info / version / register-info），弹窗里铺成表格。
 *
 * 管理 IP、MAC、线路模板、业务模板这几列在同步采集时就由后端随每条 ONT 的
 * 单台详情（display ont info 0 <槽位> <端口> <ONT ID>）一起采回，打开列表就是全的，
 * 不用再点着查；老快照没有这些字段时显示 -，点一次「同步ONT状态」即补齐。
 *
 * 仍按需查的：点 SN / 模板列查 display current-configuration ont（授权行与业务流），
 * 「详情」图标查 display ont info（运行状态、掉线原因、在线时长），业务流列单独查
 * display service-port port 0/<槽位>/<端口> ont <ONT ID>。
 *
 * 下发 TR069 / 业务流那些写操作是占位，下发能力稍后接入。
 */
export default {
  name: 'OltOntStatusPanel',
  props: {
    /** 后端摊好的 ONT 行（板卡 × 端口 × ONT 一条一行），页签只渲染 */
    ontRows: {
      type: Array,
      default: () => []
    },
    /** 后端给的「板卡端口」筛选下拉：value 是 槽位/端口，label 是「槽位 2 - 0/2/1」 */
    ontPortOptions: {
      type: Array,
      default: () => []
    },
    /** 有 ONT 的业务板（后端算好的 slotNo / total），「删除业务板 ONU」用 */
    deletableBoards: {
      type: Array,
      default: () => []
    },
    /** 这些业务板上的 ONT 总数，0 表示按钮置灰 */
    deletableOntTotal: {
      type: Number,
      default: 0
    },
    oltId: {
      type: [Number, String],
      default: null
    },
    /** ONT 汇总（总数/在线/离线）：由父页面算好传进来，和操作按钮排在同一行 */
    ontSummary: {
      type: Object,
      default: null
    },
    emptyText: {
      type: String,
      default: '尚未采集到端口/ONT 数据。点右上角「同步ONT状态」立即采集。'
    },
    /** 删除业务板全部 ONT：父页面传进来，槽位/端口由后端按库里板卡算 */
    deleteAllHandler: {
      type: Function,
      default: null
    }
  },
  data() {
    return {
      stateFilter: '',
      portFilter: '',
      selected: [],
      /** 删除业务板 ONT 下发中：按钮转圈，防止连点重复下发 */
      deleting: false,
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
      // 配置弹窗：display current-configuration ont 的授权信息
      cfgVisible: false,
      cfgLoading: false,
      cfgRow: null,
      cfgData: null,
      cfgCommand: '',
      cfgRaw: '',
      // 改授权表单：只读先行，提交暂不下发
      cfgAuthType: 'sn-auth',
      cfgAuthCode: '',
      // 业务流弹窗：业务流列「查看」单独查 display service-port port 0/x/x ont n
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
    /** 码表：ONT 回显键值、认证方式、VLAN 属性、掉线原因，全部由后端下发 */
    codeTables() {
      return (this.optionTables && this.optionTables.codeTables) || {}
    },
    /**
     * 只按页面上的两个筛选条件过滤。
     *
     * <p>摊平（板卡 → 端口 → ONT）、字段改名（{@code desc} / {@code stateText} /
     * {@code configText}）和认证方式判定都在后端做——原来这里按 {@code ont.id}、
     * {@code ont.stateText} 取值，而接口给的是 {@code ontId}、{@code runState}，
     * 于是状态列一直是空的。</p>
     */
    filteredRows() {
      return this.ontRows.filter(row => {
        if (this.stateFilter === 'online' && !row.online) return false
        if (this.stateFilter === 'offline' && row.online) return false
        if (this.portFilter && this.portFilter !== row.slotNo + '/' + row.portNo) return false
        return true
      })
    },
    /**
     * 有 ONT 的业务板：按钮是否可用、确认框里列哪几块板都按它来。
     * 槽位取的是采到的实际值（这台是 2 号槽位就是 0/2），不写死。
     */

    /**
     * 查询弹窗的卡片数据：每块回显转成「字段 / 值」行，英文标签翻成中文，
     * 全是「-」的空块（比如注册历史末尾的占位段）直接丢掉。
     */
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
    /** 改授权表单第一行的坐标，照现场工具的 0:1:0:1 格式 */
    cfgCoordinates() {
      if (!this.cfgRow) return ''
      return '0:' + this.cfgRow.slotNo + ':' + this.cfgRow.portNo + ':' + this.cfgRow.ontId
    },
    spTitle() {
      return this.spRow ? 'ONT 业务流 - ' + this.spRow.iface + ' - ONT ' + this.spRow.ontId : 'ONT 业务流'
    },
    /** 配置弹窗上半部分的授权信息字段：空的不摆出来 */
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
    /** 详情弹窗要显示的字段：空的不摆出来，值按字段翻成中文 */
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
    /** 英文键翻中文（后端码表小写键），没对上的原样返回；T-CONT 的 PQ 行是动态键 */
    keyLabel(key) {
      const trimmed = (key || '').trim()
      const pq = trimmed.match(/^Number of PQs in T-CONT\s+(\d+)$/i)
      if (pq) {
        return 'T-CONT ' + pq[1] + ' 的 PQ 数目'
      }
      // 键→中文由后端下发（键统一小写），没对上的原样显示
      const labels = this.codeTables.ontKeyLabels || {}
      return labels[trimmed.toLowerCase()] || trimmed
    },
    /**
     * 一块回显转成卡片行：字段顺序按原回显、空键跳过、
     * 全是「-」的块返回空数组（queryCards 会滤掉）。
     */
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
      // 全是「-」的占位块（注册历史末尾常见）没有展示价值
      const meaningful = rows.filter(row => row.value !== '-')
      return meaningful.length ? meaningful : []
    },
    /** 线路模板：同步时随单台详情采回，只显示编号（如 100）；老快照没有显示 - */
    lineText(row) {
      if (row.lineProfileId === null || row.lineProfileId === undefined) return ''
      return String(row.lineProfileId)
    },
    /** 业务模板：同线路模板，只显示编号 */
    srvText(row) {
      if (row.srvProfileId === null || row.srvProfileId === undefined) return ''
      return String(row.srvProfileId)
    },
    /** 管理 IP：同步时随单台详情采回；桥接模式设备没上报管理地址就显示 - */
    ipOf(row) {
      return row.mgmtIp || null
    },
    /** MAC：后端从单台详情回显或 MAC 认证码还原，前端不再自己拼 */
    macOf(row) {
      return row.mac || null
    },
    /** VLAN 属性翻中文，没对上的原样 */
    vlanAttrText(attr) {
      if (!attr) return '-'
      const text = String(attr).trim().toLowerCase()
      const attrs = this.codeTables.vlanAttrs || {}
      return attrs[text] || attr
    },
    /** 业务流上的操作（查询 GMP / 删除 / 新增等）：能力还没接入，先提示 */
    notReady(name) {
      this.$message.info(name + '：暂未开放，后续版本接入')
    },
    /** 批量操作：写通道还没接，先提示 */
    batchReboot() {
      this.$message.info('写操作暂未开放：重启ONU（ont reboot）后续接入')
    },
    batchDelete() {
      this.$message.info('写操作暂未开放：删除注册（ont delete）后续接入')
    },
    /**
     * 删除业务板上的全部 ONU：按采到的业务板逐块清（这台是 2 号槽位就发
     * {@code interface gpon 0/2} 再逐口 {@code ont delete <端口> all}），后端下发成功后整机重采。
     */
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
      // 板卡表示法（0/2（12 个））与总数都由后端给，页面只拼这句确认语气
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
    /**
     * 点 SN 打开详情：display ont info，运行状态 / 掉线原因 / 在线时长在这一份里
     * （IP 与模板同步时已采进快照，这里再看的是补充字段）。
     */
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
    /**
     * 点 SN / 模板列打开配置弹窗：display current-configuration ont 0/<槽位>/<端口> <ONT ID>，
     * 授权行（SN / 线路模板 / 业务模板 / 描述）和业务流行一条命令全带回来。
     */
    openOntConfig(row) {
      if (this.oltId == null) return
      if (!this.checkRow(row)) return
      this.cfgRow = row
      this.cfgVisible = true
      this.loadOntConfig(row)
    },
    /**
     * 模板列点击：同一行的模板编号再点一次就是收起弹窗（可点击取消）；
     * 点别的行则切换到那条 ONT 的配置。
     */
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
          // 改授权表单预填当前认证码（注册方式默认 SN）
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
    /** 槽位/端口/ONT ID 缺一不可，行是老快照时会缺 */
    checkRow(row) {
      if (row.slotNo == null || row.portNo == null || row.ontId == null) {
        this.$message.warning('该行缺少槽位/端口/ONT ID，请先「同步ONT状态」刷新数据后再查询')
        return false
      }
      return true
    },
    /**
     * 业务流列「查看」：单独查 display service-port port 0/<槽位>/<端口> ont <ONT ID>，
     * 弹窗里只放业务流表，和点 SN 的配置弹窗分开。
     */
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
    /** 改授权提交：写通道还没接，先提示只读 */
    submitAuth() {
      this.$message.info('目前仅支持读数据，写操作暂未开放')
    },
    /** 点图标按需查一次：登录设备进 GPON 端口视图发命令，块状解析后铺表格 */
    openQuery(action, row) {
      if (this.oltId == null) return
      // 槽位/端口/ONT ID 缺一不可（后端也会校验），行数据是老快照时会缺，提示先重新同步
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
/* 操作条最左边的 ONT 汇总窗：总数 / 在线 / 离线，和按钮同一行 */
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
