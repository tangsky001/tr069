<template>
  <div :class="{ 'app-container': !embedded }">
    <!-- 嵌在台账页里时上面已经有下拉和按钮了，这里只出内容区 -->
    <olt-device-bar
      v-if="!embedded"
      :device="device"
      :status-text="statusText"
      :status-tag="statusTag"
      :firmware-text="firmwareText"
      :syncing="syncing"
      :testing="testing"
      :auto-refresh="autoRefresh"
      :poll-seconds="pollSeconds"
      @back="goBack"
      @edit="openEdit"
      @sync="handleSync()"
      @test="handleTest"
      @delete="handleDelete"
      @update:autoRefresh="autoRefresh = $event"
      @update:pollSeconds="pollSeconds = $event"
    />

    <el-card v-if="hasDevice" shadow="never" class="olt-main-card">
      <div class="olt-tip">
        鸿升光是一体化设备，没有板卡概念：认出厂商后按型号生成整机卡片，端口固定 4 个 PON 口。
        采集时进配置视图（<code>enable</code> → <code>config</code>）跑
        <code>show onu-info all</code>、<code>show pon-info</code> 与
        <code>show running-config</code>，把每口的 ONU 在线数、每条 ONU 的 MAC 与在线状态、
        VLAN（含成员端口）补上去。VLAN 页签里可以直接新增 <code>vlan standard</code>、
        勾选删除 <code>no vlan</code>，下发成功后自动重采。
      </div>

      <el-tabs v-model="activeTab">
        <el-tab-pane label="设备概览" name="spec">
          <div class="olt-summary">
            <div>
              厂商：<b>{{ device.vendorLabel || '鸿升光' }}</b>；型号：<b>{{ device.model || '-' }}</b>；
              软件版本：{{ device.softwareVersion || '-' }}
            </div>
            <div>最近连接：{{ device.lastConnectAt || '从未成功连接' }}；最近同步：{{ device.lastSyncAt || '未同步' }}</div>
            <div>{{ device.syncSummary || '尚未同步，点击「同步」采集' }}</div>
            <!-- 数据以 OLT 为准：打开页面直连采一次，采不到才退回库里的快照 -->
            <div class="olt-live" :class="{ 'olt-live-error': liveError }">{{ liveText }}</div>
          </div>

          <div class="olt-section-title">
            整机信息
            <span v-if="boardSummary" class="board-summary">{{ boardSummary }}</span>
          </div>

          <!-- 整机不是板卡：一台设备画一块前面板，PON 口 + 上联 GE/XGE + NMS/CONSOLE -->
          <olt-hsgq-faceplate
            v-if="slots.length"
            :board="slots[0]"
            :model="device.model"
            :expanded-onts="expandedOnts"
            :hover-port="hoverPort"
            @toggle="onToggleOnts"
            @hover="hoverOn"
            @leave="hoverOff"
          />
          <div v-else class="olt-empty">尚无整机数据。点击「同步」登录设备采集。</div>
        </el-tab-pane>

        <el-tab-pane label="PON 口状态" name="pon">
          <div class="olt-summary">
            <div>
              PON 口 <b>{{ ponPorts.length }}</b> 个；每口的 ONU 在线数来自
              <code>show pon-info</code>，展开端口或去「ONU 列表」看每条 ONU（<code>show onu-info all</code>）。
            </div>
          </div>

          <div v-if="!ponPorts.length" class="olt-empty">尚未采集到 PON 口数据，点上面的「同步」立即刷新。</div>

          <el-table v-else :data="ponPorts" size="small" border>
            <el-table-column label="PON 口" prop="name" width="120" />
            <el-table-column label="接口" prop="iface" width="160" />
            <el-table-column label="模式" prop="mode" width="120" />
            <el-table-column label="ONU 在线" width="120" align="center">
              <template slot-scope="{ row }">
                <span v-if="row.onuTotal != null">{{ row.onuOnline }} / {{ row.onuTotal }}</span>
                <span v-else class="ont-sub-empty">未采集</span>
              </template>
            </el-table-column>
            <el-table-column label="光模块收光" width="120" align="center">
              <template slot-scope="{ row }">
                <span v-if="row.hasOptic">{{ row.rxPower || '--' }}</span>
                <span v-else class="ont-sub-empty">未采集</span>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="ONU 列表" name="onu">
          <!-- show onu-info all 采回来的 ONU：一条一行全平铺出来，按 PON 口排序 -->
          <olt-ont-list
            :ont-boards="ontBoards"
            sn-label="MAC 地址"
            empty-text="尚未采集到 ONU 数据。点上面的「同步」，采集时会跑 show onu-info all。"
          />
        </el-tab-pane>

        <el-tab-pane label="配置信息" name="config">
          <olt-device-info
            :device="device"
            :firmware-text="firmwareText"
            :board-summary="boardSummary"
            :web-account-text="webAccountText"
          />
        </el-tab-pane>

        <el-tab-pane label="VLAN" name="vlan">
          <olt-hsgq-vlan-panel
            :olt-id="detailId"
            :vlans="vlanList"
            :member-ports="memberPorts"
            :create-handler="createVlan"
            :delete-handler="deleteVlan"
          />
        </el-tab-pane>

        <el-tab-pane label="TR069模板" name="tr069" lazy>
          <!-- 不落库：进页签就实时读设备，增加是当场下发 onu-tr069-profile add -->
          <olt-hsgq-tr069-panel :olt-id="detailId" />
        </el-tab-pane>

        <el-tab-pane label="设备配置" name="commands">
          <olt-command-center
            :olt-id="detailId"
            :actions="commandActions"
            :exec-handler="execAction"
            :ports="memberPorts"
            :setup-handler="handleQuickSetup"
          />
        </el-tab-pane>

        <el-tab-pane label="采集记录" name="records">
          <olt-records :records="records" @refresh="loadRecords" />
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <olt-form-dialog
      :visible.sync="formVisible"
      :olt="editing"
      @saved="handleSaved"
    />
  </div>
</template>

<script>
import OltDeviceBar from './components/OltDeviceBar'
import OltDeviceInfo from './components/OltDeviceInfo'
import OltHsgqFaceplate from './components/OltHsgqFaceplate'
import OltOntList from './components/OltOntList'
import OltHsgqVlanPanel from './components/OltHsgqVlanPanel'
import OltHsgqTr069Panel from './components/OltHsgqTr069Panel'
import OltCommandCenter from './components/OltCommandCenter'
import OltRecords from './components/OltRecords'
import OltFormDialog from './components/OltFormDialog'
import { oltDetailMixin } from './mixins/oltDetail'
import { detailRoute } from './vendors'
import { deleteOlt } from '@/api/olt'

/**
 * 鸿升光 HSGQ-E04 等 EPON 一体化 OLT 详情页。
 *
 * 命令风格是 show 那一套（show version / show pon-info），提示符是 OLT> / OLT(config)#，
 * 进配置视图的命令是 configure，和华为完全不是一路，所以单独一个页面。
 */
export default {
  name: 'OltHsgq',
  components: {
    OltDeviceBar,
    OltDeviceInfo,
    OltHsgqFaceplate,
    OltOntList,
    OltHsgqVlanPanel,
    OltHsgqTr069Panel,
    OltCommandCenter,
    OltRecords,
    OltFormDialog
  },
  mixins: [oltDetailMixin],
  props: {
    /** 嵌在台账页里时由外面给（下拉选中的那台）；不传就取路由上的 id */
    deviceId: {
      type: [String, Number],
      default: null
    },
    /** 台账页内嵌：不画自己的顶部设备条，也不带 app-container 的外边距 */
    embedded: {
      type: Boolean,
      default: false
    }
  },
  data() {
    return {
      activeTab: 'spec',
      formVisible: false,
      editing: null
    }
  },
  computed: {
    detailId() {
      return this.deviceId != null ? String(this.deviceId) : this.$route.params.id
    },
    /** 整机就一块「板」，把它的端口摊平成 PON 口列表 */
    ponPorts() {
      if (!this.slots.length) return []
      return this.slots[0].ports.map(port => ({
        name: port.name,
        iface: port.iface,
        mode: this.slots[0].portMode || '-',
        onuTotal: port.onuTotal == null ? null : port.onuTotal,
        onuOnline: port.onuOnline || 0,
        rxPower: port.rxPower,
        hasOptic: port.hasOptic
      }))
    }
  },
  watch: {
    detailId: {
      immediate: true,
      handler(id) {
        this.expandedOnts = {}
        this.loadDetail(id)
      }
    }
  },
  methods: {
    goBack() {
      this.$router.push({ name: 'OltManagement' })
    },
    openEdit() {
      this.editing = this.device
      this.formVisible = true
    },
    handleSaved(saved) {
      this.formVisible = false
      if (saved && String(saved.id) === String(this.detailId)) {
        this.loadDetail(saved.id)
        this.$router.replace(detailRoute(saved.vendor, saved.id))
        return
      }
      this.$router.push(detailRoute(saved && saved.vendor, saved && saved.id))
    },
    onToggleOnts(payload) {
      this.toggleOnts(payload.slotNo, payload.portNo, payload.count)
    },
    handleDelete() {
      const name = this.device.name || this.device.host
      this.$confirm('删除后该 OLT 的板卡与采集记录将一并清理，是否继续？', '删除 OLT：' + name, {
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        type: 'warning'
      })
        .then(() => deleteOlt(this.detailId))
        .then(() => {
          this.$message.success('已删除')
          this.goBack()
        })
        .catch(() => {})
    }
  }
}
</script>

<style lang="scss" scoped>
@import '~@/styles/olt.scss';

.olt-tip {
  margin-bottom: 12px;
  padding: 8px 12px;
  background: #fdf6ec;
  border-radius: 4px;
  color: #e6a23c;
  font-size: 13px;
  line-height: 1.8;
}
</style>
