<template>
  <div class="app-container olt-layout">
    <!-- 左：分组树筛选 -->
    <olt-group-tree @node-select="onGroupSelect" @device-select="onDeviceSelect" />

    <!-- 右：OLT 资源信息 + 厂商面板 -->
    <div class="olt-main-col">
      <el-card shadow="never" class="olt-top-card">
        <div slot="header" class="olt-top-head">
          <span class="olt-title">OLT/ONU 管理</span>
          <div class="olt-head-actions">
            <el-tag
              v-if="groupFilterName"
              size="small"
              type="warning"
              closable
              class="olt-group-tag"
              @close="clearGroupFilter"
            >分组：{{ groupFilterName }}</el-tag>
            <el-button type="success" size="mini" icon="el-icon-refresh" :loading="loading" @click="loadList">刷新</el-button>
          </div>
        </div>

        <div v-if="!resources.length" class="olt-empty">
          尚未添加 OLT。请到「设备根域」选择节点后点「添加 OLT」，添加后设备即归属该分组；
          认出来后自动按厂商的面板展示。
        </div>

        <div v-else-if="!filteredResources.length" class="olt-empty">
          当前分组「{{ groupFilterName }}」下没有 OLT，点左侧分组树的「全部」或标签上的 × 清除筛选。
        </div>

        <template v-else>
          <div class="olt-res-row">
            <span class="olt-label">OLT 资源信息：</span>
            <el-select
              v-model="selectedId"
              size="small"
              style="width:140px"
              filterable
              placeholder="请选择 OLT"
            >
              <el-option v-for="r in filteredResources" :key="r.id" :label="r.name" :value="r.id" />
            </el-select>
            <el-input :value="selected.host" size="small" style="width:170px" disabled />
            <el-button
              v-if="$has('olt:update')"
              type="primary"
              size="small"
              icon="el-icon-refresh-right"
              :loading="syncing"
              :disabled="!selectedId"
              @click="handleSync"
            >同步</el-button>
            <el-button
              v-if="$has('olt:update')"
              type="warning"
              size="small"
              icon="el-icon-cpu"
              :loading="testing"
              :disabled="!selectedId"
              @click="handleTest"
            >诊断测试</el-button>
            <el-button
              v-if="$has('olt:update')"
              type="danger"
              plain
              size="small"
              icon="el-icon-switch-button"
              :loading="rebooting"
              :disabled="!selectedId"
              @click="handleReboot"
            >设备重启</el-button>
            <el-button
              v-if="$has('olt:update')"
              type="success"
              size="small"
              icon="el-icon-download"
              :loading="savingConfig"
              :disabled="!selectedId"
              @click="handleSaveConfig"
            >保存配置</el-button>
            <el-button
              size="small"
              :icon="mapVisible ? 'el-icon-arrow-up' : 'el-icon-map-location'"
              @click="mapVisible = !mapVisible"
            >{{ mapVisible ? '收起地图' : '省份地图' }}</el-button>
            <span class="olt-auto">
              <el-switch v-model="autoRefresh" />
              <span class="olt-auto-label">自动刷新</span>
              <el-select v-model="pollSeconds" size="small" style="width:86px;margin-left:4px" :disabled="!autoRefresh">
                <el-option :value="5" label="5 秒" />
                <el-option :value="10" label="10 秒" />
                <el-option :value="30" label="30 秒" />
                <el-option :value="60" label="60 秒" />
              </el-select>
            </span>
            <span class="olt-auto">
              <el-switch v-model="keepalive" :disabled="!keepaliveReady" @change="handleKeepaliveChange" />
              <span class="olt-auto-label">会话保活</span>
              <el-select v-model="keepaliveMinutes" size="small" style="width:86px;margin-left:4px" :disabled="!keepalive || !keepaliveReady" @change="handleKeepaliveChange">
                <el-option v-for="m in keepaliveMinuteOptions" :key="m" :value="m" :label="m + ' 分钟'" />
              </el-select>
            </span>
          </div>

          <div class="olt-meta-row">
            <span>名称：<b>{{ selected.name || '-' }}</b></span>
            <span>
              厂商：
              <el-tag v-if="selected.vendorLabel" size="mini" type="success">{{ selected.vendorLabel }}</el-tag>
              <span v-else>未识别</span>
            </span>
            <span>型号：<b>{{ selected.model || '-' }}</b></span>
            <span>背板型号：{{ selected.backplane || '-' }}</span>
            <span>主控 MAC：{{ selected.macAddress || '-' }}</span>
            <span>固件版本：{{ firmwareText }}</span>
            <span>运行时长：{{ selected.uptime || '-' }}</span>
            <span>
              状态：
              <el-tag :type="statusTag" size="mini">{{ statusText }}</el-tag>
            </span>
            <span>
              最近同步：{{ selected.lastSyncAt || '未同步' }}
              <span class="olt-auto-tip">（启动即采一次，之后每 5 分钟自动采集，页面自动刷新）</span>
            </span>
          </div>

          <div v-if="selected.lastError" class="olt-error">最近一次失败：{{ selected.lastError }}</div>

          <div class="olt-foot-tip">
            下面的面板按厂商自动切换面板，没认出来的先按通用视图展示
            认出来后（「诊断测试」重新识别）会自己换过去。厂商是「测试连接」时用版本回显认出来的。
          </div>
        </template>
      </el-card>

      <!-- 按厂商渲染对应视图，切设备当场换面板 -->
      <china-map v-if="mapVisible" member-type="OLT" title="OLT 省份分布" @province-select="mapVisible = false" />

      <component
        :is="vendorView"
        v-if="selectedId"
        :key="selectedId"
        :device-id="selectedId"
        embedded
      />

    </div>
  </div>
</template>

<script>
import OltGroupTree from './components/common/OltGroupTree'
import ChinaMap from '@/components/ChinaMap'
import { fetchOltList, testOltById, quickSyncOlt, fetchOltSessionPool, setOltKeepalive, saveOltConfig, rebootOlt } from '@/api/olt'
import { fetchGroupMembers } from '@/api/group'
import { componentOfVendor } from './vendors'

/** OLT 管理页：下拉选设备，按厂商渲染对应面板 */
export default {
  name: 'OltManagement',
  components: { OltGroupTree, ChinaMap },
  data() {
    return {
      loading: false,
      syncing: false,
      testing: false,
      savingConfig: false,
      /** 设备重启下发中：按钮转圈防连点 */
      rebooting: false,
      /** 第一次确认后核对过的 web 超管密码：再次点击「设备重启」且确认后才真正下发 */
      rebootPassword: '',
      mapVisible: false,
      resources: [],
      selectedId: null,
      // 台账列表的自动刷新，默认关；面板数据由视图自己拉
      autoRefresh: false,
      pollSeconds: 30,
      pollTimer: null,
      // Telnet 会话保活开关（后端长连接管理），状态从后端取，取到前不允许切换
      keepalive: false,
      keepaliveMinutes: 2,
      // 可选的探活档位由后端给，页面不写死
      keepaliveMinuteOptions: [],
      keepaliveReady: false,
      // 分组筛选：null = 全部
      groupFilterRef: null,
      groupFilterName: '',
      groupOltIds: null
    }
  },
  computed: {
    /** 下拉选中的那台；列表刷新后可能已被删，退回空对象 */
    selected() {
      return this.filteredResources.find(item => String(item.id) === String(this.selectedId)) || {}
    },
    /** 按分组过滤后的台账；未选分组 = 全部 */
    filteredResources() {
      if (!this.groupOltIds) {
        return this.resources
      }
      return this.resources.filter(item => this.groupOltIds.includes(String(item.id)))
    },
    /** 按厂商挑视图组件 */
    vendorView() {
      return componentOfVendor(this.selected.vendor)
    },
    /** 固件版本与状态文案由后端统一下发 */
    firmwareText() {
      return this.selected.firmwareText || '-'
    },
    statusText() {
      return this.selected.statusText || '未探测'
    },
    statusTag() {
      return this.selected.statusLevel || 'info'
    }
  },
  watch: {
    autoRefresh(value) {
      if (value) {
        this.startPolling()
      } else {
        this.stopPolling()
      }
    },
    pollSeconds() {
      if (this.autoRefresh) {
        this.startPolling()
      }
    }
  },
  created() {
    // 分组页「管理」跳转：?deviceId= 预选设备
    this.preselectId = this.$route.query.deviceId || null
    this.loadList()
    this.loadSessionPool()
  },
  beforeDestroy() {
    this.stopPolling()
  },
  methods: {
    /** 左侧分组树选中/清除：拉组内成员 id 清单，过滤右侧 OLT 下拉 */
    onGroupSelect(node) {
      if (!node) {
        this.clearGroupFilter()
        return
      }
      this.groupFilterRef = node.nodeRef
      this.groupFilterName = node.name
      this.groupOltIds = []
      fetchGroupMembers(node.nodeRef)
        .then(res => {
          this.groupOltIds = (res.data || []).map(row => String(row.oltId))
          // 筛选后当前选中的不在组内就退回组内第一台
          if (this.selectedId && !this.groupOltIds.includes(String(this.selectedId))) {
            this.selectedId = this.filteredResources.length ? this.filteredResources[0].id : null
          }
        })
        .catch(() => {
          this.clearGroupFilter()
        })
    },
    clearGroupFilter() {
      this.groupFilterRef = null
      this.groupFilterName = ''
      this.groupOltIds = null
    },
    /** 点树上的设备叶子：直接选中这台设备（不改变分组筛选） */
    onDeviceSelect(node) {
      const id = node.deviceId
      if (id != null && this.filteredResources.some(item => String(item.id) === String(id))) {
        this.selectedId = id
      }
    },
    startPolling() {
      this.stopPolling()
      this.pollTimer = setInterval(() => {
        if (!this.loading) this.loadList()
      }, this.pollSeconds * 1000)
    },
    stopPolling() {
      if (this.pollTimer) {
        clearInterval(this.pollTimer)
        this.pollTimer = null
      }
    },
    loadList() {
      this.loading = true
      return fetchOltList()
        .then(res => {
          this.resources = res.data || []
          // 从分组页「管理」跳过来时带 deviceId，直接选中这台设备
          if (this.preselectId && this.resources.some(item => String(item.id) === String(this.preselectId))) {
            this.selectedId = this.preselectId
            this.preselectId = null
            return
          }
          // 还没选、或选中的那台已被删，就默认选第一台
          if (!this.selectedId || !this.resources.some(item => String(item.id) === String(this.selectedId))) {
            this.selectedId = this.resources.length ? this.resources[0].id : null
          }
        })
        .catch(() => {})
        .finally(() => {
          this.loading = false
        })
    },
    /** 拉会话池状态，把后端的开关和探活周期同步到页面上 */
    loadSessionPool() {
      return fetchOltSessionPool()
        .then(res => {
          const data = res.data || {}
          this.keepalive = !!data.keepaliveEnabled
          // 分钟档位由后端算好，页面不再换算
          this.keepaliveMinutes = data.keepaliveMinutes || 2
          this.keepaliveMinuteOptions = data.keepaliveMinuteOptions || []
          this.keepaliveReady = true
        })
        .catch(() => {})
    },
    /** 切换保活开关/周期：失败就把开关回退 */
    handleKeepaliveChange() {
      if (!this.keepaliveReady) return
      const target = this.keepalive
      setOltKeepalive(target, this.keepaliveMinutes * 60000)
        .then(res => {
          const data = res.data || {}
          this.keepalive = !!data.keepaliveEnabled
          // 文案按后端返回的数字生成，不写死
          this.$message.success(target
            ? '会话保活已开启：每台 OLT 保持 1 条长连接常驻登录，' +
              this.keepaliveMinutes + ' 分钟探活一次'
            : '会话保活已关闭：会话 ' + (data.idleQuitText || '1 分钟') + '没人用将退出登录')
        })
        .catch(() => {
          this.keepalive = !target
        })
    },

    handleTest() {
      if (!this.selectedId) return
      this.testing = true
      testOltById(this.selectedId)
        .then(res => {
          const data = res.data || {}
          this.$alert(data.message || (data.success ? '连接成功' : '连接失败'), data.success ? '诊断测试通过' : '诊断测试失败', {
            type: data.success ? 'success' : 'error'
          })
          this.loadList()
        })
        .catch(() => {})
        .finally(() => {
          this.testing = false
        })
    },
    handleSync() {
      if (!this.selectedId) return
      this.syncing = true
      // 只采板卡秒回，端口 / ONT / VLAN / 模板由后端后台补采
      quickSyncOlt(this.selectedId)
        .then(res => {
          const data = res.data || {}
          this.$message.success('同步完成：' + ((data.device && data.device.syncSummary) || '已刷新采集数据'))
          this.loadList()
        })
        .catch(() => {})
        .finally(() => {
          this.syncing = false
        })
    },

    /** 保存配置：下发 save 写 flash，掉电不丢；写盘要几十秒，先确认再发 */
    handleSaveConfig() {
      if (!this.selectedId) return
      this.$confirm(
        '把当前运行配置写入 OLT 的 flash，掉电后配置不丢。写盘要几十秒且期间设备不接其它操作，是否继续？',
        '保存配置',
        { confirmButtonText: '保存', cancelButtonText: '取消', type: 'warning' }
      )
        .then(() => {
          this.savingConfig = true
          return saveOltConfig(this.selectedId)
        })
        .then(() => {
          this.$message.success('已保存到设备 flash，回显见下方面板的「采集记录」')
          this.loadList()
        })
        .catch(e => {
          if (e === 'cancel') return
          this.$message.error((e && e.message) || '保存配置失败')
        })
        .finally(() => {
          this.savingConfig = false
        })
    },
    /** 设备重启三段防误触：确认业务中断 → 核验超管密码 → 再次点击最终确认 */
    handleReboot() {
      if (!this.selectedId) return
      if (this.rebootPassword) {
        // 密码已核对，这里是最终确认
        this.$confirm(
          '超级管理员密码已核对。最后确认：重启将导致所有 ONT 业务中断，确定立即下发重启命令？',
          '设备重启',
          { type: 'warning', confirmButtonText: '是', cancelButtonText: '否' }
        )
          .then(() => {
            const password = this.rebootPassword
            this.rebootPassword = ''
            this.rebooting = true
            return rebootOlt(this.selectedId, password)
          })
          .then(() => {
            this.$message.success('重启命令已下发，设备正在重启；业务恢复需几分钟，稍后点「同步」确认')
            this.loadList()
          })
          .catch(e => {
            if (e === 'cancel' || e === 'close') return
            // 业务错误已由 request 拦截器统一提示
          })
          .finally(() => {
            this.rebooting = false
          })
        return
      }
      this.$confirm(
        '确定需要重启 OLT 设备吗？这样会导致所有 ONT 业务中断。',
        '设备重启',
        { type: 'warning', confirmButtonText: '是', cancelButtonText: '否' }
      )
        .then(() => this.$prompt('请输入 web 超级管理员密码，授权本次重启', '设备重启', {
          inputType: 'password',
          inputPlaceholder: '当前登录账号的密码',
          inputPattern: /\S+/,
          inputErrorMessage: '密码不能为空',
          confirmButtonText: '确定',
          cancelButtonText: '取消'
        }))
        .then(({ value }) => {
          this.rebootPassword = value
          this.$message.info('密码已核对，请再次点击「设备重启」做最终确认')
        })
        .catch(() => {})
    }
  }
}
</script>

<style lang="scss" scoped>
@import '~@/styles/olt.scss';

.olt-layout {
  display: flex;
  gap: 16px;
}
.olt-main-col {
  flex: 1;
  min-width: 0;
}
.olt-head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.olt-group-tag {
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
}
.olt-foot-tip {
  margin-top: 10px;
  color: #909399;
  font-size: 12px;
  line-height: 1.8;
}
</style>
