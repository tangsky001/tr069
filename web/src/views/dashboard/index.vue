<template>
  <div class="app-container">
    <el-row :gutter="16" class="stat-row">
      <el-col :xs="12" :sm="8" :md="6" :lg="4">
        <div class="stat-card">
          <div class="stat-icon total"><i class="el-icon-monitor" /></div>
          <div class="stat-body">
            <div class="stat-value">{{ overview.deviceTotal }}</div>
            <div class="stat-label">所有设备</div>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="8" :md="6" :lg="4">
        <div class="stat-card">
          <div class="stat-icon online"><i class="el-icon-success" /></div>
          <div class="stat-body">
            <div class="stat-value">{{ overview.online }}</div>
            <div class="stat-label">在线</div>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="8" :md="6" :lg="4">
        <div class="stat-card">
          <div class="stat-icon offline"><i class="el-icon-error" /></div>
          <div class="stat-body">
            <div class="stat-value">{{ overview.offline }}</div>
            <div class="stat-label">离线</div>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="8" :md="6" :lg="4">
        <div class="stat-card">
          <div class="stat-icon dispatched"><i class="el-icon-s-promotion" /></div>
          <div class="stat-body">
            <div class="stat-value">{{ overview.dispatched }}</div>
            <div class="stat-label">已下发</div>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="8" :md="6" :lg="4">
        <div class="stat-card">
          <div class="stat-icon undispatched"><i class="el-icon-warning-outline" /></div>
          <div class="stat-body">
            <div class="stat-value">{{ overview.undispatched }}</div>
            <div class="stat-label">未下发</div>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="8" :md="6" :lg="4">
        <div class="stat-card">
          <div class="stat-icon brand"><i class="el-icon-cpu" /></div>
          <div class="stat-body">
            <div class="stat-value">{{ (overview.brands || []).length }}</div>
            <div class="stat-label">光猫品牌</div>
          </div>
        </div>
      </el-col>
    </el-row>

    <el-row :gutter="16">
      <el-col :xs="24" :md="14">
        <el-card shadow="never" class="panel">
          <div slot="header" class="panel-header">
            <span>光猫品牌分布</span>
            <el-button type="text" icon="el-icon-refresh" @click="load">刷新</el-button>
          </div>
          <div v-if="brands.length === 0" class="empty">暂无设备</div>
          <div v-for="brand in brands" :key="brand.name" class="brand-row">
            <span class="brand-name" :title="brand.name">{{ brand.name }}</span>
            <div class="brand-bar">
              <div class="brand-bar-inner" :style="{ width: brand.percent + '%' }" />
            </div>
            <span class="brand-count">{{ brand.count }}</span>
          </div>
        </el-card>
      </el-col>

      <el-col :xs="24" :md="10">
        <el-card shadow="never" class="panel">
          <div slot="header" class="panel-header">
            <span>工单与模板</span>
          </div>
          <div class="kv-row">
            <span>工单总数</span><b>{{ worklist.total }}</b>
          </div>
          <div class="kv-row">
            <span>执行成功</span><b class="ok">{{ worklist.success }}</b>
          </div>
          <div class="kv-row">
            <span>执行失败</span><b class="bad">{{ worklist.fail }}</b>
          </div>
          <div class="kv-row">
            <span>执行中</span><b class="running">{{ worklist.running }}</b>
          </div>
          <el-divider />
          <div class="kv-row">
            <span>配置模板</span><b>{{ templates.total }}</b>
          </div>
          <div class="kv-row">
            <span>已启用</span><b class="ok">{{ templates.enabled }}</b>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="panel">
      <div slot="header" class="panel-header">
        <span>快捷入口</span>
      </div>
      <el-button type="primary" icon="el-icon-monitor" @click="go('DeviceList')">设备管理</el-button>
      <el-button type="success" icon="el-icon-files" @click="go('TemplateList')">配置模板</el-button>
      <el-button type="warning" icon="el-icon-menu" @click="go('BizTemplateList')">业务模板</el-button>
      <el-button icon="el-icon-tickets" @click="go('WorklistInstances')">工单记录</el-button>
    </el-card>
  </div>
</template>

<script>
import { fetchOverview } from '@/api/dashboard'

export default {
  name: 'Dashboard',
  data() {
    return {
      loading: false,
      overview: {
        deviceTotal: 0,
        online: 0,
        offline: 0,
        dispatched: 0,
        undispatched: 0,
        brands: [],
        worklistStats: {},
        templateStats: {}
      }
    }
  },
  computed: {
    brands() {
      const list = this.overview.brands || []
      const max = list.reduce((m, b) => Math.max(m, b.count || 0), 0)
      return list.map(b => ({
        name: b.name,
        count: b.count || 0,
        percent: max === 0 ? 0 : Math.round((b.count / max) * 100)
      }))
    },
    worklist() {
      return this.overview.worklistStats || {}
    },
    templates() {
      return this.overview.templateStats || {}
    }
  },
  created() {
    this.load()
  },
  methods: {
    async load() {
      this.loading = true
      try {
        const res = await fetchOverview()
        this.overview = res.data || this.overview
      } catch (e) {
        this.overview = {
          deviceTotal: 0,
          online: 0,
          offline: 0,
          dispatched: 0,
          undispatched: 0,
          brands: [],
          worklistStats: {},
          templateStats: {}
        }
      } finally {
        this.loading = false
      }
    },
    go(name) {
      this.$router.push({ name })
    }
  }
}
</script>

<style scoped>
.stat-row {
  margin-bottom: 16px;
}
.stat-card {
  display: flex;
  align-items: center;
  padding: 16px;
  margin-bottom: 16px;
  background: #fff;
  border-radius: 4px;
  box-shadow: 0 1px 4px rgba(0, 21, 41, 0.08);
}
.stat-icon {
  width: 48px;
  height: 48px;
  margin-right: 12px;
  border-radius: 4px;
  color: #fff;
  font-size: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.stat-icon.total { background: #409eff; }
.stat-icon.online { background: #67c23a; }
.stat-icon.offline { background: #909399; }
.stat-icon.dispatched { background: #e6a23c; }
.stat-icon.undispatched { background: #f56c6c; }
.stat-icon.brand { background: #8e44ad; }
.stat-value {
  font-size: 24px;
  font-weight: 600;
  line-height: 1.2;
}
.stat-label {
  color: #909399;
  font-size: 13px;
}
.panel {
  margin-bottom: 16px;
}
.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
}
.brand-row {
  display: flex;
  align-items: center;
  margin-bottom: 10px;
}
.brand-name {
  width: 120px;
  font-size: 13px;
  color: #606266;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.brand-bar {
  flex: 1;
  height: 10px;
  background: #f0f2f5;
  border-radius: 5px;
  overflow: hidden;
}
.brand-bar-inner {
  height: 100%;
  background: linear-gradient(90deg, #409eff, #67c23a);
}
.brand-count {
  width: 48px;
  text-align: right;
  font-size: 13px;
  color: #303133;
}
.kv-row {
  display: flex;
  justify-content: space-between;
  padding: 6px 0;
  font-size: 14px;
  color: #606266;
}
.kv-row b {
  color: #303133;
}
.ok { color: #67c23a !important; }
.bad { color: #f56c6c !important; }
.running { color: #409eff !important; }
.empty {
  color: #909399;
  font-size: 13px;
  padding: 12px 0;
}
</style>
