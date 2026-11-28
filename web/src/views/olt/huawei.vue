<template>
  <div :class="{ 'app-container': !embedded }">
    <!-- 内嵌台账页时上面已有设备条，这里只出内容区 -->
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
      :saving="savingConfig"
      @back="goBack"
      @edit="openEdit"
      @sync="handleSync()"
      @test="handleTest"
      @save-config="handleSaveConfig"
      @delete="handleDelete"
      @update:autoRefresh="autoRefresh = $event"
      @update:pollSeconds="pollSeconds = $event"
    />

    <el-card v-if="hasDevice" shadow="never" class="olt-main-card">
      <el-tabs v-model="activeTab" @tab-click="handleTabClick">
        <el-tab-pane label="OLT 状态" name="spec">
          <div class="olt-summary">
            <div>
              厂商：<b>{{ device.vendorLabel || '未识别' }}</b>；型号：<b>{{ device.model || '-' }}</b>；
              背板：{{ device.backplane || '-' }}；软件版本：{{ firmwareText }}
            </div>
            <div>最近连接：{{ device.lastConnectAtText }}；最近同步：{{ device.lastSyncAtText }}</div>
            <div>{{ device.syncSummary || '尚未同步，点击「同步」采集板卡信息' }}</div>
            <!-- 打开页面直连采集一次，采不到才退回库里的快照 -->
            <div class="olt-live" :class="{ 'olt-live-error': liveError }">{{ liveText }}</div>

            <!-- 自动发现开关不开，设备不会去发现光猫；状态以设备回执为准 -->
            <div class="olt-autofind">
              <span class="olt-autofind-label">ONT 自动发现</span>

              <el-tag v-if="autoFindLoading" size="mini" type="info">检测中…</el-tag>
              <template v-else-if="autoFind && autoFind.supported">
                <el-tag size="mini" :type="autoFind.allEnabled ? 'success' : 'warning'">
                  {{ autoFind.allEnabled
                    ? '已全部开启（' + autoFind.totalPorts + ' 个 PON 口）'
                    : '已开 ' + autoFind.enabledPorts + ' / ' + autoFind.totalPorts + ' 个 PON 口' }}
                </el-tag>
                <span v-if="autoFind.disabledPorts && autoFind.disabledPorts.length" class="olt-autofind-hint">
                  未开：{{ autoFind.disabledPorts.join('、') }}
                </span>
              </template>
              <template v-else>
                <el-tag size="mini" type="info">状态未知</el-tag>
                <span class="olt-autofind-hint">
                  {{ autoFindError || (autoFind && autoFind.message) || '还没检测' }}
                </span>
              </template>

              <!-- 状态查不到也允许开：这条命令幂等，不会把设备搞坏 -->
              <el-button
                v-if="!autoFindLoading && !(autoFind && autoFind.allEnabled)"
                type="text"
                size="mini"
                :loading="autoFindBusy"
                @click="handleEnableAutoFind"
              >一键开启自动发现</el-button>
              <el-button type="text" size="mini" :loading="autoFindLoading" @click="loadAutoFind()">重新检测</el-button>
            </div>
          </div>

          <div class="olt-section-title">
            板卡信息
            <span v-if="boardSummary" class="board-summary">{{ boardSummary }}</span>
            <!-- 板卡停在 auto-find（未确认）时不加载业务，需先一键确认 -->
            <el-button
              v-if="boardsNeedConfirm"
              class="board-confirm-btn"
              type="danger"
              size="mini"
              plain
              :loading="confirmingBoards"
              @click="handleConfirmBoards"
            >确认所有业务板</el-button>
          </div>

          <olt-board-cards
            :slots="slots"
            :slot-count="chassisSlotCount"
            :model="device.model"
            :brand="device.vendorLabel"
            :expanded-onts="expandedOnts"
            :hover-port="hoverPort"
            empty-text="尚无板卡数据。点击「同步」登录设备采集；若同步成功仍为空，说明厂商命令与解析规则不匹配，可到「采集记录」查看原始回显。"
            @toggle="onToggleOnts"
            @hover="hoverOn"
            @leave="hoverOff"
          />
        </el-tab-pane>

        <el-tab-pane label="配置信息" name="config">
          <!-- 改配置前先一键开局把参数配好 -->
          <div class="olt-config-actions">
            <el-button size="small" type="primary" icon="el-icon-setting" @click="quickSetupVisible = true">
              一键开局
            </el-button>
            <el-button size="small" icon="el-icon-user" @click="userVisible = true">
              创建用户
            </el-button>
            <span class="olt-config-actions-tip">
              管理 VLAN / 三层地址、TR-069 ACS、业务 VLAN 与 DBA 模板一次配好；建 OLT 登录账号走「创建用户」
            </span>
          </div>

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
            :add-port-vlan-handler="addPortVlan"
            :remove-port-vlan-handler="removePortVlan"
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
            :olt-id="detailId"
            :dba-profiles="dbaProfiles"
            :line-profiles="lineProfiles"
            :create-handler="createDba"
            :delete-handler="deleteDba"
            :tcont-handler="addTcont"
            :gemport-handler="addGemport"
            :gem-mapping-handler="addGemMapping"
            :create-line-handler="createLineProfile"
            :delete-line-handler="deleteLine"
            empty-text="尚未采集到 DBA / 线路模板。点上面的「同步」，采集时会跑 display dba-profile all 与 display ont-lineprofile gpon all。"
          />
        </el-tab-pane>

        <el-tab-pane label="业务/TR069/流量模板" name="service">
          <olt-service-panel
            :srv-profiles="srvProfiles"
            :tr069-profiles="tr069Profiles"
            :traffic-tables="trafficTables"
            @create-tr069="tr069DialogVisible = true"
          />
        </el-tab-pane>

        <el-tab-pane label="链路聚合" name="ola" lazy>
          <olt-link-aggregation-panel
            :groups="linkAggregations"
            :loading="linkAggLoading"
            :applying="linkAggApplying"
            @refresh="loadLinkAggregations()"
            @create="createLinkAggregation"
            @remove="deleteLinkAggregation"
          />
        </el-tab-pane>

        <el-tab-pane label="ONT 配置/状态" name="ont">
          <olt-ont-status-panel
            :ont-rows="ontRows"
            :ont-port-options="ontPortOptions"
            :ont-summary="ontSummary"
            :deletable-boards="deletableBoards"
            :deletable-ont-total="deletableOntTotal"
            :olt-id="detailId"
            :delete-all-handler="deleteAllOnts"
            :delete-ont-handler="deleteOnt"
            :reset-ont-handler="resetOltOntOnDevice"
            :empty-text="'尚未采集到端口/ONT 数据。点右上角「同步ONT状态」立即采集。'"
            @sync="syncSection('ont', true)"
          />
        </el-tab-pane>

        <el-tab-pane label="ONT 注册" name="ontreg">
          <olt-ont-reg-panel :olt-id="detailId" :olt-ip="device.host || ''" />
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

    <!-- 编辑台账：保存后按新厂商重新分发 -->
    <olt-form-dialog
      :visible.sync="formVisible"
      :olt="editing"
      @saved="handleSaved"
    />

    <!-- 一键开局：命令序列由后端生成，页面只收参数和展示预览 -->
    <olt-huawei-quick-setup-dialog
      :visible.sync="quickSetupVisible"
      :olt-id="detailId"
      :submit-handler="handleQuickSetup"
    />

    <!-- 新增 TR069 模板：保存即下发 ont tr069-server-profile add -->
    <olt-huawei-tr069-dialog
      :visible.sync="tr069DialogVisible"
      :olt-id="detailId"
      @saved="handleTr069Saved"
    />

    <!-- 创建 OLT 用户：华为走 terminal user name 交互式命令 -->
    <el-dialog :visible.sync="userVisible" title="创建 OLT 用户" width="460px" append-to-body>
      <el-form :model="userForm" label-width="92px" size="small">
        <el-form-item label="用户名">
          <el-input v-model="userForm.username" placeholder="6~15 位（设备限制）" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="userForm.password" type="password" placeholder="6~15 位（设备限制）" />
        </el-form-item>
        <el-form-item label="确认密码">
          <el-input v-model="userForm.confirmPassword" type="password" placeholder="再输一遍" />
        </el-form-item>
        <el-form-item label="权限级别">
          <el-select v-model="userForm.level" class="olt-user-level">
            <el-option label="Common User（只能看）" :value="1" />
            <el-option label="Operator（能改配置）" :value="2" />
            <el-option label="Administrator（超级管理员）" :value="3" />
          </el-select>
        </el-form-item>
      </el-form>

      <div class="olt-user-note">
        华为不允许建比当前登录账号级别更高的账号。级别选了 Administrator，
        但台账里这台设备是用 Operator 级账号（如 oltadmin）连的，设备会回
        「Error choice or the input level higher than owns」并拒绝，页面上会原样显示这句。
        要建 Administrator，请改用一个 root（Administrator 级）账号登录的台账。
      </div>

      <div slot="footer">
        <el-button size="small" type="primary" :loading="userBusy" @click="submitUser">下发</el-button>
        <el-button size="small" @click="userVisible = false">取消</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import OltDeviceBar from './components/common/OltDeviceBar'
import OltDeviceInfo from './components/common/OltDeviceInfo'
import OltBoardCards from './components/device/OltBoardCards'
import OltOntStatusPanel from './components/ont/OltOntStatusPanel'
import OltVlanPanel from './components/vlan/OltVlanPanel'
import OltDbaPanel from './components/template/OltDbaPanel'
import OltServicePanel from './components/template/OltServicePanel'
import OltRecords from './components/common/OltRecords'
import OltFormDialog from './components/common/OltFormDialog'
import OltHuaweiQuickSetupDialog from './components/setup/OltHuaweiQuickSetupDialog'
import OltHuaweiTr069Dialog from './components/template/OltHuaweiTr069Dialog'
import OltOntRegPanel from './components/ont/OltOntRegPanel'
import OltLinkAggregationPanel from './components/device/OltLinkAggregationPanel'
import { oltDetailMixin } from './mixins/oltDetail'
import { detailRoute } from './vendors'
import { deleteOlt, fetchOntAutoFind, enableOntAutoFind, createOltTerminalUser } from '@/api/olt'

/** 华为 MA5600T / MA5800 / EA5800 OLT 详情页 */
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
    OltFormDialog,
    OltHuaweiQuickSetupDialog,
    OltHuaweiTr069Dialog,
    OltOntRegPanel,
    OltLinkAggregationPanel
  },
  mixins: [oltDetailMixin],
  props: {
    /** 嵌在台账页里时由外面给；不传就取路由上的 id */
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
      editing: null,
      quickSetupVisible: false,
      tr069DialogVisible: false,
      // PON 口自动发现开关状态
      autoFind: null,
      autoFindLoading: false,
      autoFindBusy: false,
      autoFindError: '',
      // 级别默认 Operator：华为不允许建比当前账号更高级别的账号
      userVisible: false,
      userBusy: false,
      userForm: { username: '', password: '', confirmPassword: '', level: 2 }
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
        // 自动发现逐口查询较慢，不阻塞主信息
        this.loadAutoFind(id)
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
    /** 保存后厂商可能变了，按新厂商重新分发 */
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
    /** 查 PON 口自动发现开关；失败只在本行提示，不影响页面其它部分 */
    loadAutoFind(id) {
      const target = id || this.detailId
      if (!target) {
        return
      }
      this.autoFindLoading = true
      this.autoFindError = ''
      fetchOntAutoFind(target)
        .then(res => {
          this.autoFind = res.data || null
        })
        .catch(e => {
          this.autoFind = null
          this.autoFindError = (e && e.message) || '查不到自动发现状态'
        })
        .finally(() => {
          this.autoFindLoading = false
        })
    },
    handleEnableAutoFind() {
      const ports = this.autoFind ? this.autoFind.totalPorts : 0
      this.$confirm(
        '将在这台 OLT 的 ' + ports + ' 个 GPON 业务板 PON 口开启 ont-auto-find，完成后自动重采一次。' +
        '已经开着的口再开一次不受影响，是否继续？',
        '一键开启自动发现',
        { confirmButtonText: '开启', cancelButtonText: '取消', type: 'warning' }
      )
        .then(() => {
          this.autoFindBusy = true
          return enableOntAutoFind(this.detailId)
        })
        .then(res => {
          // 下发接口顺带返回重采后的整机数据，直接用
          this.applyDetail(res.data)
          this.$message.success('已下发开启命令，共 ' + ports + ' 个 PON 口')
          // 再查一次设备，显示真实回复的结果
          this.loadAutoFind()
        })
        .catch(e => {
          // 取消时 e 是 'cancel'，不算失败
          if (e !== 'cancel' && e !== 'close') {
            this.$message.error((e && e.message) || '开启失败')
          }
        })
        .finally(() => {
          this.autoFindBusy = false
        })
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
    },
    /** 保存成功后用返回的最新模板列表更新快照 */
    handleTr069Saved(list) {
      const template = this.detail && this.detail.template
      if (template) {
        this.$set(template, 'tr069Profiles', list || [])
      }
    },
    /** 建本地用户：由后端登设备走交互式命令下发 */
    submitUser() {
      const form = this.userForm
      const username = (form.username || '').trim()
      const password = form.password || ''
      if (!username || !password) {
        this.$message.warning('用户名和密码都要填')
        return
      }
      // 长度限制是设备定的，本地先拦下
      if (username.length < 6 || username.length > 15) {
        this.$message.warning('用户名要 6~15 位')
        return
      }
      if (password.length < 6 || password.length > 15) {
        this.$message.warning('密码要 6~15 位')
        return
      }
      if (password !== form.confirmPassword) {
        this.$message.warning('两次密码不一样')
        return
      }
      this.userBusy = true
      createOltTerminalUser(this.detailId, {
        username,
        password,
        level: form.level,
        reenter: 4
      })
        .then(res => {
          this.applyDetail(res.data)
          this.$message.success('已建用户 ' + username)
          this.userVisible = false
          this.userForm = { username: '', password: '', confirmPassword: '', level: 2 }
        })
        .catch(e => {
          this.$message.error((e && e.message) || '建用户失败')
        })
        .finally(() => {
          this.userBusy = false
        })
    }
  }
}
</script>

<style lang="scss" scoped>
@import '~@/styles/olt.scss';

.olt-config-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.olt-config-actions-tip {
  color: #909399;
  font-size: 12px;
}

.olt-user-level {
  width: 100%;
}

.olt-user-note {
  margin-top: 8px;
  padding: 6px 10px;
  color: #8a6d3b;
  font-size: 12px;
  line-height: 1.6;
  background: #fcf8e3;
  border-left: 3px solid #f0c36d;
}

</style>
