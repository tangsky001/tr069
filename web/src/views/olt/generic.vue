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
      <!-- 还没认出来先说清楚：不是没采集，是回显里没有厂商关键字 -->
      <div v-if="!device.vendorLabel" class="olt-tip">
        这台设备还没识别出厂商（<code>display version</code> / <code>show version</code> 的回显里没有厂商关键字），
        先按通用视图展示已采到的数据。点上面的「诊断测试」重新认一次，认出来后会自动跳到对应厂商的页面。
      </div>

      <el-tabs v-model="activeTab">
        <el-tab-pane label="OLT 状态" name="spec">
          <div class="olt-summary">
            <div>
              厂商：<b>{{ device.vendorLabel || '未识别' }}</b>；型号：<b>{{ device.model || '-' }}</b>；
              背板：{{ device.backplane || '-' }}；软件版本：{{ firmwareText }}
            </div>
            <div>最近连接：{{ device.lastConnectAt || '从未成功连接' }}；最近同步：{{ device.lastSyncAt || '未同步' }}</div>
            <div>{{ device.syncSummary || '尚未同步，点击「同步」采集板卡信息' }}</div>
            <!-- 数据以 OLT 为准：打开页面直连采一次，采不到才退回库里的快照 -->
            <div class="olt-live" :class="{ 'olt-live-error': liveError }">{{ liveText }}</div>
          </div>

          <div class="olt-section-title">
            板卡信息
            <span v-if="boardSummary" class="board-summary">{{ boardSummary }}</span>
          </div>

          <olt-hsgq-faceplate
            v-if="isHsgq && slots.length"
            :board="slots[0]"
            :model="device.model"
            :expanded-onts="expandedOnts"
            :hover-port="hoverPort"
            empty-text="尚无板卡数据。点击「同步」登录设备采集。"
            @toggle="onToggleOnts"
            @hover="hoverOn"
            @leave="hoverOff"
          />
          <olt-board-cards
            v-else
            :slots="slots"
            :slot-count="chassisSlotCount"
            :model="device.model"
            :expanded-onts="expandedOnts"
            :hover-port="hoverPort"
            empty-text="尚无板卡数据。点击「同步」登录设备采集；若同步成功仍为空，说明这台设备的命令与现有解析规则不匹配，可到「采集记录」看原始回显再补规则。"
            @toggle="onToggleOnts"
            @hover="hoverOn"
            @leave="hoverOff"
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
          <div class="olt-summary">
            <div>
              VLAN <b>{{ vlanList.length }}</b> 个，上行口 <b>{{ portVlans.length }}</b> 个，
              三层接口 <b>{{ vlanIpInterfaces.length }}</b> 个，静态路由 <b>{{ staticRoutes.length }}</b> 条
              <span v-if="vlanInfo && vlanInfo.collectedAt">；快照时间 {{ vlanInfo.collectedAt }}</span>
            </div>
          </div>

          <olt-vlan-panel
            :vlan-list="vlanList"
            :port-vlans="portVlans"
            :vlan-ip-interfaces="vlanIpInterfaces"
            :static-routes="staticRoutes"
            :vlan-group-rows="vlanGroupRows"
            empty-text="尚未采集到 VLAN 信息，也可以点上面的「同步」立即刷新。"
          />
        </el-tab-pane>

        <el-tab-pane label="ONT 配置/状态" name="ont">
          <div class="olt-summary">
            <div>
              业务板 <b>{{ ontBoards.length }}</b> 块，端口 <b>{{ ontPortTotal }}</b> 个，
              ONT <b>{{ ontSummary.total }}</b> 个（在线 {{ ontSummary.online }} / 离线 {{ ontSummary.offline }}）。
            </div>
          </div>

          <olt-ont-board
            :ont-boards="ontBoards"
            empty-text="尚未采集到端口/ONT 数据，点上面的「同步」立即刷新。"
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
import OltBoardCards from './components/OltBoardCards'
import OltHsgqFaceplate from './components/OltHsgqFaceplate'
import OltOntBoard from './components/OltOntBoard'
import OltVlanPanel from './components/OltVlanPanel'
import OltRecords from './components/OltRecords'
import OltFormDialog from './components/OltFormDialog'
import { oltDetailMixin } from './mixins/oltDetail'
import { detailRoute } from './vendors'
import { deleteOlt } from '@/api/olt'

/**
 * 通用详情页：没认出来的设备、以及中兴、烽火、鸿升光这些厂商都走这里。
 *
 * 只摆命令普遍通用的东西：板卡、端口 ONT、VLAN、配置信息、采集记录。
 * 华为那套 DBA、线路模板、主备、保护之类的面板不在这儿——那些标签页换个厂商就是错的。
 * 鸿升光是整机，板卡区换成按真机正面画的横向面板（OltHsgqFaceplate）。
 */
export default {
  name: 'OltGeneric',
  components: {
    OltDeviceBar,
    OltDeviceInfo,
    OltBoardCards,
    OltHsgqFaceplate,
    OltOntBoard,
    OltVlanPanel,
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
    /** 认出厂商后就没必要留在通用页了，跳到对应厂商的页面 */
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
