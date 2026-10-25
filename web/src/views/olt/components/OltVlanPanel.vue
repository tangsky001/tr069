<template>
  <div>
    <div v-if="!vlanList.length" class="olt-empty">{{ emptyText }}</div>

    <template v-else>
      <div v-if="editCount" class="vlan-edit-banner">
        有 {{ editCount }} 处本地修改，尚未下发设备；重新打开页面后会还原成采集值
      </div>

      <!-- 1. 三层 VLAN 接口：vlanif 对应的 IP 和掩码 -->
      <div class="vlan-section-head">
        <div class="olt-section-title">三层 VLAN 接口</div>
        <el-button size="mini" type="success" icon="el-icon-plus" @click="openAdd('ipif')">新增</el-button>
      </div>
      <el-table v-if="ipifRows.length" :data="ipifRows" size="small" border>
        <el-table-column label="VLAN ID" width="100" align="center">
          <template slot-scope="{ row }">{{ row.vlanId }}</template>
        </el-table-column>
        <el-table-column label="VLAN IP 地址" width="180">
          <template slot-scope="{ row }">{{ row.ipAddress }}</template>
        </el-table-column>
        <el-table-column label="子网掩码" width="180">
          <template slot-scope="{ row }">{{ row.mask }}</template>
        </el-table-column>
        <el-table-column label="接口" prop="name" width="140" />
        <el-table-column label="状态">
          <template slot-scope="{ row }">
            {{ row.physical }} / {{ row.protocol }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" align="center">
          <template slot-scope="{ row, $index }">
            <el-button type="text" size="mini" @click="openEdit('ipif', row, $index)">编辑</el-button>
            <el-button type="text" size="mini" class="vlan-row-danger" @click="openDelete('ipif', row, $index)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-else class="vlan-empty-row">该设备没有配置三层 VLAN 接口（vlanif）</div>

      <!-- 2. 上行 VLAN 透传：每个上行口放通了哪些 VLAN -->
      <div class="vlan-section-head">
        <div class="olt-section-title">上行 VLAN 透传</div>
        <el-button size="mini" type="success" icon="el-icon-plus" @click="openAdd('portVlan')">新增 VLAN 透传</el-button>
      </div>
      <el-table :data="portVlanRows" size="small" border>
        <el-table-column label="机框/板卡/端口" width="180">
          <template slot-scope="{ row }">{{ row.iface }}</template>
        </el-table-column>
        <el-table-column label="透传 VLAN">
          <template slot-scope="{ row }">
            <span v-if="portVlanText(row)" class="vlan-text">{{ portVlanText(row) }}</span>
            <span v-else class="vlan-tip-inline">无</span>
          </template>
        </el-table-column>
        <el-table-column label="Native VLAN" width="130" align="center">
          <template slot-scope="{ row }">{{ row.nativeVlan == null ? '-' : row.nativeVlan }}</template>
        </el-table-column>
        <el-table-column label="操作" width="120" align="center">
          <template slot-scope="{ row, $index }">
            <el-button type="text" size="mini" @click="openEdit('portVlan', row, $index)">编辑</el-button>
            <el-button type="text" size="mini" class="vlan-row-danger" @click="openDelete('portVlan', row, $index)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 3. VLAN / 二层互通：按类型合并的汇总视图，只读 -->
      <div class="vlan-section-head">
        <div class="olt-section-title">VLAN / 二层互通</div>
      </div>
      <el-table :data="vlanGroupRows" size="small" border>
        <el-table-column label="VLAN 类型" prop="label" width="280" />
        <el-table-column label="VLAN ID">
          <template slot-scope="{ row }">
            <span class="vlan-text">{{ row.text }}</span>
          </template>
        </el-table-column>
        <el-table-column label="标准口数" width="110" align="center">
          <template slot-scope="{ row }">{{ row.std == null ? '-' : row.std }}</template>
        </el-table-column>
        <el-table-column label="业务流数" width="110" align="center">
          <template slot-scope="{ row }">{{ row.serv == null ? '-' : row.serv }}</template>
        </el-table-column>
      </el-table>

      <!-- 4. 静态路由 -->
      <div class="vlan-section-head">
        <div class="olt-section-title">静态路由</div>
        <el-button size="mini" type="success" icon="el-icon-plus" @click="openAdd('route')">新增静态路由</el-button>
      </div>
      <el-table v-if="routeRows.length" :data="routeRows" size="small" border>
        <el-table-column label="目的网络" width="200">
          <template slot-scope="{ row }">{{ row.destination }}</template>
        </el-table-column>
        <el-table-column label="下一跳网关" width="200">
          <template slot-scope="{ row }">{{ row.nextHop }}</template>
        </el-table-column>
        <el-table-column label="出接口" width="160">
          <template slot-scope="{ row }">{{ row.iface }}</template>
        </el-table-column>
        <el-table-column label="优先级" width="100" align="center">
          <template slot-scope="{ row }">{{ row.preference == null ? '-' : row.preference }}</template>
        </el-table-column>
        <el-table-column label="操作" width="120" align="center">
          <template slot-scope="{ row, $index }">
            <el-button type="text" size="mini" @click="openEdit('route', row, $index)">编辑</el-button>
            <el-button type="text" size="mini" class="vlan-row-danger" @click="openDelete('route', row, $index)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-else class="vlan-empty-row">没有静态路由</div>

      <!-- 单条编辑 / 新增 / 删除 对话框：确认按钮绿色，修改暂存本地，下发设备的能力以后接 -->
      <el-dialog
        :title="dialog.title"
        :visible.sync="dialog.visible"
        width="460px"
        append-to-body
      >
        <div v-if="dialog.mode === 'delete'" class="vlan-dialog-tip">
          确认删除这一条吗？删除先暂存在页面本地，下发设备的能力稍后接入。
        </div>
        <template v-else>
          <!-- 三层 VLAN 接口 -->
          <template v-if="dialog.type === 'ipif'">
            <div class="vlan-form-row">
              <span class="vlan-form-label">VLAN ID</span>
              <el-input v-model="dialog.form.vlanId" size="small" placeholder="2~4094" />
            </div>
            <div class="vlan-form-row">
              <span class="vlan-form-label">VLAN IP 地址</span>
              <el-input v-model="dialog.form.ipAddress" size="small" placeholder="如 10.172.10.254" />
            </div>
            <div class="vlan-form-row">
              <span class="vlan-form-label">子网掩码</span>
              <el-input v-model="dialog.form.mask" size="small" placeholder="如 255.255.255.0" />
            </div>
          </template>
          <!-- 上行 VLAN 透传 -->
          <template v-else-if="dialog.type === 'portVlan'">
            <div class="vlan-form-row">
              <span class="vlan-form-label">机框/板卡/端口</span>
              <el-input v-model="dialog.form.iface" size="small" placeholder="如 0/8/1" :disabled="dialog.mode === 'edit'" />
            </div>
            <div class="vlan-form-row">
              <span class="vlan-form-label">VLAN ID 号</span>
              <el-input v-model="dialog.form.vlanText" size="small" placeholder="VLAN ID号，范围2~4094，多个VLAN用逗号分隔" />
            </div>
            <div class="vlan-form-row">
              <span class="vlan-form-label">Native VLAN</span>
              <el-input v-model="dialog.form.nativeVlan" size="small" placeholder="留空表示没有" />
            </div>
          </template>
          <!-- 静态路由 -->
          <template v-else>
            <div class="vlan-form-row">
              <span class="vlan-form-label">目的网络</span>
              <el-input v-model="dialog.form.destination" size="small" placeholder="如 0.0.0.0/0" />
            </div>
            <div class="vlan-form-row">
              <span class="vlan-form-label">下一跳网关</span>
              <el-input v-model="dialog.form.nextHop" size="small" placeholder="如 10.172.10.1" />
            </div>
            <div class="vlan-form-row">
              <span class="vlan-form-label">出接口</span>
              <el-input v-model="dialog.form.iface" size="small" placeholder="如 vlanif46" />
            </div>
            <div class="vlan-form-row">
              <span class="vlan-form-label">优先级</span>
              <el-input v-model="dialog.form.preference" size="small" placeholder="如 60" />
            </div>
          </template>
        </template>
        <div slot="footer" class="vlan-dialog-foot">
          <el-button size="small" @click="dialog.visible = false">取消</el-button>
          <el-button size="small" type="success" @click="confirmDialog">{{ dialog.action }}</el-button>
        </div>
      </el-dialog>
    </template>
  </div>
</template>

<script>
import { compressVlans } from '../utils'

/**
 * VLAN 面板：三层接口、上行透传、二层互通、静态路由四张表。
 *
 * 数据全是登录后采集到的快照（不是页面下发的结果），这四张表对各厂商页面都适用。
 *
 * 编辑是预留的、按条独立操作：每个区块「新增」（+），每行「编辑 / 删除」，都走对话框，
 * 确认后只把修改暂存在页面本地（savedEdits，暂存后的行优先于采集值展示），并没有写回
 * 设备——下发命令的链路以后接，到时候把 confirmDialog 里的暂存逻辑换成调后端接口即可。
 */
export default {
  name: 'OltVlanPanel',
  props: {
    vlanList: {
      type: Array,
      default: () => []
    },
    portVlans: {
      type: Array,
      default: () => []
    },
    vlanIpInterfaces: {
      type: Array,
      default: () => []
    },
    staticRoutes: {
      type: Array,
      default: () => []
    },
    vlanGroupRows: {
      type: Array,
      default: () => []
    },
    emptyText: {
      type: String,
      default: '尚未采集到 VLAN 信息，点上面的「同步」立即刷新。'
    }
  },
  data() {
    return {
      /** 本地暂存的修改：每张表一份完整行数组（暂存后优先于采集值展示） */
      savedEdits: null,
      dialog: {
        visible: false,
        type: 'ipif',
        mode: 'edit',
        index: -1,
        action: '',
        title: '',
        form: {}
      }
    }
  },
  computed: {
    ipifRows() {
      return this.savedEdits ? this.savedEdits.ipifs : this.vlanIpInterfaces
    },
    portVlanRows() {
      return this.savedEdits ? this.savedEdits.portVlans : this.portVlans
    },
    routeRows() {
      return this.savedEdits ? this.savedEdits.routes : this.staticRoutes
    },
    editCount() {
      const saved = this.savedEdits
      if (!saved) {
        return 0
      }
      return saved.ipifs.length + saved.portVlans.length + saved.routes.length
    },
    dialogTitle() {
      return this.dialog.title
    }
  },
  methods: {
    compressVlans,
    portVlanText(row) {
      if (row.vlanText != null) {
        return row.vlanText
      }
      return row.vlans && row.vlans.length ? compressVlans(row.vlans) : ''
    },
    /** 三张表的暂存底稿：第一次改的时候从采集快照拷出来 */
    ensureSaved() {
      if (this.savedEdits) {
        return this.savedEdits
      }
      this.savedEdits = {
        ipifs: this.vlanIpInterfaces.map(row => ({ ...row })),
        portVlans: this.portVlans.map(row => ({
          ...row,
          vlanText: this.portVlanText(row)
        })),
        routes: this.staticRoutes.map(row => ({ ...row }))
      }
      return this.savedEdits
    },
    openAdd(type) {
      this.dialog = {
        visible: true,
        type,
        mode: 'add',
        index: -1,
        action: this.actionText(type, 'add'),
        title: this.sectionLabel(type) + ' · 新增',
        form: this.blankForm(type)
      }
    },
    openEdit(type, row, index) {
      this.dialog = {
        visible: true,
        type,
        mode: 'edit',
        index,
        action: this.actionText(type, 'edit'),
        title: this.sectionLabel(type) + ' · 编辑',
        form: { ...row }
      }
    },
    openDelete(type, row, index) {
      this.dialog = {
        visible: true,
        type,
        mode: 'delete',
        index,
        action: this.actionText(type, 'delete'),
        title: this.sectionLabel(type) + ' · 删除',
        form: { ...row }
      }
    },
    actionText(type, mode) {
      const label = this.sectionLabel(type)
      if (mode === 'add') {
        return '新增 ' + label
      }
      if (mode === 'delete') {
        return '删除 ' + label
      }
      return '保存修改'
    },
    sectionLabel(type) {
      return { ipif: '三层接口', portVlan: 'VLAN 透传', route: '静态路由' }[type] || '条目'
    },
    blankForm(type) {
      if (type === 'ipif') {
        return { vlanId: '', ipAddress: '', mask: '', name: '' }
      }
      if (type === 'portVlan') {
        return { iface: '', vlanText: '', nativeVlan: '' }
      }
      return { destination: '', nextHop: '', iface: '', preference: '' }
    },
    confirmDialog() {
      const { type, mode, index, form } = this.dialog
      const rows = this.ensureSaved()[{ ipif: 'ipifs', portVlan: 'portVlans', route: 'routes' }[type]]
      if (mode === 'delete') {
        rows.splice(index, 1)
      } else if (mode === 'add') {
        rows.push({ ...form })
      } else {
        rows.splice(index, 1, { ...form })
      }
      this.dialog.visible = false
      this.$message.info('修改已暂存在页面本地，尚未下发设备')
      this.$emit('save', this.savedEdits)
    }
  }
}
</script>

<style lang="scss" scoped>
@import '~@/styles/olt.scss';
</style>
