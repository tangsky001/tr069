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
      <el-tabs v-model="activeTab">
        <el-tab-pane label="OLT 状态" name="spec">
          <!-- 连接概要：先看设备概况，再看下面每块板卡 -->
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

          <olt-board-cards
            :slots="slots"
            :slot-count="chassisSlotCount"
            :model="device.model"
            :expanded-onts="expandedOnts"
            :hover-port="hoverPort"
            empty-text="尚无板卡数据。点击「同步」登录设备采集；若同步成功仍为空，说明厂商命令与解析规则不匹配，可到「采集记录」查看原始回显。"
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
            empty-text="尚未采集到 VLAN 信息。后台启动后会自动采一轮、之后每 5 分钟一次（display vlan all），也可以点上面的「同步」立即刷新。"
            @save="afterOltOperation"
          />
        </el-tab-pane>

        <el-tab-pane label="DBA/线路模板" name="dba">
          <div class="olt-summary">
            <div>
              DBA 模板 <b>{{ dbaProfiles.length }}</b> 条，线路模板 <b>{{ lineProfiles.length }}</b> 条
              <span v-if="templateInfo && templateInfo.collectedAt">；快照时间 {{ templateInfo.collectedAt }}</span>
            </div>
          </div>

          <olt-dba-panel
            :dba-profiles="dbaProfiles"
            :line-profiles="lineProfiles"
            :create-handler="createDba"
            :delete-handler="deleteDba"
            empty-text="尚未采集到 DBA / 线路模板。点上面的「同步」，采集时会跑 display dba-profile all 与 display ont-lineprofile gpon all。"
          />
        </el-tab-pane>

        <el-tab-pane label="业务/TR069/流量模板" name="service">
          <olt-service-panel
            :srv-profiles="srvProfiles"
            :tr069-profiles="tr069Profiles"
            :traffic-tables="trafficTables"
          />
        </el-tab-pane>

        <el-tab-pane label="ONT 配置/状态" name="ont">
          <div class="olt-summary">
            <div>
              业务板 <b>{{ ontBoards.length }}</b> 块，端口 <b>{{ ontPortTotal }}</b> 个，
              ONT <b>{{ ontSummary.total }}</b> 个（在线 {{ ontSummary.online }} / 离线 {{ ontSummary.offline }}）。
            </div>
            <div>
              列表数据来自每块业务板一条 <code>display ont info 0 &lt;槽位&gt; all</code>；
              行上的「详情 / 光信息 / 版本 / 注册历史」图标点开时才登录设备查一次
              （<code>display ont info / optical-info / version / register-info</code>）；
              点 SN 或「线路/业务模板」列还能看到这条 ONT 的 IP、绑定的模板与业务流。
            </div>
          </div>

          <olt-ont-status-panel
            :ont-boards="ontBoards"
            :olt-id="detailId"
            :empty-text="'尚未采集到端口/ONT 数据。点右上角「同步ONT状态」立即采集。'"
            @sync="afterOltOperation"
          />
        </el-tab-pane>

        <el-tab-pane label="ONT 注册" name="ontreg">
          <div class="olt-placeholder">
            ONT 注册（占位）：待接入 <code>display ont autofind all</code> 采集后展示待注册 ONT
            （SN / MAC、发现端口、时间），并支持按 SN 授权到指定端口、绑定线路模板与业务模板。
          </div>
        </el-tab-pane>

        <el-tab-pane label="OLA" name="ola">
          <div class="olt-placeholder">OLA（光链路聚合，占位）：待接入真实 OLT 采集数据后展示聚合组与成员口。</div>
        </el-tab-pane>

        <el-tab-pane label="ONU 后板编辑面板" name="onu">
          <div class="olt-placeholder">ONU 后板编辑面板（占位）：用于按槽位/端口批量编辑 ONU 模板与授权，待后端接入。</div>
        </el-tab-pane>

        <el-tab-pane label="采集记录" name="records">
          <olt-records :records="records" @refresh="loadRecords" />
        </el-tab-pane>

        <el-tab-pane label="OLT 升级" name="upgrade">
          <div class="olt-placeholder">OLT 升级（占位）：固件版本管理与批量升级任务，待后端接入。</div>
        </el-tab-pane>

        <el-tab-pane label="主备" name="standby">
          <div class="olt-placeholder">主备（占位）：主控板主备状态、倒换记录，待采集主控板信息后展示。</div>
        </el-tab-pane>

        <el-tab-pane label="保护" name="protect">
          <div class="olt-placeholder">保护（占位）：Type B/C 保护组、环路检测等配置，待后端接入。</div>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 编辑台账：改了厂商要能重新分发，保存后回列表重新进 -->
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
import OltOntStatusPanel from './components/OltOntStatusPanel'
import OltVlanPanel from './components/OltVlanPanel'
import OltDbaPanel from './components/OltDbaPanel'
import OltServicePanel from './components/OltServicePanel'
import OltRecords from './components/OltRecords'
import OltFormDialog from './components/OltFormDialog'
import { oltDetailMixin } from './mixins/oltDetail'
import { detailRoute } from './vendors'
import { deleteOlt } from '@/api/olt'

/**
 * 华为 MA5600T / MA5800 / EA5800 详情页。
 *
 * 走的就是华为那一套 display 命令：板卡、温度、每口 ONT 明细、VLAN 与静态路由都是真的采上来的，
 * DBA 模板与线路模板也是（display dba-profile all / display ont-lineprofile gpon all，
 * 展开线路模板看它绑定的 T-CONT / GEM port 与 DBA 带宽档位）；
 * 剩下一标了「占位」的标签页是还没接采集的业务流、ONT 能力模板那一批。
 */
export default {
  name: 'OltHuawei',
  components: {
    OltDeviceBar,
    OltDeviceInfo,
    OltBoardCards,
    OltOntStatusPanel,
    OltVlanPanel,
    OltDbaPanel,
    OltServicePanel,
    OltRecords,
    OltFormDialog
  },
  mixins: [oltDetailMixin],
  props: {
    /**
     * 嵌在台账页里时由外面给（下拉选中的那台）；不传就取路由上的 id。
     */
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
    // 切换 OLT 时重新拉
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
    /**
     * 保存后厂商可能变了（比如原本识别成通用，同步后认清是华为），
     * 按新厂商重新分发一次，不符合就跳到对应页面。
     */
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
</style>
