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
        <template slot-scope="{ row }">{{ row.ontId }}</template>
      </el-table-column>
      <el-table-column label="配置状态" width="170">
        <template slot-scope="{ row }">
          <span class="ont-auth">{{ row.authMethod }}</span>
          <div class="ont-config-line">
            <span class="ont-config">{{ row.configText || '-' }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="认证码 (SN)" min-width="180">
        <template slot-scope="{ row }">
          <div class="ont-sn">{{ row.sn }}</div>
          <div class="ont-sub">{{ row.desc }}</div>
        </template>
      </el-table-column>
      <el-table-column label="线路业务模板/业务流" width="130" align="center">
        <template>
          <span class="ont-sub">-</span>
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
      <el-table-column label="查询" width="120" align="center">
        <template slot-scope="{ row }">
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

    <!-- 按需查询弹窗：解析成 key-value 表格 + 原始回显 -->
    <el-dialog :title="queryTitle" :visible.sync="queryVisible" width="760px" append-to-body>
      <div v-loading="queryLoading" class="ont-query-body">
        <template v-if="queryBlocks.length">
          <el-table :data="queryBlocks" size="small" border>
            <el-table-column v-for="key in queryKeys" :key="key" :label="key" min-width="150">
              <template slot-scope="{ row }">{{ row[key] || '-' }}</template>
            </el-table-column>
          </el-table>
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
  </div>
</template>

<script>
import { queryOnt } from '@/api/olt'

const ACTION_TITLES = {
  optical: '光信息',
  version: '版本信息',
  register: '注册历史'
}

/**
 * ONT 配置/状态主表：每条 ONT 一行（板卡 × 端口 × ONT 摊平），带筛选与按需查询。
 *
 * 数据来自轮询采集（display ont info 0 <槽位> all）；光信息、版本、注册历史这三类
 * 是块状明细，几百条 ONT 逐个采不现实，点行上的图标时才登录设备查一次
 * （display ont optical-info / version / register-info），弹窗里铺成表格。
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
      queryBlocks: []
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
              key: board.slotNo + '/' + port.no + '/' + ont.id,
              slotNo: board.slotNo,
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
            options.push({ value: board.slotNo + '/' + port.no, label: '槽位 ' + board.slotNo + ' - ' + port.iface })
          }
        })
      })
      return options
    },
    queryKeys() {
      const keys = []
      this.queryBlocks.forEach(block => {
        Object.keys(block).forEach(key => {
          if (keys.indexOf(key) < 0) {
            keys.push(key)
          }
        })
      })
      return keys
    },
    queryTitle() {
      return 'ONT ' + (ACTION_TITLES[this.queryAction] || '信息')
    }
  },
  methods: {
    /** 点图标按需查一次：登录设备进 GPON 端口视图发命令，块状解析后铺表格 */
    openQuery(action, row) {
      if (this.oltId == null) return
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
