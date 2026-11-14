<template>
  <div class="app-container">
    <!-- 筛选：模块、操作人、关键字、时间段，全部送后端查库 -->
    <el-select
      v-model="module"
      placeholder="模块"
      size="small"
      clearable
      style="width:130px"
    >
      <el-option label="用户" value="用户" />
      <el-option label="分组" value="分组" />
      <el-option label="设备权限" value="设备权限" />
    </el-select>
    <el-input
      v-model="username"
      placeholder="操作人"
      size="small"
      clearable
      style="width:140px;margin-left:10px"
    />
    <el-input
      v-model="keyword"
      placeholder="动作 / 详情关键字"
      size="small"
      clearable
      style="width:200px;margin-left:10px"
    />
    <el-date-picker
      v-model="range"
      type="datetimerange"
      size="small"
      clearable
      start-placeholder="开始时间"
      end-placeholder="结束时间"
      style="width:340px;margin-left:10px"
    />
    <el-button size="small" icon="el-icon-refresh" style="margin-left:10px" @click="load">刷新</el-button>
    <el-button size="small" type="text" @click="reset">清空条件</el-button>
    <span class="tip">记录：改密码、建/删分组、移动设备、ACL 授权等关键操作</span>

    <el-table v-loading="loading" :data="logs" border size="small" style="margin-top:12px">
      <el-table-column label="操作人" width="120">
        <template slot-scope="{ row }">{{ row.username || '-' }}</template>
      </el-table-column>
      <el-table-column label="模块" width="100" align="center">
        <template slot-scope="{ row }">
          <el-tag size="mini" :type="moduleType(row.module)">{{ row.module }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="action" label="动作" width="130" />
      <el-table-column prop="detail" label="详情" min-width="380" show-overflow-tooltip />
      <el-table-column prop="ip" label="来源 IP" width="140" />
      <el-table-column prop="createdAtText" label="操作时间" width="170" />
    </el-table>
  </div>
</template>

<script>
import { fetchOperLogs } from '@/api/system'

/**
 * 操作审计日志页：后端在关键动作处落库（sys_oper_log），
 * 这里只做查询与筛选，字段文案由后端给。
 */
export default {
  name: 'SystemOperLogs',
  data() {
    return {
      logs: [],
      loading: false,
      module: '',
      username: '',
      keyword: '',
      range: null,
      /** 输入防抖：连续敲条件时不要每个字符查一次库 */
      reloadTimer: null
    }
  },
  watch: {
    module() {
      this.reloadLater()
    },
    username() {
      this.reloadLater()
    },
    keyword() {
      this.reloadLater()
    },
    range() {
      this.reloadLater()
    }
  },
  created() {
    this.load()
  },
  beforeDestroy() {
    if (this.reloadTimer) {
      clearTimeout(this.reloadTimer)
    }
  },
  methods: {
    load() {
      this.loading = true
      const params = { limit: 200 }
      const username = (this.username || '').trim()
      const keyword = (this.keyword || '').trim()
      if (this.module) params.module = this.module
      if (username) params.username = username
      if (keyword) params.keyword = keyword
      if (this.range && this.range.length === 2) {
        params.from = this.paramTime(this.range[0])
        params.to = this.paramTime(this.range[1])
      }
      fetchOperLogs(params)
        .then(res => {
          this.logs = res.data || []
        })
        .catch(() => {})
        .then(() => {
          this.loading = false
        })
    },
    /** 条件改成 300ms 后再查，避免一边打字一边打库 */
    reloadLater() {
      if (this.reloadTimer) {
        clearTimeout(this.reloadTimer)
      }
      this.reloadTimer = setTimeout(() => this.load(), 300)
    },
    /** Date → 后端认的 ISO 本地时间串（不带时区，按服务器本地时间比较） */
    paramTime(date) {
      const pad = n => String(n).padStart(2, '0')
      return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate()) +
        'T' + pad(date.getHours()) + ':' + pad(date.getMinutes()) + ':' + pad(date.getSeconds())
    },
    reset() {
      this.module = ''
      this.username = ''
      this.keyword = ''
      this.range = null
      this.load()
    },
    /** 模块只决定标签颜色 */
    moduleType(module) {
      if (module === '分组') return 'success'
      if (module === '设备权限') return 'warning'
      if (module === 'OLT') return 'danger'
      return 'info'
    }
  }
}
</script>

<style scoped>
.tip {
  margin-left: 10px;
  color: #909399;
  font-size: 12px;
}
</style>
