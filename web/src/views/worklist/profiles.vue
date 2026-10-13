<template>
  <div class="app-container">
    <el-card shadow="never" class="status-card">
      <div slot="header">
        配置总览
        <el-button type="primary" size="mini" style="float:right;margin-left:8px" :loading="reloadLoading" @click="handleReload">
          热加载
        </el-button>
        <el-button size="mini" style="float:right" @click="handleSamples">释放示例配置</el-button>
      </div>
      <el-row :gutter="12" class="kv">
        <el-col :span="24"><span class="k">配置根目录</span><span class="v">{{ status.rootPath || '-' }}</span></el-col>
        <el-col :span="6"><span class="k">光猫档案</span><span class="v">{{ status.profileCount == null ? '-' : status.profileCount }}</span></el-col>
        <el-col :span="6"><span class="k">档案异常</span><span class="v">
          <el-tag v-if="status.profileInvalidCount" type="danger" size="small">{{ status.profileInvalidCount }}</el-tag>
          <span v-else>0</span>
        </span></el-col>
        <el-col :span="12"><span class="k">配置版本</span><span class="v">{{ status.version == null ? '-' : status.version }}</span></el-col>
        <el-col :span="12"><span class="k">最近加载</span><span class="v">{{ status.lastLoadTime || '-' }}</span></el-col>
      </el-row>
      <div class="tip">
        新增或修改光猫 yaml 后点击「热加载」即可生效，无需重启服务；
        若已开启文件监听，保存后会自动加载。
      </div>
    </el-card>

    <el-card shadow="never" class="section">
      <div slot="header">
        档案匹配测试
        <span class="sub-tip">按光猫上报的标识预判会命中哪个档案</span>
      </div>
      <el-form :inline="true" size="small">
        <el-form-item label="厂商">
          <el-input v-model="matchForm.manufacturer" placeholder="Manufacturer" clearable style="width:140px" />
        </el-form-item>
        <el-form-item label="OUI">
          <el-input v-model="matchForm.oui" placeholder="序列号前 6 位" clearable style="width:130px" />
        </el-form-item>
        <el-form-item label="产品型号">
          <el-input v-model="matchForm.productClass" placeholder="ProductClass" clearable style="width:140px" />
        </el-form-item>
        <el-form-item label="软件版本">
          <el-input v-model="matchForm.softwareVersion" placeholder="SoftwareVersion" clearable style="width:150px" />
        </el-form-item>
        <el-form-item label="序列号">
          <el-input v-model="matchForm.serialNumber" placeholder="SerialNumber" clearable style="width:170px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="el-icon-search" @click="handleMatch">匹配</el-button>
        </el-form-item>
      </el-form>
      <div v-if="matchResult" class="match-result">
        <el-tag :type="matchResult.matched ? 'success' : 'warning'" size="small">
          {{ matchResult.matched ? '已命中' : '未命中任何档案' }}
        </el-tag>
        <pre v-if="matchResult.matched">{{ formatJson(matchResult.profile) }}</pre>
      </div>
    </el-card>

    <el-card shadow="never" class="section">
      <div slot="header">
        光猫适配档案
        <el-button type="text" style="float:right;padding:0" icon="el-icon-refresh" @click="load">刷新</el-button>
      </div>
      <el-table v-loading="loading" :data="profiles" border size="small" style="width:100%">
        <el-table-column label="标识" prop="key" min-width="180" show-overflow-tooltip />
        <el-table-column label="名称" prop="name" min-width="180" show-overflow-tooltip />
        <el-table-column label="运营商" prop="isp" width="90" />
        <el-table-column label="版本" prop="version" width="80" />
        <el-table-column label="上报端口" prop="acsPort" width="90" />
        <el-table-column label="优先级" prop="priority" width="80" />
        <el-table-column label="来源文件" prop="sourceFile" min-width="180" show-overflow-tooltip />
        <el-table-column label="操作" width="80" fixed="right">
          <template slot-scope="{ row }">
            <el-button type="text" size="mini" @click="showDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog title="档案详情" :visible.sync="detailVisible" width="760px">
      <pre>{{ formatJson(detail) }}</pre>
      <span slot="footer">
        <el-button @click="detailVisible = false">关闭</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import {
  fetchConfigStatus,
  fetchProfiles,
  matchProfile,
  reloadConfig,
  unpackSamples
} from '@/api/worklist'

export default {
  name: 'WorklistProfiles',
  data() {
    return {
      loading: false,
      reloadLoading: false,
      status: {},
      profiles: [],
      detail: null,
      detailVisible: false,
      matchForm: {
        manufacturer: '',
        oui: '',
        productClass: '',
        softwareVersion: '',
        serialNumber: ''
      },
      matchResult: null
    }
  },
  created() {
    this.load()
    this.loadStatus()
  },
  methods: {
    async load() {
      this.loading = true
      try {
        const res = await fetchProfiles()
        this.profiles = res.data || []
      } catch (e) {
        this.profiles = []
      } finally {
        this.loading = false
      }
    },
    async loadStatus() {
      try {
        const res = await fetchConfigStatus()
        this.status = res.data || {}
      } catch (e) {
        this.status = {}
      }
    },
    async handleReload() {
      this.reloadLoading = true
      try {
        const res = await reloadConfig()
        const r = res.data || {}
        this.$message.success(
          '重载完成：光猫档案 ' + r.profileTotal + ' 个，工单模板 ' + r.worklistTotal + ' 个，耗时 ' + r.durationMs + 'ms'
        )
      } catch (e) {
        this.$message.error('重载完成但存在解析失败，请查看后端日志')
      } finally {
        this.reloadLoading = false
        this.load()
        this.loadStatus()
      }
    },
    async handleSamples() {
      try {
        const res = await unpackSamples()
        this.$message.success('已释放 ' + (res.data || 0) + ' 个示例配置')
        this.load()
        this.loadStatus()
      } catch (e) {
        /* 拦截器已提示 */
      }
    },
    async handleMatch() {
      try {
        const res = await matchProfile(this.matchForm)
        this.matchResult = res.data || null
      } catch (e) {
        this.matchResult = null
      }
    },
    showDetail(row) {
      this.detail = row
      this.detailVisible = true
    },
    formatJson(value) {
      if (!value) return '-'
      if (typeof value === 'string') {
        try {
          return JSON.stringify(JSON.parse(value), null, 2)
        } catch (e) {
          return value
        }
      }
      try {
        return JSON.stringify(value, null, 2)
      } catch (e) {
        return String(value)
      }
    }
  }
}
</script>

<style scoped>
.status-card {
  margin-bottom: 16px;
}
.section {
  margin-bottom: 16px;
}
.kv .k {
  display: inline-block;
  width: 88px;
  color: #909399;
}
.kv .v {
  color: #303133;
  word-break: break-all;
}
.kv .el-col {
  margin-bottom: 8px;
}
.tip {
  margin-top: 8px;
  color: #909399;
  font-size: 12px;
}
.sub-tip {
  margin-left: 8px;
  color: #909399;
  font-size: 12px;
  font-weight: normal;
}
.match-result {
  margin-top: 8px;
}
pre {
  max-height: 480px;
  overflow: auto;
  background: #f5f7fa;
  padding: 12px;
  border-radius: 4px;
  margin: 8px 0 0;
}
</style>
