<template>
  <div class="app-container">
    <div class="toolbar">
      <span class="title">后端控制台实时日志</span>
      <el-tag size="small" :type="connected ? 'success' : 'danger'">
        {{ connected ? '已连接' : '未连接' }}
      </el-tag>
      <span class="tip">打开本页建立会话并开始推送，离开页面自动结束会话</span>
      <div class="actions">
        <el-switch
          v-model="autoscroll"
          active-text="自动滚动"
          style="margin-right:16px"
        />
        <el-switch
          v-model="paused"
          active-text="暂停接收"
          style="margin-right:16px"
        />
        <el-button
          size="small"
          :type="connected ? 'danger' : 'primary'"
          icon="el-icon-connection"
          @click="connected ? disconnect() : connect()"
        >{{ connected ? '断开' : '连接' }}</el-button>
        <el-button size="small" icon="el-icon-delete" @click="clear">清屏</el-button>
      </div>
    </div>

    <div
      ref="console"
      class="console"
      @scroll="onScroll"
    >
      <div v-if="lines.length === 0" class="empty">暂无日志，等待后端输出…</div>
      <div
        v-for="(item, index) in visibleLines"
        :key="index"
        class="line"
        :class="'level-' + item.level.toLowerCase()"
      >{{ item.text }}</div>
    </div>
    <div class="footer">共 {{ lines.length }} 条 / 已渲染 {{ visibleLines.length }} 条（最多保留 2000 条）</div>
  </div>
</template>

<script>
import { createRealtimeLogSocket } from '@/utils/realtimeLogSocket'

const MAX_LINES = 2000
/** 单条日志文本、级别由后端拼好，这里只渲染与配色 */
export default {
  name: 'SystemRealtimeLogs',
  data() {
    return {
      lines: [],
      connected: false,
      paused: false,
      autoscroll: true,
      stickBottom: true,
      socket: null,
      /** 暂停期间积压的日志，恢复后一次性合入 */
      pendingLines: []
    }
  },
  computed: {
    visibleLines() {
      return this.lines
    }
  },
  watch: {
    /** 恢复接收时把暂停期间积压的日志一次性补上 */
    paused(val) {
      if (!val && this.pendingLines.length) {
        this.lines.push(...this.pendingLines.splice(0))
        if (this.lines.length > MAX_LINES) {
          this.lines.splice(0, this.lines.length - MAX_LINES)
        }
        this.scrollToBottom()
      }
    }
  },
  mounted() {
    this.connect()
  },
  beforeDestroy() {
    this.disconnect()
  },
  methods: {
    connect() {
      this.disconnect()
      this.socket = createRealtimeLogSocket(
        msg => this.onMessage(msg),
        online => {
          this.connected = online
          if (online) this.pendingLines = []
        }
      )
    },
    disconnect() {
      if (this.socket) {
        this.socket.close()
        this.socket = null
      }
      this.connected = false
    },
    onMessage(msg) {
      if (!msg || !msg.type) return
      if (msg.type === 'realtime-log-backlog') {
        this.lines = (msg.data || []).slice(-MAX_LINES)
        this.scrollToBottom()
      } else if (msg.type === 'realtime-log') {
        const line = msg.data
        if (!line || !line.text) return
        if (this.paused) {
          if (this.pendingLines.length < MAX_LINES) this.pendingLines.push(line)
          return
        }
        this.append(line)
      }
    },
    append(line) {
      this.lines.push(line)
      if (this.lines.length > MAX_LINES) {
        this.lines.splice(0, this.lines.length - MAX_LINES)
      }
      if (this.autoscroll && this.stickBottom) {
        this.scrollToBottom()
      }
    },
    clear() {
      this.lines = []
      this.pendingLines = []
    },
    onScroll() {
      const el = this.$refs.console
      this.stickBottom = el.scrollTop + el.clientHeight >= el.scrollHeight - 10
    },
    scrollToBottom() {
      this.$nextTick(() => {
        const el = this.$refs.console
        if (el) el.scrollTop = el.scrollHeight
      })
    }
  }
}
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  margin-bottom: 12px;
}
.title {
  font-size: 15px;
  font-weight: 600;
  margin-right: 12px;
}
.tip {
  margin-left: 12px;
  color: #909399;
  font-size: 12px;
}
.actions {
  margin-left: auto;
}
.console {
  height: calc(100vh - 220px);
  overflow: auto;
  background: #1e1e1e;
  border-radius: 4px;
  padding: 10px 12px;
  font-family: Consolas, Monaco, 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.6;
}
.empty {
  color: #666;
  text-align: center;
  margin-top: 40px;
}
.line {
  white-space: pre-wrap;
  word-break: break-all;
  color: #d4d4d4;
}
.level-info { color: #d4d4d4; }
.level-warn { color: #e6a23c; }
.level-error { color: #f56c6c; }
.level-debug { color: #909399; }
.level-trace { color: #606266; }
.footer {
  margin-top: 8px;
  color: #909399;
  font-size: 12px;
}
</style>
