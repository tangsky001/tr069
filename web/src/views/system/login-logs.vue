<template>
  <div class="app-container">
    <!-- 三个筛选：地址、时间、标识。条件送后端查库，不在当前这一批里过滤 -->
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

    <el-table v-loading="loading" :data="logs" border size="small" style="margin-top:12px">
      <el-table-column label="登录名" width="130">
        <template slot-scope="{ row }">{{ row.username || '-' }}</template>
      </el-table-column>
      <el-table-column prop="ip" label="登录地址" width="140" />
      <el-table-column label="登录标识" width="100" align="center">
        <template slot-scope="{ row }">
          <el-tag size="mini" :type="tagType(row.source)">{{ row.sourceText }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="结果" width="140">
        <template slot-scope="{ row }">
          <el-tag size="mini" :type="row.success ? 'success' : 'danger'">{{ row.resultText }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="客户端标识" min-width="220">
        <template slot-scope="{ row }">
          <span class="ua">{{ row.userAgent || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="createdAtText" label="登录时间" width="170" />
    </el-table>
  </div>
</template>

<script>
import { fetchLoginLogs } from '@/api/system'

/**
 * 登录日志。
 *
 * <p>筛选条件（地址 / 来源 / 时间段）直接送后端查库，页面不再先拉最近 200 条
 * 再前端过滤——那样子筛出来的行数与库里对不上，时间区间一拉长就缺记录。
 * 来源中文名、结果文案、时间格式也都由后端给，页面只管上色。</p>
 */
export default {
  name: 'SystemLoginLogs',
  data() {
    return {
      logs: [],
      loading: false,
      ip: '',
      source: '',
      range: null,
      /** 输入防抖：连续敲地址时不要每个字符查一次库 */
      reloadTimer: null
    }
  },
  watch: {
    ip() {
      this.reloadLater()
    },
    source() {
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
      const ip = (this.ip || '').trim()
      if (ip) params.ip = ip
      if (this.source) params.source = this.source
      if (this.range && this.range.length === 2) {
        params.from = this.paramTime(this.range[0])
        params.to = this.paramTime(this.range[1])
      }
      fetchLoginLogs(params)
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
      this.ip = ''
      this.source = ''
      this.range = null
      this.load()
    },
    /** 标签颜色是渲染：来源只决定颜色，文字用后端给的 sourceText */
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
