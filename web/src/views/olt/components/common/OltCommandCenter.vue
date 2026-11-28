<template>
  <div class="cmd-center">
    <div class="cmd-bar">
      <div class="cmd-tip">
        功能逐条对照《EPON 光纤接入局端设备 命令行操作手册 V3.0.2》，选好参数点「下发」；
        查询类动作把设备回显直接贴在下面，配置类下发成功后会重新采集一次。
      </div>
      <el-button
        size="small"
        type="primary"
        icon="el-icon-magic-stick"
        @click="quickVisible = true"
      >
        一键开局
      </el-button>
    </div>

    <div v-if="!actions.length" class="olt-empty">
      这台设备没有接入命令动作目录。
    </div>

    <el-collapse v-else v-model="openChapters">
      <el-collapse-item v-for="group in groups" :key="group.chapter" :name="group.chapter">
        <template slot="title">
          <span class="cmd-chapter">{{ group.chapter }}</span>
          <span class="cmd-count">{{ group.items.length }} 项</span>
        </template>

        <div v-for="action in group.items" :key="action.code" class="cmd-item">
          <div class="cmd-head">
            <span class="cmd-label">{{ action.label }}</span>
            <el-tag size="mini" :type="action.query ? 'info' : 'success'">
              {{ action.query ? '查询' : '配置' }}
            </el-tag>
            <el-tag v-if="action.danger" size="mini" type="danger">高风险</el-tag>
            <span class="cmd-view">{{ viewText(action.view) }}</span>
          </div>
          <div v-if="action.note" class="cmd-note">{{ action.note }}</div>

          <div class="cmd-line">
            <div v-for="p in action.params" :key="p.key" class="cmd-field">
              <span class="cmd-field-label">{{ p.label }}</span>
              <el-select
                v-if="p.type === 'enum'"
                v-model="values[action.code][p.key]"
                size="mini"
                class="cmd-input"
              >
                <el-option v-for="option in p.options" :key="option" :label="option" :value="option" />
              </el-select>
              <el-input
                v-else
                v-model="values[action.code][p.key]"
                size="mini"
                class="cmd-input"
                :placeholder="p.hint || ''"
              />
            </div>
            <el-button
              size="mini"
              type="primary"
              :loading="running === action.code"
              @click="run(action)"
            >
              下发
            </el-button>
          </div>

          <div class="cmd-preview">{{ preview(action) }}</div>
        </div>
      </el-collapse-item>
    </el-collapse>

    <div v-if="output" class="cmd-output">
      <div class="cmd-output-head">
        <span>{{ output.title }}</span>
        <el-button type="text" size="mini" @click="output = null">清空</el-button>
      </div>
      <pre class="cmd-output-body">{{ output.text }}</pre>
    </div>

    <!-- 一键开局弹窗：把开局常用几步合成一次下发 -->
    <olt-quick-setup-dialog
      :visible.sync="quickVisible"
      :olt-id="oltId"
      :ports="ports"
      :submit-handler="setupHandler"
    />
  </div>
</template>

<script>
import OltQuickSetupDialog from '../setup/OltQuickSetupDialog'
import { fetchOltOptions, previewOltActions } from '@/api/olt'

/** 设备配置（命令动作中心）：按后端动作目录分组渲染参数表单，预览并下发命令；新增命令只改后端目录 */
export default {
  name: 'OltCommandCenter',
  components: { OltQuickSetupDialog },
  props: {
    oltId: {
      type: [Number, String],
      default: null
    },

    /** 动作目录，来自 GET /api/olt/{id}/actions */
    actions: {
      type: Array,
      default: () => []
    },
    /** 执行动作，返回 Promise<{query, outputs, commands}> */
    execHandler: {
      type: Function,
      default: null
    },
    /** 端口清单 { type, no, label }，一键开局的端口勾选用 */
    ports: {
      type: Array,
      default: () => []
    },
    /** 一键开局下发回调；为空时弹窗只做命令预览 */
    setupHandler: {
      type: Function,
      default: null
    }
  },
  data() {
    return {
      openChapters: [],
      values: {},
      running: '',
      output: null,
      quickVisible: false,
      /** 视图中文名等码表，来自 GET /api/olt/options */
      viewLabels: null,
      /** 命令文本：后端按执行侧同一套规则渲染，键是动作编号 */
      previews: {},
      /** 参数改动后的防抖定时器 */
      previewTimer: null,
      /** 请求序号，只认最后一次返回 */
      previewSeq: 0
    }
  },
  computed: {
    groups() {
      const map = new Map()
      this.actions.forEach(action => {
        if (!map.has(action.chapter)) {
          map.set(action.chapter, [])
        }
        map.get(action.chapter).push(action)
      })
      return Array.from(map, ([chapter, items]) => ({ chapter, items }))
    }
  },
  watch: {
    actions: {
      immediate: true,
      handler() {
        this.resetValues()
        this.openChapters = this.groups.length ? [this.groups[0].chapter] : []
        this.schedulePreviews()
      }
    },
    // 参数一动就重算预览（防抖 400ms）
    values: {
      deep: true,
      handler() {
        this.schedulePreviews()
      }
    }
  },
  beforeDestroy() {
    if (this.previewTimer) {
      clearTimeout(this.previewTimer)
    }
  },
  created() {
    fetchOltOptions()
      .then(res => {
        this.viewLabels = res.data && res.data.viewLabels ? res.data.viewLabels : null
      })
      .catch(() => {
        this.viewLabels = null
      })
  },
  methods: {
    /** 每个动作一份表单值，默认值取参数定义里的 defaultValue */
    resetValues() {
      const values = {}
      this.actions.forEach(action => {
        const form = {}
        const params = action.params || []
        params.forEach(param => {
          form[param.key] = param.defaultValue || ''
        })
        values[action.code] = form
      })
      this.values = values
    },
    paramsOf(action) {
      return this.values[action.code] || {}
    },
    /** 视图叫什么由后端给（码表在 GET /api/olt/options 里） */
    viewText(view) {
      return (this.viewLabels && this.viewLabels[view]) || view
    },
    /** 参数改动后防抖 400ms 再算预览 */
    schedulePreviews() {
      if (this.previewTimer) {
        clearTimeout(this.previewTimer)
      }
      this.previewTimer = setTimeout(() => this.requestPreviews(), 400)
    },
    /** 整页表单一次送后端，拿回每条动作的命令文本 */
    requestPreviews() {
      if (!this.oltId || !this.actions.length) {
        return
      }
      const items = this.actions.map(action => ({
        code: action.code,
        params: this.paramsOf(action)
      }))
      const seq = ++this.previewSeq
      previewOltActions(this.oltId, items)
        .then(res => {
          // 已有更新的一次响应，丢掉过期结果
          if (seq === this.previewSeq) {
            this.previews = (res && res.data) || {}
          }
        })
        .catch(() => {})
    },
    /** 命令预览文本，全部由后端渲染 */
    preview(action) {
      return this.previews[action.code] || ''
    },
    run(action) {
      if (!this.execHandler) {
        return
      }
      const params = this.paramsOf(action)
      const paramsDef = action.params || []
      const missing = paramsDef.some(param => {
        return param.required && !String(params[param.key] || '').trim()
      })
      if (missing) {
        this.$message.warning('请把参数填完整')
        return
      }
      const exec = () => {
        this.running = action.code
        this.execHandler({ code: action.code, params })
          .then(data => {
            const outputs = (data && data.outputs) || []
            this.output = {
              title: action.label + (data && data.query ? '（查询回显）' : '（下发回显）'),
              text: outputs.length ? outputs.join('\n') : '设备没有回显'
            }
          })
          .finally(() => {
            this.running = ''
          })
      }
      if (action.danger) {
        this.$confirm('「' + action.label + '」会改动设备上的既有配置，确认执行？', '高风险操作', {
          confirmButtonText: '执行',
          cancelButtonText: '取消',
          type: 'warning'
        })
          .then(exec)
          .catch(() => {})
        return
      }
      exec()
    }
  }
}
</script>

<style lang="scss" scoped>
@import '~@/styles/olt.scss';

.cmd-bar {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-bottom: 10px;
}
.cmd-tip {
  flex: 1;
  padding: 8px 12px;
  color: #5a6b7d;
  font-size: 12px;
  line-height: 1.6;
  background: #f5f8fc;
  border-left: 3px solid #c0d4ea;
}
.cmd-chapter {
  font-weight: 600;
  color: #2c3e50;
}
.cmd-count {
  margin-left: 8px;
  color: #9aa7b4;
  font-size: 12px;
}
.cmd-item {
  padding: 8px 0;
  border-bottom: 1px dashed #e6ebf0;

  &:last-child {
    border-bottom: none;
  }
}
.cmd-head {
  display: flex;
  align-items: center;
  gap: 6px;
}
.cmd-label {
  font-size: 13px;
  color: #2c3e50;
}
.cmd-view {
  color: #9aa7b4;
  font-size: 12px;
}
.cmd-note {
  margin-top: 2px;
  color: #9aa7b4;
  font-size: 12px;
}
.cmd-line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
}
.cmd-field {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.cmd-field-label {
  color: #6b7c8d;
  font-size: 12px;
}
.cmd-input {
  width: 140px;
}
.cmd-preview {
  margin-top: 4px;
  color: #7f8c9a;
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  word-break: break-all;
}
.cmd-output {
  margin-top: 12px;
  border: 1px solid #dfe6ee;
  border-radius: 4px;
}
.cmd-output-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 10px;
  background: #f5f8fc;
  color: #4a5b6c;
  font-size: 12px;
}
.cmd-output-body {
  max-height: 320px;
  margin: 0;
  padding: 10px;
  overflow: auto;
  color: #2c3e50;
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
