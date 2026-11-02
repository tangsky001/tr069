<template>
  <div>
    <div v-if="!vlanList.length" class="olt-empty">{{ emptyText }}</div>

    <template v-else>
      <div v-if="editCount" class="vlan-edit-banner">
        三层接口有 {{ editCount }} 处本地修改，尚未下发设备；重新打开页面后会还原成采集值
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
          <template slot-scope="{ row }">
            <el-button type="text" size="mini" @click="openPortVlanAdd(row)">添加</el-button>
            <el-button type="text" size="mini" class="vlan-row-danger" @click="openPortVlanRemove(row)">删除</el-button>
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

      <!-- 4. 静态路由：只读展示采集结果，页面不提供增删改 -->
      <div class="vlan-section-head">
        <div class="olt-section-title">静态路由</div>
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
      </el-table>
      <div v-else class="vlan-empty-row">没有静态路由</div>

      <!-- 对话框：三层接口是本地暂存；上行 VLAN 透传是真实下发，成功后立刻重读 VLAN -->
      <el-dialog
        :title="dialog.title"
        :visible.sync="dialog.visible"
        width="460px"
        append-to-body
      >
        <!-- 上行口移除 VLAN：明确写清要动哪个口、哪些 VLAN -->
        <div v-if="dialog.type === 'portVlan' && dialog.mode === 'delete'" class="vlan-dialog-tip">
          将从 <b>{{ dialog.form.iface }}</b> 解除 VLAN
          <b>{{ dialog.form.vlanText || '（无）' }}</b> 的绑定（下发 no port vlan），
          设备上立即生效，随后立刻重读所有 VLAN。
        </div>
        <div v-else-if="dialog.mode === 'delete'" class="vlan-dialog-tip">
          确认删除这一条吗？删除先暂存在页面本地，下发设备的能力稍后接入。
        </div>
        <template v-else-if="dialog.type === 'ipif'">
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
        <!-- 上行 VLAN 透传：放通 VLAN（真实下发） -->
        <template v-else>
          <div class="vlan-form-row">
            <span class="vlan-form-label">机框/板卡/端口</span>
            <el-input
              v-model="dialog.form.iface"
              size="small"
              placeholder="如 0/7/0，多个口用逗号分隔"
              :disabled="dialog.lockIface"
            />
          </div>
          <div class="vlan-form-row">
            <span class="vlan-form-label">VLAN</span>
            <el-input v-model="dialog.form.vlanText" size="small" placeholder="如 41,45-46（支持区间）" />
          </div>
          <div class="vlan-form-row">
            <span class="vlan-form-label">Native</span>
            <el-checkbox v-model="dialog.form.nativeVlan">作为 Native VLAN（不打标签）</el-checkbox>
          </div>
          <div class="vlan-dialog-tip">
            先 <code>vlan &lt;VLAN&gt; smart</code> 建出 VLAN（已存在的自动跳过），
            再 <code>port vlan &lt;VLAN&gt; &lt;框/槽&gt; &lt;端口&gt;</code> 放通（多个口逐口各发一条）；
            成功后立刻重读所有 VLAN。
          </div>
        </template>
        <div slot="footer" class="vlan-dialog-foot">
          <el-button size="small" @click="dialog.visible = false">取消</el-button>
          <el-button size="small" type="success" :loading="submitting" @click="confirmDialog">{{ dialog.action }}</el-button>
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
 * 上行透传是真实下发：表头「新增 VLAN 透传」和每行「添加」都走对话框，
 * 确认后由父页面调 {@code POST /api/olt/{id}/vlan/port-vlan} 下发
 * {@code port vlan <VLAN> <框/槽> <端口>}，后端下发成功后立刻只重读 VLAN，
 * 页面换上新快照；每行「删除」同理，下发 {@code no port vlan ...}。
 *
 * 三层接口的编辑还是预留的：确认后只把修改暂存在页面本地（savedEdits），
 * 并没有写回设备——下发链路以后接，到时候把 confirmDialog 里的暂存逻辑换成调
 * 后端接口即可。
 *
 * 静态路由只读：路由是人手一条条配上去的，页面上不提供增删改。
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
    },
    /** 上行口放通 VLAN（真实下发）：父页面传进来，下发成功后父页面换上新快照 */
    addPortVlanHandler: {
      type: Function,
      default: null
    },
    /** 上行口解除 VLAN 绑定（真实下发） */
    removePortVlanHandler: {
      type: Function,
      default: null
    }
  },
  data() {
    return {
      /** 三层接口的本地暂存（只有这张表还是页面本地改） */
      savedEdits: null,
      /** 上行口 VLAN 下发中：按钮转圈，防止连点重复下发 */
      submitting: false,
      dialog: {
        visible: false,
        type: 'ipif',
        mode: 'edit',
        index: -1,
        action: '',
        title: '',
        /** 端口是这一行带进来的就锁住，别让人顺手改到别的口上 */
        lockIface: false,
        form: {}
      }
    }
  },
  computed: {
    ipifRows() {
      return this.savedEdits ? this.savedEdits.ipifs : this.vlanIpInterfaces
    },
    /** 透传表直接展示采集值：新增/删除都真实下发，成功后父页面重读 VLAN */
    portVlanRows() {
      return this.portVlans
    },
    /** 静态路由只读，不参与本地暂存 */
    routeRows() {
      return this.staticRoutes
    },
    editCount() {
      return this.savedEdits ? this.savedEdits.ipifs.length : 0
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
    /** 三层接口的暂存底稿：第一次改的时候从采集快照拷出来 */
    ensureSaved() {
      if (!this.savedEdits) {
        this.savedEdits = {
          ipifs: this.vlanIpInterfaces.map(row => ({ ...row }))
        }
      }
      return this.savedEdits
    },
    openAdd(type) {
      const form = this.blankForm(type)
      // 新增 VLAN 透传：默认把采到的上行口全填上（可改成单个口，多个口用逗号分隔）
      if (type === 'portVlan') {
        const ifaces = this.portVlanRows.map(row => row.iface).filter(Boolean)
        form.iface = ifaces.join(',')
      }
      this.dialog = {
        visible: true,
        type,
        mode: 'add',
        index: -1,
        lockIface: false,
        action: this.actionText(type, 'add'),
        title: this.sectionLabel(type) + ' · 新增',
        form
      }
    },
    openEdit(type, row, index) {
      this.dialog = {
        visible: true,
        type,
        mode: 'edit',
        index,
        lockIface: false,
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
        lockIface: false,
        action: this.actionText(type, 'delete'),
        title: this.sectionLabel(type) + ' · 删除',
        form: { ...row }
      }
    },
    /** 某一行的「添加」：口已经定了，只填要放通的 VLAN */
    openPortVlanAdd(row) {
      this.dialog = {
        visible: true,
        type: 'portVlan',
        mode: 'add',
        index: -1,
        lockIface: true,
        action: '添加 VLAN',
        title: '上行口 ' + row.iface + ' · 添加 VLAN',
        form: this.blankForm('portVlan')
      }
      this.dialog.form.iface = row.iface
    },
    /** 某一行的「删除」：把这行已放通的 VLAN 从这个口上解绑 */
    openPortVlanRemove(row) {
      const vlanText = this.portVlanText(row)
      this.dialog = {
        visible: true,
        type: 'portVlan',
        mode: 'delete',
        index: -1,
        lockIface: true,
        action: '移除 VLAN',
        title: '上行口 ' + row.iface + ' · 移除 VLAN',
        form: { iface: row.iface, vlanText, nativeVlan: false }
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
      return { ipif: '三层接口', portVlan: 'VLAN 透传' }[type] || '条目'
    },
    blankForm(type) {
      if (type === 'ipif') {
        return { vlanId: '', ipAddress: '', mask: '', name: '' }
      }
      return { iface: '', vlanText: '', nativeVlan: false }
    },
    confirmDialog() {
      const { type, mode, index, form } = this.dialog
      // 上行 VLAN 透传：真实下发，成功后面板由父页面换上新快照
      if (type === 'portVlan') {
        this.submitPortVlan(mode, form)
        return
      }
      // 三层接口：仍是本地暂存（下发链路以后接）
      const rows = this.ensureSaved().ipifs
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
    },
    /**
     * 上行口 VLAN 的真实下发：add → port vlan ...，delete → no port vlan ...。
     *
     * <p>后端下发成功后立刻只重读 VLAN，返回的就是最新快照，父页面换上后
     * 这张表当场变成设备上的真实数据（不用再点「同步」）。</p>
     */
    submitPortVlan(mode, form) {
      const iface = String(form.iface || '').trim()
      const vlanIds = String(form.vlanText || '').trim()
      if (!iface) {
        this.$message.warning('先填机框/板卡/端口，例如 0/7/0')
        return
      }
      if (!vlanIds) {
        this.$message.warning('先填 VLAN，例如 41,45-46')
        return
      }
      const handler = mode === 'delete' ? this.removePortVlanHandler : this.addPortVlanHandler
      if (typeof handler !== 'function') {
        this.$message.warning('当前页面没有接上行口 VLAN 下发')
        return
      }
      this.submitting = true
      Promise.resolve(handler({ iface, vlanIds, nativeVlan: !!form.nativeVlan }))
        .then(() => {
          this.dialog.visible = false
        })
        .catch(() => {})
        .finally(() => {
          this.submitting = false
        })
    }
  }
}
</script>

<style lang="scss" scoped>
@import '~@/styles/olt.scss';
</style>
