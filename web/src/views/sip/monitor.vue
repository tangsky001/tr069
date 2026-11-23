<template>
  <div class="app-container">
    <el-card shadow="never" style="margin-bottom:16px">
      <div slot="header" class="card-header">
        <span>活跃呼叫</span>
        <div>
          <el-tag size="mini" :type="calls.length ? 'warning' : 'info'">{{ calls.length }} 路</el-tag>
          <el-switch
            v-model="autoRefresh"
            active-text="自动刷新(10s)"
            style="margin-left:12px"
            @change="onAutoChange"
          />
        </div>
      </div>
      <el-table v-loading="loading" :data="calls" border size="small">
        <el-table-column label="主叫" prop="caller" width="120" align="center" />
        <el-table-column label="被叫" prop="callee" width="120" align="center" />
        <el-table-column label="状态" width="120" align="center">
          <template slot-scope="{ row }">
            <el-tag size="mini" :type="row.state === 'ACTIVE' ? 'success' : 'warning'">
              {{ row.state === 'ACTIVE' ? '通话中' : '振铃中' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="通话时长" width="150" align="center">
          <template slot-scope="{ row }">{{ formatDuration(row.durationSeconds) }}</template>
        </el-table-column>
        <el-table-column label="说明">
          <span class="tip-line">挂断后录音写入服务端 data/sip-records/，可在「通话记录」页下载</span>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never">
      <div slot="header" class="card-header">
        <span>H.248 网关（G.711A）</span>
        <el-tag size="mini" :type="gateways.length ? 'success' : 'info'">{{ gateways.length }} 台</el-tag>
      </div>
      <el-table v-loading="loading" :data="gateways" border size="small">
        <el-table-column label="网关地址" width="220">
          <template slot-scope="{ row }">
            <span class="mono">{{ row.address }}</span>
          </template>
        </el-table-column>
        <el-table-column label="协议版本" prop="version" width="120" align="center" />
        <el-table-column label="注册时长" width="130" align="center">
          <template slot-scope="{ row }">{{ formatDuration(row.registeredSeconds) }}</template>
        </el-table-column>
        <el-table-column label="终结点配置（分机号 → 终结点）">
          <template slot-scope="{ row }">
            <span class="mono">{{ formatTerminals(row.terminals) }}</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script>
import { fetchSipCalls, fetchSipGateways } from '@/api/sip'

/** 实时监控：活跃呼叫 + H.248 网关注册状态，10 秒轮询 */
export default {
  name: 'SipMonitor',
  data() {
    return {
      calls: [],
      gateways: [],
      loading: false,
      autoRefresh: true,
      pollTimer: null
    }
  },
  created() {
    this.load()
    this.startPoll()
  },
  beforeDestroy() {
    this.stopPoll()
  },
  methods: {
    load() {
      this.loading = true
      Promise.all([fetchSipCalls(), fetchSipGateways()])
        .then(([callRes, gwRes]) => {
          this.calls = callRes.data || []
          this.gateways = gwRes.data || []
        })
        .catch(() => {})
        .then(() => {
          this.loading = false
        })
    },
    formatTerminals(terminals) {
      if (!terminals || !Object.keys(terminals).length) return '-'
      return Object.keys(terminals).map(k => `${k}→${terminals[k]}`).join('， ')
    },
    startPoll() {
      this.stopPoll()
      if (this.autoRefresh) {
        this.pollTimer = setInterval(() => this.load(), 10000)
      }
    },
    stopPoll() {
      if (this.pollTimer) {
        clearInterval(this.pollTimer)
        this.pollTimer = null
      }
    },
    onAutoChange() {
      this.startPoll()
    },
    formatDuration(sec) {
      const pad = n => String(n).padStart(2, '0')
      const m = Math.floor(sec / 60)
      return pad(m) + ':' + pad(sec % 60)
    }
  }
}
</script>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.mono {
  color: #606266;
  font-size: 12px;
  font-family: Consolas, Menlo, monospace;
}
.tip-line {
  color: #909399;
  font-size: 12px;
}
</style>
