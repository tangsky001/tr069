<template>
  <el-dialog
    title="一键开局"
    :visible.sync="dialogVisible"
    width="660px"
    append-to-body
    :close-on-click-modal="false"
    @open="onOpen"
  >
    <div class="quick-desc">
      把开局要做的几件事放在一个弹窗里一次下发。默认已经勾选
      <b>所有 PON 口和 GE 口</b>，端口 VLAN 模式在下面按业务选（access / hybrid / trunk 各有用处），
      确认端口清单和模式没问题直接点「下发」。
    </div>

    <div v-if="!meta" class="quick-empty">正在读取这台设备的开局选项…</div>

    <el-form v-else label-width="120px" size="small">
      <div class="quick-section">
        <el-checkbox v-model="form.portModeEnabled">① 统一端口 VLAN 模式</el-checkbox>
        <span class="quick-section-hint">端口默认是 hybrid，开局一般全改成 trunk</span>
      </div>

      <template v-if="form.portModeEnabled">
        <el-form-item label="端口">
          <div v-if="!portGroups.length" class="quick-empty">
            还没采到端口数据，先点上面的「同步」刷新一下。
          </div>
          <div v-for="group in portGroups" :key="group.type" class="quick-port-group">
            <el-checkbox
              class="quick-group-all"
              :value="groupChecked(group)"
              :indeterminate="groupIndeterminate(group)"
              @change="checked => toggleGroup(group, checked)"
            >
              全选{{ group.title }}（{{ group.ports.length }}）
            </el-checkbox>
            <el-checkbox-group v-model="form.portKeys">
              <el-checkbox v-for="port in group.ports" :key="port.key" :label="port.key">
                {{ port.label }}
              </el-checkbox>
            </el-checkbox-group>
          </div>
        </el-form-item>

        <el-form-item label="VLAN 模式">
          <el-radio-group v-model="form.mode">
            <el-radio v-for="mode in meta.modes" :key="mode" :label="mode">{{ mode }}</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="VLAN">
          <el-input v-model="form.vlanList" :placeholder="vlanPlaceholder" />
          <div class="quick-field-tip">
            {{ vlanTip }}
          </div>
        </el-form-item>
        <el-form-item label=" ">
          <el-checkbox v-model="form.createVlans">
            这些 VLAN 设备上还没有的话，顺带创建出来
          </el-checkbox>
        </el-form-item>
      </template>

      <div class="quick-section">
        <el-checkbox v-model="form.tr069Enabled">③ 创建 TR-069（ACS）模板</el-checkbox>
        <span class="quick-section-hint">填好 ACS 地址，开局时一并下发 onu-tr069-profile add</span>
      </div>

      <template v-if="form.tr069Enabled && tr069Meta">
        <el-form-item label="模板名">
          <el-input
            v-model="form.tr069.profileName"
            :maxlength="tr069Meta.nameMax"
            placeholder="选填，0-32 characters"
          />
        </el-form-item>
        <el-form-item label="模板 ID">
          <el-input-number
            v-model="form.tr069.profileId"
            :min="tr069Meta.profileIdMin"
            :max="tr069Meta.profileIdMax"
            controls-position="right"
          />
          <span class="quick-range">range: {{ tr069Meta.profileIdMin }} - {{ tr069Meta.profileIdMax }}</span>
        </el-form-item>
        <el-form-item label="ACS 服务器 URL">
          <el-input v-model="form.tr069.url" :maxlength="tr069Meta.urlMax" placeholder="http://192.168.1.10:9090" />
          <span class="quick-range">ex: 1 - {{ tr069Meta.urlMax }} characters</span>
        </el-form-item>
        <el-form-item label="ACS 服务器用户名">
          <el-input
            v-model="form.tr069.acsUsername"
            :maxlength="tr069Meta.fieldMax"
            placeholder="选填，0 - 24 characters"
          />
        </el-form-item>
        <el-form-item label="ACS 服务器密码">
          <el-input
            v-model="form.tr069.acsPassword"
            :maxlength="tr069Meta.fieldMax"
            show-password
            placeholder="选填，0 - 24 characters"
          />
        </el-form-item>
        <el-form-item label="连接用户名">
          <el-input
            v-model="form.tr069.username"
            :maxlength="tr069Meta.fieldMax"
            placeholder="选填，0 - 24 characters"
          />
        </el-form-item>
        <el-form-item label="连接密码">
          <el-input
            v-model="form.tr069.password"
            :maxlength="tr069Meta.fieldMax"
            show-password
            placeholder="选填，0 - 24 characters"
          />
        </el-form-item>
        <el-form-item label="连接端口">
          <el-input-number
            v-model="form.tr069.port"
            :min="tr069Meta.portMin"
            :max="tr069Meta.portMax"
            controls-position="right"
          />
          <span class="quick-range">range: {{ tr069Meta.portMin }} - {{ tr069Meta.portMax }}</span>
        </el-form-item>
      </template>
    </el-form>

    <div class="quick-preview">
      <div class="quick-preview-head">
        命令预览
        <span class="quick-preview-count">共 {{ preview.commands.length }} 条</span>
        <span v-if="previewing" class="quick-preview-count">计算中…</span>
      </div>
      <div v-if="preview.warnings.length" class="quick-preview-warn">
        <div v-for="(warning, index) in preview.warnings" :key="index">{{ warning }}</div>
      </div>
      <pre class="quick-preview-body" :class="{ 'is-error': previewError }">{{ previewText }}</pre>
    </div>

    <div slot="footer">
      <el-button size="small" @click="dialogVisible = false">取消</el-button>
      <el-button size="small" type="primary" :loading="submitting" @click="submit">下发</el-button>
    </div>
  </el-dialog>
</template>

<script>
import { previewQuickSetup, fetchQuickSetupForm, fetchTr069Form } from '@/api/olt'

/** 各类端口在页面上叫什么（只是中文名，端口清单本身由后端/采集给） */
const PORT_TITLES = {
  epon: 'PON 口',
  gpon: 'PON 口',
  ge: 'GE 以太网口',
  xge: '10GE 以太网口'
}

/** 表单改动后隔多久重新算命令：输入 VLAN 时别每敲一个字符就打一次接口 */
const PREVIEW_DELAY_MS = 300

/**
 * 一键开局弹窗。
 *
 * <p><b>这个组件不写任何设备命令。</b>命令怎么排（先建 VLAN 还是先改端口）、
 * 三种模式各用什么写法、VLAN 表达式怎么解析、哪些组合不合理，全由后端算：
 * 打开时取 {@code GET /api/olt/{id}/quick-setup/form} 拿可选模式和默认值，
 * 每次改动调 {@code POST /api/olt/{id}/quick-setup/preview} 拿这次会下发的命令。</p>
 *
 * <p>预览和实际下发走后端同一个 {@code quickSetupCommands}，所以页面上看到的
 * 必然就是即将下发的那条序列。以前前端自己拼一份，命令顺序一改两边就对不上，
 * 出现过「预览看着对、下发被设备拒绝」。</p>
 */
export default {
  name: 'OltQuickSetupDialog',
  props: {
    visible: {
      type: Boolean,
      default: false
    },
    /** 设备 ID，取表单约束和命令预览要用 */
    oltId: {
      type: [Number, String],
      default: null
    },
    /**
     * 可选端口，来自已经采到的成员端口清单：{ type: 'epon'|'ge'|'xge', no: 1, label: 'PON01' }
     */
    ports: {
      type: Array,
      default: () => []
    },
    /** 下发回调，返回 Promise；没给就只提示「后端还没接入」 */
    submitHandler: {
      type: Function,
      default: null
    }
  },
  data() {
    return {
      submitting: false,
      previewing: false,
      /** 开局表单的取值约束与默认值，来自后端 */
      meta: null,
      /** TR-069 段的默认值与长度/范围限制，来自后端 */
      tr069Meta: null,
      /** 后端算出来的命令和提醒 */
      preview: { commands: [], warnings: [] },
      previewError: '',
      form: {
        portModeEnabled: true,
        portKeys: [],
        mode: 'trunk',
        vlanList: '',
        // 一个 VLAN 输入框、一个「顺带创建」开关：放通和创建说的是同一批 VLAN，
        // 分成两个输入框会出现「填了允许的 VLAN 却忘了建」这种开局事故
        createVlans: true,
        tr069Enabled: false,
        tr069: {}
      }
    }
  },
  computed: {
    dialogVisible: {
      get() {
        return this.visible
      },
      set(value) {
        this.$emit('update:visible', value)
      }
    },
    /** 同名端口按类型分组，页面按组展示 + 给个「全选」 */
    portGroups() {
      const groups = []
      const index = {}
      const ports = this.ports || []
      ports.forEach(port => {
        const type = port.type || ''
        if (!index[type]) {
          index[type] = { type, title: PORT_TITLES[type] || type, ports: [] }
          groups.push(index[type])
        }
        index[type].ports.push({
          key: port.type + ':' + port.no,
          type: port.type,
          no: port.no,
          label: port.label || port.type + port.no
        })
      })
      return groups
    },
    /** 勾选的端口还原成 {type, no}，保持页面上的顺序 */
    selectedPorts() {
      const ports = []
      this.portGroups.forEach(group => {
        group.ports.forEach(port => {
          if (this.form.portKeys.includes(port.key)) {
            ports.push({ type: port.type, no: port.no })
          }
        })
      })
      return ports
    },
    vlanPlaceholder() {
      return this.form.mode === 'access'
        ? '如 41；access 端口只属于一个 VLAN'
        : '如 41,45-46；留空表示只改模式，不动 VLAN'
    },
    vlanTip() {
      return this.form.mode === 'access'
        ? 'access 端口只配这一个 VLAN。设备上还没有的，勾下面一条一并建出来。'
        : '端口放通/归属的 VLAN。设备上还没有的，勾下面一条一并建出来。'
    },
    previewText() {
      if (this.previewError) {
        return this.previewError
      }
      if (!this.preview.commands.length) {
        return '（没有选中任何操作）'
      }
      return this.preview.commands.join('\n')
    }
  },
  watch: {
    // 表单一变就让后端重新算一遍命令，页面不再自己拼
    form: {
      deep: true,
      handler() {
        this.schedulePreview()
      }
    }
  },
  beforeDestroy() {
    if (this.previewTimer) {
      clearTimeout(this.previewTimer)
    }
  },
  methods: {
    async onOpen() {
      await this.loadMeta()
      this.reset()
    },
    async loadMeta() {
      if (!this.oltId) {
        return
      }
      try {
        // 两个接口各管一段：开局表单给模式/端口/默认值，TR-069 表单给长度和范围限制
        const [setup, tr069] = await Promise.all([
          fetchQuickSetupForm(this.oltId),
          fetchTr069Form(this.oltId)
        ])
        // 接口回的是 { code, data }，这里取里面的 data——取错一层会让
        // modes 变成 undefined，页面上「VLAN 模式」就只剩个标题、一个可选项都没有
        this.meta = setup.data || null
        this.tr069Meta = tr069.data || null
      } catch (e) {
        const message = e && e.message ? e.message : '读取开局选项失败'
        this.$message.error(message)
      }
    },
    reset() {
      const defaults = this.meta && this.meta.tr069Defaults ? this.meta.tr069Defaults : {}
      const types = this.meta && this.meta.defaultPortTypes ? this.meta.defaultPortTypes : []
      this.form = {
        portModeEnabled: true,
        portKeys: (this.ports || [])
          .filter(port => types.includes(port.type))
          .map(port => port.type + ':' + port.no),
        mode: (this.meta && this.meta.defaultMode) || 'trunk',
        vlanList: '',
        createVlans: true,
        tr069Enabled: false,
        tr069: { ...defaults }
      }
    },
    groupChecked(group) {
      return group.ports.every(port => this.form.portKeys.includes(port.key))
    },
    groupIndeterminate(group) {
      const hit = group.ports.filter(port => this.form.portKeys.includes(port.key)).length
      return hit > 0 && hit < group.ports.length
    },
    toggleGroup(group, checked) {
      const keys = new Set(this.form.portKeys)
      group.ports.forEach(port => {
        if (checked) {
          keys.add(port.key)
        } else {
          keys.delete(port.key)
        }
      })
      this.form.portKeys = Array.from(keys)
    },
    schedulePreview() {
      if (this.previewTimer) {
        clearTimeout(this.previewTimer)
      }
      this.previewTimer = setTimeout(() => this.runPreview(), PREVIEW_DELAY_MS)
    },
    /** 让后端把这次要下发的命令算出来；填错的地方也由后端指出 */
    async runPreview() {
      if (!this.oltId) {
        return
      }
      this.previewing = true
      try {
        const result = await previewQuickSetup(this.oltId, this.payload())
        // 接口回的是 { code, data }：取错一层会变成「什么都算不出来」，
        // 页面上明明勾了端口填了 VLAN，点下发却提示「没有可下发的命令」
        const data = result.data || {}
        this.preview = {
          commands: data.commands || [],
          warnings: data.warnings || []
        }
        this.previewError = ''
      } catch (e) {
        // 后端校验没过：预览区直接显示原因，命令清空，下发按钮也就点不动
        this.preview = { commands: [], warnings: [] }
        this.previewError = e && e.message ? e.message : '命令预演失败'
      } finally {
        this.previewing = false
      }
    },
    /** 提交给后端的就是页面上的选择，命令怎么编由后端决定 */
    payload() {
      const vlanText = this.form.portModeEnabled ? String(this.form.vlanList || '').trim() : ''
      return {
        portModeEnabled: this.form.portModeEnabled,
        ports: this.selectedPorts,
        mode: this.form.mode,
        // 放通和创建用的是同一份 VLAN 清单，勾了「顺带创建」就把它们也建出来
        vlanList: vlanText,
        createVlanIds: this.form.createVlans ? vlanText : '',
        addCreatedToPorts: this.form.createVlans,
        tr069: this.form.tr069Enabled ? this.tr069Payload() : null
      }
    },
    tr069Payload() {
      const form = this.form.tr069
      return {
        profileId: form.profileId,
        profileName: String(form.profileName || '').trim(),
        url: String(form.url || '').trim(),
        acsUsername: String(form.acsUsername || '').trim(),
        acsPassword: form.acsPassword,
        username: String(form.username || '').trim(),
        password: form.password,
        port: form.port
      }
    },
    submit() {
      // 命令和校验都在后端：预览算不出来（填错了）就不下发
      if (this.previewError) {
        this.$message.warning(this.previewError)
        return
      }
      if (!this.preview.commands.length) {
        const first = this.preview.warnings.length ? this.preview.warnings[0] : '没有可下发的命令'
        this.$message.warning(first)
        return
      }
      if (!this.submitHandler) {
        this.$message.info('一键开局的后端接口还没接入，当前只做命令预览')
        return
      }
      const data = this.payload()
      this.$confirm('将对设备下发 ' + this.preview.commands.length + ' 条命令，确认开局？', '一键开局', {
        confirmButtonText: '下发',
        cancelButtonText: '取消',
        type: 'warning'
      })
        .then(() => {
          this.submitting = true
          return this.submitHandler(data)
        })
        .then(() => {
          this.dialogVisible = false
        })
        .catch(() => {})
        .finally(() => {
          this.submitting = false
        })
    }
  }
}
</script>

<style lang="scss" scoped>
@import '~@/styles/olt.scss';

.quick-desc {
  padding: 8px 12px;
  margin-bottom: 12px;
  color: #5a6b7d;
  font-size: 12px;
  line-height: 1.6;
  background: #f5f8fc;
  border-left: 3px solid #c0d4ea;
}

.quick-section {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 0;

  &-hint {
    color: #9aa7b4;
    font-size: 12px;
  }
}

.quick-empty {
  color: #9aa7b4;
  font-size: 12px;
}

.quick-port-group {
  padding: 4px 0;
  border-bottom: 1px dashed #e6ebf0;

  &:last-child {
    border-bottom: none;
  }
}

.quick-group-all {
  margin-right: 8px;
  font-weight: 600;
}

.quick-preview {
  margin-top: 12px;
  border: 1px solid #dfe6ee;
  border-radius: 4px;
}

.quick-preview-head {
  padding: 4px 10px;
  background: #f5f8fc;
  color: #4a5b6c;
  font-size: 12px;
}

.quick-preview-count {
  margin-left: 8px;
  color: #9aa7b4;
}

/* 后端给的提醒：不是错误，但不说清楚容易以为是漏配 */
.quick-preview-warn {
  padding: 6px 10px;
  color: #8a6d3b;
  font-size: 12px;
  line-height: 1.6;
  background: #fcf8e3;
  border-bottom: 1px solid #f5e6c0;
}

/* 输入框右侧的 range / ex 提示，和参考图一样灰字靠右 */
.quick-range {
  margin-left: 8px;
  color: #9aa7b4;
  font-size: 12px;
}

/* 输入框下方的说明，一行灰字 */
.quick-field-tip {
  color: #9aa7b4;
  font-size: 12px;
  line-height: 1.5;
}

.quick-preview-body {
  max-height: 220px;
  margin: 0;
  padding: 10px;
  overflow: auto;
  color: #2c3e50;
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;

  /* 校验没过时预览区显示的是后端给的原因 */
  &.is-error {
    color: #c0392b;
  }
}
</style>
