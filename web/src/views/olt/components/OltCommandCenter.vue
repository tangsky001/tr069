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

    <!-- 一键开局：批量下发开局要做的那几条命令，默认把所有 PON 和 GE 口设成 trunk -->
    <olt-quick-setup-dialog
      :visible.sync="quickVisible"
      :ports="ports"
      :submit-handler="setupHandler"
    />
  </div>
</template>

<script>
import OltQuickSetupDialog from './OltQuickSetupDialog'

/**
 * 设备配置（命令动作中心）。
 *
 * 手册 13 章几百条命令，不可能一条一个页面，所以后端把它们登记成「动作目录」：
 * 每条动作带章节、命令模板、参数定义、在哪个视图执行、是不是查询类。
 * 这个组件只做三件事：按章节分组、按参数定义生成表单项、把渲染出来的命令和回显贴出来。
 *
 * 新增命令只改后端（HsgqActionCatalog），这里不用动。
 *
 * 右上角「一键开局」是把开局常用的几步合成一次下发，见 {@link OltQuickSetupDialog}。
 */
export default {
  name: 'OltCommandCenter',
  components: { OltQuickSetupDialog },
  props: {
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
    /** 可选端口清单，「一键开局」的端口勾选框用它：{ type, no, label } */
    ports: {
      type: Array,
      default: () => []
    },
    /** 一键开局的下发回调，返回 Promise；后端还没接时留空，弹窗只做命令预览 */
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
      quickVisible: false
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
      }
    }
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
    viewText(view) {
      const map = {
        CONFIG: '全局配置视图',
        VLAN: 'VLAN 视图',
        PORT: '端口视图',
        VLANIF: '三层接口视图'
      }
      return map[view] || view
    },
    /** 把模板里的占位符换成当前填的值，让用户先看清要发什么命令 */
    preview(action) {
      const params = this.paramsOf(action)
      const lines = []
      if (action.view === 'VLAN') {
        lines.push('vlan standard ' + (params.vlanId || '{vlanId}'))
      } else if (action.view === 'PORT') {
        lines.push('interface ' + (params.portType || '{portType}') + ' ' + (params.portNo || '{portNo}'))
      } else if (action.view === 'VLANIF') {
        lines.push('interface vlanif ' + (params.vlanId || '{vlanId}'))
      }
      const commands = action.commands || []
      commands.forEach(template => {
        lines.push(template.replace(/\{(\w+)\}/g, (all, key) => {
          return params[key] ? params[key] : all
        }))
      })
      if (action.view !== 'CONFIG') {
        lines.push('exit')
      }
      return lines.join('  →  ')
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
