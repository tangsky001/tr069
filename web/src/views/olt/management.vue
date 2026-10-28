<template>
  <div class="app-container">
    <!-- 顶部：OLT 资源信息，下拉切换哪一台 -->
    <el-card shadow="never" class="olt-top-card">
      <div slot="header" class="olt-top-head">
        <span class="olt-title">OLT/ONU 管理</span>
        <div>
          <el-button type="primary" size="mini" icon="el-icon-plus" @click="openCreate">添加 OLT</el-button>
          <el-button type="success" size="mini" icon="el-icon-refresh" :loading="loading" @click="loadList">刷新</el-button>
        </div>
      </div>

      <div v-if="!resources.length" class="olt-empty">
        尚未添加 OLT。点击右上角「添加 OLT」填写地址与登录账号，保存时先用「测试连接」认厂商，
        认出来后自动按厂商的面板展示。
      </div>

      <template v-else>
        <div class="olt-res-row">
          <span class="olt-label">OLT 资源信息：</span>
          <el-select
            v-model="selectedId"
            size="small"
            style="width:260px"
            filterable
            placeholder="请选择 OLT"
          >
            <el-option v-for="r in resources" :key="r.id" :label="r.name" :value="r.id" />
          </el-select>
          <el-input :value="selected.host" size="small" style="width:170px" disabled />
          <el-button type="primary" size="small" icon="el-icon-edit" :disabled="!selectedId" @click="openEdit">编辑</el-button>
          <el-button
            type="primary"
            size="small"
            icon="el-icon-refresh-right"
            :loading="syncing"
            :disabled="!selectedId"
            @click="handleSync"
          >同步</el-button>
          <el-button
            type="warning"
            size="small"
            icon="el-icon-cpu"
            :loading="testing"
            :disabled="!selectedId"
            @click="handleTest"
          >诊断测试</el-button>
          <el-button type="danger" size="small" icon="el-icon-delete" :disabled="!selectedId" @click="handleDelete">删除</el-button>
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

    <!-- 主体：按下拉选中的厂商渲染对应视图，切设备当场换面板，不用来回跳页面 -->
    <component
      :is="vendorView"
      v-if="selectedId"
      :key="selectedId"
      :device-id="selectedId"
      embedded
    />

    <olt-form-dialog
      :visible.sync="formVisible"
      :olt="editing"
      @saved="handleSaved"
    />
  </div>
</template>

<script>
import OltFormDialog from './components/OltFormDialog'
import { fetchOltList, deleteOlt, testOltById, quickSyncOlt } from '@/api/olt'
import { componentOfVendor } from './vendors'

/**
 * OLT 管理页：上面下拉选哪一台，下面就按厂商出哪一套面板。
 *
 * 这里只管台账本身——下拉切换、增删改、诊断连通性、触发同步，再加一排设备概况；
 * 板卡、ONT、VLAN 那些采集结果的展示全在厂商视图里（华为一套、其它厂商走第三方），
 * 由 vendors.js 按 vendor 挑一个渲染在下面。厂商不同命令不同，混在一个页面里很快就没法维护。
 */
export default {
  name: 'OltManagement',
  components: { OltFormDialog },
  data() {
    return {
      loading: false,
      syncing: false,
      testing: false,
      resources: [],
      selectedId: null,
      formVisible: false,
      editing: null,
      // 台账列表的自动刷新（30 秒起），默认关；面板数据由视图自己按 10 秒拉
      autoRefresh: false,
      pollSeconds: 30,
      pollTimer: null
    }
  },
  computed: {
    /** 下拉选中的那台；列表刷新后可能不在里面了（被删），退回空对象 */
    selected() {
      return this.resources.find(item => String(item.id) === String(this.selectedId)) || {}
    },
    /** 按厂商挑视图组件：华为出华为面板，其它厂商（鸿升光/中兴/烽火…）都出第三方面板 */
    vendorView() {
      return componentOfVendor(this.selected.vendor)
    },
    firmwareText() {
      const parts = [this.selected.softwareVersion, this.selected.patchVersion].filter(item => !!item)
      return parts.length ? parts.join(' / ') : '-'
    },
    statusText() {
      const map = { ONLINE: '在线', OFFLINE: '离线', UNKNOWN: '未探测' }
      return map[this.selected.status] || '未探测'
    },
    statusTag() {
      if (this.selected.status === 'ONLINE') return 'success'
      if (this.selected.status === 'OFFLINE') return 'danger'
      return 'info'
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
    this.loadList()
  },
  beforeDestroy() {
    this.stopPolling()
  },
  methods: {
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
          // 还没选、或者选的那台已经没了（被删），就默认选第一台
          if (!this.selectedId || !this.resources.some(item => String(item.id) === String(this.selectedId))) {
            this.selectedId = this.resources.length ? this.resources[0].id : null
          }
        })
        .catch(() => {})
        .finally(() => {
          this.loading = false
        })
    },
    openCreate() {
      this.editing = null
      this.formVisible = true
    },
    openEdit() {
      this.editing = this.selected
      this.formVisible = true
    },
    /**
     * 保存后刷新台账：新加的直接选中它（面板按刚认出的厂商出），
     * 编辑的保持选中；厂商变了的话下面那块面板会自动换成新厂商那套。
     */
    handleSaved(saved) {
      this.formVisible = false
      const wasEdit = !!this.editing
      this.loadList().then(() => {
        if (saved) {
          this.selectedId = saved.id
        }
        if (wasEdit) {
          this.$message.success('已保存' + (saved && saved.vendorLabel ? '，当前面板：' + saved.vendorLabel : ''))
        }
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
    handleDelete() {
      const name = this.selected.name || this.selected.host
      this.$confirm('删除后该 OLT 的板卡与采集记录将一并清理，是否继续？', '删除 OLT：' + name, {
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        type: 'warning'
      })
        .then(() => deleteOlt(this.selectedId))
        .then(() => {
          this.$message.success('已删除')
          this.selectedId = null
          return this.loadList()
        })
        .catch(() => {})
    }
  }
}
</script>

<style lang="scss" scoped>
@import '~@/styles/olt.scss';

.olt-foot-tip {
  margin-top: 10px;
  color: #909399;
  font-size: 12px;
  line-height: 1.8;
}
</style>
