<template>
  <div class="app-container">
    <!-- 三个筛选：地址、时间、标识，筛的是拉回来的这批，不清数据 -->
    <el-input
      v-model="ip"
      placeholder="登录地址"
      size="small"
      clearable
      style="width:170px"
    />
    <el-date-picker
      v-model="range"
      type="datetimerange"
      size="small"
      clearable
      start-placeholder="登录开始时间"
      end-placeholder="登录结束时间"
      style="width:340px;margin-left:10px"
    />
    <el-select
      v-model="source"
      placeholder="登录标识"
      size="small"
      clearable
      style="width:130px;margin-left:10px"
    >
      <el-option label="网页端" value="WEB" />
      <el-option label="客户端" value="CLIENT" />
      <el-option label="移动端" value="MOBILE" />
    </el-select>
    <el-button size="small" icon="el-icon-refresh" style="margin-left:10px" @click="load">刷新</el-button>
    <el-button size="small" type="text" @click="reset">清空条件</el-button>
    <span class="tip">连着一片失败是有人在试密码</span>

    <el-table v-loading="loading" :data="rows" border size="small" style="margin-top:12px">
      <el-table-column label="登录名" width="130">
        <template slot-scope="{ row }">{{ row.username || '-' }}</template>
      </el-table-column>
      <el-table-column prop="ip" label="登录地址" width="140" />
      <el-table-column label="登录标识" width="100" align="center">
        <template slot-scope="{ row }">
          <el-tag size="mini" :type="tagType(row.source)">{{ sourceText(row.source) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="结果" width="140">
        <template slot-scope="{ row }">
          <el-tag size="mini" :type="row.success ? 'success' : 'danger'">
            {{ row.success ? '成功' : (row.failReason || '失败') }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="客户端标识" min-width="220">
        <template slot-scope="{ row }">
          <span class="ua">{{ row.userAgent || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="登录时间" width="170">
        <template slot-scope="{ row }">{{ row.createdAt ? String(row.createdAt).replace('T', ' ') : '-' }}</template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script>
import { fetchLoginLogs } from '@/api/system'

const SOURCE_TEXT = { WEB: '网页端', CLIENT: '客户端', MOBILE: '移动端' }

export default {
  name: 'SystemLoginLogs',
  data() {
    return {
      logs: [],
      loading: false,
      ip: '',
      source: '',
      range: null
    }
  },
  computed: {
    rows() {
      const ip = this.ip.trim().toLowerCase()
      const [from, to] = this.range || []
      return this.logs.filter(r => {
        if (ip && (r.ip || '').toLowerCase().indexOf(ip) < 0) return false
        if (this.source && r.source !== this.source) return false
        if (from || to) {
          const t = new Date(r.createdAt).getTime()
          // 认不出时间的不往结果里塞，省得一条脏数据把整个区间搞乱
          if (!t) return false
          if (from && t < from.getTime()) return false
          if (to && t > to.getTime()) return false
        }
        return true
      })
    }
  },
  created() {
    this.load()
  },
  methods: {
    load() {
      this.loading = true
      fetchLoginLogs({ limit: 200 })
        .then(res => { this.logs = res.data || [] })
        .catch(() => {})
        .then(() => { this.loading = false })
    },
    reset() {
      this.ip = ''
      this.source = ''
      this.range = null
    },
    sourceText(source) {
      return SOURCE_TEXT[source] || (source || '-')
    },
    tagType(source) {
      if (source === 'MOBILE') return 'warning'
      if (source === 'CLIENT') return 'info'
      return ''
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
.ua {
  color: #606266;
  font-size: 12px;
}
</style>
