<template>
  <div>
    <!-- 顶部操作条：写操作先占位（下发能力稍后接入），同步ONT状态接实时刷新 -->
    <div class="ont-toolbar">
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
        <el-option v-for="option in portOptions" :key="option.value" :label="option.label" :value="option.value" />
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
      <!-- 管理 IP：整板回显没有，点「详情」图标查过 display ont info 后回填本行 -->
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
      <!-- 整板回显里没有模板号，第一次点这两列时顺那条 ONT 的配置取回来存本地 -->
      <el-table-column label="线路模板" width="90" align="center">
        <template slot-scope="{ row }">
          <span v-if="lineText(row)" class="ont-link" @click="openOntConfig(row)">
            {{ lineText(row) }}
          </span>
          <span v-else class="ont-link ont-link-hint" @click="openOntConfig(row)">查看</span>
        </template>
      </el-table-column>
      <el-table-column label="业务模板" width="90" align="center">
        <template slot-scope="{ row }">
          <span v-if="srvText(row)" class="ont-link" @click="openOntConfig(row)">
            {{ srvText(row) }}
          </span>
          <span v-else class="ont-link ont-link-hint" @click="openOntConfig(row)">查看</span>
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

    <!-- 底部批量操作：勾选行后生效，写通道还没接，点了只提示 -->
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
            <template slot-scope="{ row }">
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
import { queryOnt } from '@/api/olt'

const ACTION_TITLES = {
  optical: '光信息',
  version: '版本信息',
  register: '注册历史',
  capability: '硬件能力/状态'
}

/**
 * 设备英文回显 → 中文标签：光信息 / 版本 / 注册历史的字段名照现场中文对照翻，
 * 没对上的键原样显示（设备切了 language-mode 时回显本身就是中文，不走这张表）。
 */
const KEY_LABELS = {
  // 注册历史
  Index: '索引编号',
  'Auth-type': '认证方式',
  'Auth type': '认证方式',
  SN: '序列号',
  Type: 'ONT 类型',
  UpTime: '上线时间',
  DownTime: '下线时间',
  DownReason: '下线原因',
  // 光信息
  'ONU NNI port ID': 'ONU网络侧端口编号',
  'Module type': '光模块类型',
  'Module sub-type': '光模块子类型',
  'Used type': '使用场景',
  'Encapsulation Type': '封装类型',
  'Optical power precision(dBm)': '光功率精度(dBm)',
  'Vendor name': '生产厂商',
  'Vendor rev': '厂商版本',
  'Vendor PN': '厂商产品编号',
  'Vendor SN': '厂商产品序列号',
  'Vendor Code': '厂商代码',
  'Date Code': '出厂日期',
  'Rx optical power(dBm)': '接收功率(dBm)',
  'Rx power current warning threshold(dBm)': '接收功率警示门限值(dBm)',
  'Rx power current alarm threshold(dBm)': '接收功率告警门限值(dBm)',
  'Tx optical power(dBm)': '发送功率(dBm)',
  'Tx power warning threshold(dBm)': '发送功率警示门限值(dBm)',
  'Tx power alarm threshold(dBm)': '发送功率告警门限值(dBm)',
  'Laser bias current(mA)': '偏置电流(mA)',
  'Laser bias current warning threshold(mA)': '偏置电流警示门限值(mA)',
  'Laser bias current alarm threshold(mA)': '偏置电流告警门限值(mA)',
  'Temperature(C)': '温度(℃)',
  'Temperature warning threshold(C)': '温度警示门限值(℃)',
  'Temperature alarm threshold(C)': '温度告警门限值(℃)',
  'Voltage(V)': '电压(V)',
  'Supply voltage warning threshold(V)': '电压警示门限值(V)',
  'Supply voltage alarm threshold(V)': '电压告警门限值(V)',
  'OLT Rx ONT optical power(dBm)': 'OLT接收ONT光功率(dBm)',
  'CATV Rx optical power(dBm)': 'CATV接收光功率(dBm)',
  'CATV Rx power alarm threshold(dBm)': 'CATV接收功率告警门限值(dBm)',
  // 版本信息
  'F/S/P': '框/槽/端口',
  'ONT-ID': 'ONT编号',
  'Equipment ID': '设备ID号',
  'Main Software Version': '主用软件版本',
  'Backup Software Version': '备用软件版本',
  'Ont Product ID': '产品编号',
  // 硬件能力/状态（display ont capability 0/<槽位> <端口> <ONT ID>）
  'ONT description': 'ONT的描述信息',
  'ONT ID': 'ONT编号',
  'Number of uplink PON ports': '上行PON口数目',
  'Number of POTS ports': 'POTS端口数目',
  'Number of ETH ports': 'ETH端口数目',
  'Number of VDSL ports': 'VDSL端口数目',
  'Number of TDM ports': 'TDM端口数目',
  'Number of MOCA ports': 'MOCA端口数目',
  'Number of CATV ANI ports': 'CATV ANI端口数目',
  'Number of CATV UNI ports': 'CATV UNI端口数目',
  'Number of GEM ports': 'GEM port数目',
  'IP configuration': '是否支持IP配置',
  'Number of traffic Schedulers': '流量控制器数目',
  'Number of T-CONTs': 'TCONT数目',
  'The type of flow control': '流控类型',
  'ONT optical module power control capability': 'ONT光模块电源控制能力',
  'ONT type': 'ONT类型',
  'Extended OMCI message format': '是否支持可变长OMCI消息格式',
  'VoIP configuration method': '语音业务配置方式',
  'VoIP signalling protocol': '语音业务协议类型',
  ETHOAM: 'ETHOAM',
  'Single ring check': '环网单次检测',
  'Multicast encrypt': '组播加密'
}

/** KEY_LABELS 的小写索引：设备回显里 Traffic/Schedulers 大小写不稳定，兜底用 */
const KEY_LABELS_LOWER = {}
Object.keys(KEY_LABELS).forEach(key => {
  KEY_LABELS_LOWER[key.toLowerCase()] = KEY_LABELS[key]
})

/** capability 回显的值也翻：support / not support / 流控类型 / 电源控制能力 */
const CAP_VALUES = {
  support: '支持',
  'not support': '不支持',
  'gemport car and pq': 'GEMPORT CAR和优先级队列',
  'the tx and rx power can be controlled separately': '支持TX和RX独立控制'
}

/** 认证方式的值也顺手翻一下 */
const AUTH_VALUES = {
  'SN-auth': 'SN认证',
  'SN': 'SN认证',
  'MAC-auth': 'MAC认证',
  'MAC': 'MAC认证',
  'password-auth': '密码认证',
  'Password-auth': '密码认证'
}

/** 详情弹窗里的英文状态值 → 中文 */
const INFO_VALUES = {
  online: '在线',
  offline: '离线',
  normal: '正常',
  initial: '初始状态',
  failed: '失败',
  match: '匹配',
  mismatch: '不匹配'
}

/** 业务流 VLAN 属性 → 中文（display service-port 表格的 VLAN ATTR 列） */
const VLAN_ATTRS = {
  common: '通用VLAN',
  translation: '转换VLAN',
  qinq: 'QinQ',
  smart: '智能VLAN'
}

/** 最后一次下线原因的常见英文值，对照现场中文叫法补全说明 */
const DOWN_REASONS = {
  'LOSi/LOBi': 'LOSi/LOBi【光质量差/光纤断】',
  LOSi: 'LOSi【光信号丢失】',
  LOFi: 'LOFi【OLT无法学到ONi序列号】',
  SuFi: 'SuFi【ONi上行流量异常】',
  'dying-gasp': '掉电（Dying-Gasp）'
}

/**
 * MAC 列：OLT 侧只有 MAC 认证（EPON）的认证码本身就是 MAC，GPON 的 SN 认证查不到 MAC，
 * 这里把 12 个十六进制字符的认证码格式化成 aa:bb:cc:dd:ee:ff，其余返回 null（列里显示 -）。
 */
function macText(sn) {
  if (!sn) return null
  const hex = String(sn).split(' ')[0].replace(/[-:.]/g, '')
  if (!/^[0-9A-Fa-f]{12}$/.test(hex)) return null
  return hex.match(/.{2}/g).join(':')
}

/** 在线时长英文格式翻中文：1 day(s), 10 hour(s) → 1天, 10小时 */
function formatDuration(text) {
  return String(text)
    .replace(/(\d+)\s*day\(s\)/g, '$1天')
    .replace(/(\d+)\s*hour\(s\)/g, '$1小时')
    .replace(/(\d+)\s*minute\(s\)/g, '$1分')
    .replace(/(\d+)\s*second\(s\)/g, '$1秒')
}

/** 详情弹窗按字段翻译值：状态/掉线原因/在线时长，其余原样 */
function infoValueText(label, value) {
  const text = String(value == null ? '' : value).trim()
  if (!text) return ''
  if (label === '运行标志' || label === '配置状态' || label === '匹配状态') {
    return INFO_VALUES[text.toLowerCase()] || text
  }
  if (label === '最后下线原因') {
    return DOWN_REASONS[text] || text
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
 * 另外两类也按需查：点 SN / 业务流 / 线路模板 / 业务模板列查的是
 * display current-configuration ont 0/<槽位>/<端口> <ONT ID>——授权行
 * （SN / 线路模板 / 业务模板 / 描述）和这条 ONT 的业务流行一条命令全带回来，
 * 查到的模板号写回本行，线路模板、业务模板两列随后显示成 100 和 100；「详情」图标出
 * display ont info——IP、运行状态、最后一次掉线原因、在线时长在这一份里。
 *
 * 下发 TR069 / 业务流那些写操作是占位，下发能力稍后接入。
 */
export default {
  name: 'OltOntStatusPanel',
  props: {
    /** 板卡折算结果（mixin 的 ontBoards），只列有端口的业务板 */
    ontBoards: {
      type: Array,
      default: () => []
    },
    oltId: {
      type: [Number, String],
      default: null
    },
    emptyText: {
      type: String,
      default: '尚未采集到端口/ONT 数据。点右上角「同步ONT状态」立即采集。'
    }
  },
  data() {
    return {
      stateFilter: '',
      portFilter: '',
      selected: [],
      queryVisible: false,
      queryLoading: false,
      queryAction: '',
      queryCommand: '',
      queryRaw: '',
      queryBlocks: [],
      // 每条 ONT 查过的详情按 槽位/端口/ONT ID 存一份：模板号、IP 只在单条详情里有
      ontInfoMap: {},
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
      spSelected: []
    }
  },
  computed: {
    /** 板卡 × 端口 × ONT 摊平成一行一条 */
    ontRows() {
      const rows = []
      this.ontBoards.forEach(board => {
        board.ports.forEach(port => {
          (port.onts || []).forEach(ont => {
            rows.push({
              key: board.no + '/' + port.no + '/' + ont.id,
              // mixin 折算后的槽位字段是 no（板卡对象），端口是 no，ONT 是 id
              slotNo: board.no,
              portNo: port.no,
              iface: port.iface,
              ontId: ont.id,
              sn: ont.sn,
              desc: ont.desc,
              online: ont.online,
              stateText: ont.stateText,
              configText: ont.configText,
              // GPON 走 SN 认证、EPON 走 MAC 认证，SN 长相就是区分标志
              authMethod: 'sn'
            })
          })
        })
      })
      return rows
    },
    filteredRows() {
      return this.ontRows.filter(row => {
        if (this.stateFilter === 'online' && !row.online) return false
        if (this.stateFilter === 'offline' && row.online) return false
        if (this.portFilter && this.portFilter !== row.slotNo + '/' + row.portNo) return false
        return true
      })
    },
    portOptions() {
      const options = []
      this.ontBoards.forEach(board => {
        board.ports.forEach(port => {
          if (port.onts && port.onts.length) {
            options.push({ value: board.no + '/' + port.no, label: '槽位 ' + board.no + ' - ' + port.iface })
          }
        })
      })
      return options
    },
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
          fields.push({ label, value: infoValueText(label, value) })
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
  methods: {
    /** 行缓存用的 key：槽位/端口/ONT ID */
    cacheKey(row) {
      return row.slotNo + '/' + row.portNo + '/' + row.ontId
    },
    /** 英文键翻中文（先精确后忽略大小写），没对上的原样返回；T-CONT 的 PQ 行是动态键 */
    keyLabel(key) {
      const trimmed = (key || '').trim()
      const pq = trimmed.match(/^Number of PQs in T-CONT\s+(\d+)$/i)
      if (pq) {
        return 'T-CONT ' + pq[1] + ' 的 PQ 数目'
      }
      return KEY_LABELS[trimmed] || KEY_LABELS_LOWER[trimmed.toLowerCase()] || trimmed
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
        if (label === '认证方式' && AUTH_VALUES[value]) {
          value = AUTH_VALUES[value]
        }
        if (CAP_VALUES[value.toLowerCase()]) {
          value = CAP_VALUES[value.toLowerCase()]
        }
        rows.push({ label, value: value || '-' })
      })
      // 全是「-」的占位块（注册历史末尾常见）没有展示价值
      const meaningful = rows.filter(row => row.value !== '-')
      return meaningful.length ? meaningful : []
    },
    /** 线路模板号：没查过这条 ONT 的配置时为空 */
    lineText(row) {
      const info = this.ontInfoMap[this.cacheKey(row)]
      if (!info) return ''
      return info.lineProfileId === null || info.lineProfileId === undefined ? '' : String(info.lineProfileId)
    },
    /** 业务模板号：没查过这条 ONT 的配置时为空 */
    srvText(row) {
      const info = this.ontInfoMap[this.cacheKey(row)]
      if (!info) return ''
      return info.srvProfileId === null || info.srvProfileId === undefined ? '' : String(info.srvProfileId)
    },
    /** 管理 IP：整板回显里没有，点「详情」查过 display ont info 后回填本行 */
    ipOf(row) {
      const info = this.ontInfoMap[this.cacheKey(row)]
      return info ? info.ip : null
    },
    /** MAC：只有 MAC 认证的行能从认证码里还原，其余显示 - */
    macOf(row) {
      return macText(row.sn)
    },
    /** VLAN 属性翻中文，没对上的原样 */
    vlanAttrText(attr) {
      if (!attr) return '-'
      const text = String(attr).trim().toLowerCase()
      return VLAN_ATTRS[text] || attr
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
     * 点 SN 打开详情：display ont info，IP / 模板 / 掉线原因都在这份里。
     * 查到的模板号写回行缓存，模板列随后就显示成 100:100。
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
          if (this.infoData) {
            this.$set(this.ontInfoMap, this.cacheKey(row), this.infoData)
          }
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
     * 查到的模板号写回行缓存，模板列随后显示成 100:100。
     */
    openOntConfig(row) {
      if (this.oltId == null) return
      if (!this.checkRow(row)) return
      this.cfgRow = row
      this.cfgVisible = true
      this.loadOntConfig(row)
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
          if (this.cfgData) {
            this.$set(this.ontInfoMap, this.cacheKey(row), this.cfgData)
          }
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
</style>
