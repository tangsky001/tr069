<template>
  <div>
    <div class="olt-section-title">
      VLAN 列表
      <span class="vlan-tip-inline">
        采集自 <code>show running-config</code>；成员端口取 vlan 视图里的
        <code>port epon/ge &lt;端口&gt; tagged|untagged</code>
      </span>
    </div>

    <el-table
      v-if="vlans.length"
      ref="table"
      :data="vlans"
      size="small"
      border
      @selection-change="rows => selected = rows"
    >
      <el-table-column type="selection" width="46" align="center" />
      <el-table-column label="VLAN ID" prop="vlanId" width="100" align="center" />
      <el-table-column label="Tagged 端口" min-width="170">
        <template slot-scope="{ row }">
          <span v-if="row.taggedPorts" class="vlan-text">{{ row.taggedPorts }}</span>
          <span v-else class="vlan-tip-inline">无</span>
        </template>
      </el-table-column>
      <el-table-column label="Untagged 端口" min-width="170">
        <template slot-scope="{ row }">
          <span v-if="row.untaggedPorts" class="vlan-text">{{ row.untaggedPorts }}</span>
          <span v-else class="vlan-tip-inline">无</span>
        </template>
      </el-table-column>
      <el-table-column label="缺省 VLAN 端口" min-width="170">
        <template slot-scope="{ row }">
          <span v-if="row.defaultVlanPorts" class="vlan-text">{{ row.defaultVlanPorts }}</span>
          <span v-else class="vlan-tip-inline">无</span>
        </template>
      </el-table-column>
    </el-table>
    <div v-else class="olt-empty">{{ emptyText }}</div>

    <div class="vlan-footer">
      <el-button type="text" icon="el-icon-plus" class="vlan-link" @click="openCreate">新增 VLAN</el-button>
      <span class="vlan-footer-right">
        <el-button type="text" class="vlan-link" @click="toggleAllRows(true)">全选</el-button>
        <el-button type="text" class="vlan-link" @click="toggleAllRows(false)">全不选</el-button>
        <el-button
          type="text"
          icon="el-icon-delete"
          class="vlan-row-danger"
          :disabled="!selected.length"
          @click="removeSelected"
        />
      </span>
    </div>

    <!--
      新建 VLAN：填 VLAN ID + 勾 tagged / untagged 端口。
      下发顺序是 vlan standard 100 → port epon 1,2 tagged → exit，一个 VLAN 一轮。
    -->
    <el-dialog
      title="创建"
      :visible.sync="dialogVisible"
      width="660px"
      :close-on-click-modal="false"
      append-to-body
    >
      <div class="vlan-form">
        <div class="vlan-form-row">
          <label class="vlan-form-label">VLAN ID:</label>
          <div class="vlan-form-body">
            <el-input
              v-model="form.vlanIds"
              size="small"
              class="vlan-id-input"
              placeholder="100 或 100-200,300"
              @keyup.enter.native="save"
            />
            <div v-if="meta" class="vlan-form-hint">
              请输入{{ meta.vlanIdMin }}到{{ meta.vlanIdMax }}之间的数字,可以使用,或-连接
            </div>
          </div>
        </div>

        <div v-if="!memberPorts.length" class="vlan-form-row">
          <label class="vlan-form-label" />
          <div class="vlan-form-body">
            <div class="vlan-form-hint vlan-form-warn">
              端口清单还没采到（同步一次就有），现在只能建 VLAN，勾不了端口。
            </div>
          </div>
        </div>

        <div v-for="mode in modes" :key="mode.key" class="vlan-form-row">
          <label class="vlan-form-label">{{ mode.key }}:</label>
          <div class="vlan-form-body">
            <div v-if="memberPorts.length" class="vlan-port-all">
              <el-checkbox
                :value="isAllPicked(mode.key)"
                :indeterminate="isPartPicked(mode.key)"
                @change="checked => togglePorts(mode.key, checked)"
              >
                全部
              </el-checkbox>
            </div>
            <el-checkbox-group
              v-model="form[mode.key]"
              class="vlan-port-grid"
              @change="() => exclusive(mode.key)"
            >
              <template v-for="group in portGroups">
                <el-checkbox
                  v-for="port in group.ports"
                  :key="group.key + port.no"
                  :label="port.type + port.no"
                >
                  {{ port.label }}
                </el-checkbox>
              </template>
            </el-checkbox-group>
          </div>
        </div>
      </div>

      <div class="vlan-preview">
        <div v-if="preview.warnings.length" class="vlan-preview-warn">
          <div v-for="(warning, index) in preview.warnings" :key="index">{{ warning }}</div>
        </div>
        <pre :class="{ 'is-error': previewError }">{{ previewText }}</pre>
      </div>

      <div slot="footer" class="vlan-dialog-foot">
        <el-button size="small" @click="dialogVisible = false">取消</el-button>
        <el-button size="small" type="success" :loading="saving" @click="save">确认</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { fetchVlanForm, previewVlan } from '@/api/olt'

/**
 * 鸿升光 VLAN 面板：VLAN 列表（含成员端口）+ 新增 / 删除。
 *
 * <p>这台的 VLAN 概念和华为完全不是一路：一条 {@code vlan standard 100} 建好 VLAN，
 * 再进 vlan 视图 {@code port epon 2 untagged} 把端口加进去，删除是 {@code no vlan 100}。
 * 没有 vlanif 三层接口、静态路由那一套，所以不复用通用的四表面板。</p>
 *
 * <p>创建弹窗按「VLAN ID + 勾 tagged / untagged 端口」填，勾选项来自设备采集的端口清单
 * （{@code show port state all}），勾不到的口不会发出去。GPON 那台 PON 口不做二层成员，
 * 清单里只有上联口。</p>
 */
export default {
  name: 'OltHsgqVlanPanel',
  props: {
    /** 设备 ID，取 VLAN 约束和命令预览要用 */
    oltId: {
      type: [Number, String],
      default: null
    },
    vlans: {
      type: Array,
      default: () => []
    },
    /** 可以做 VLAN 成员的端口（来自设备采集）：[{ type: 'epon', no: 1, label: 'PON01' }] */
    memberPorts: {
      type: Array,
      default: () => []
    },
    /** 新增 VLAN（{ vlanIds, tagged, untagged }），返回 Promise */
    createHandler: {
      type: Function,
      default: null
    },
    /** 删除 VLAN（ID 数组），返回 Promise */
    deleteHandler: {
      type: Function,
      default: null
    },
    emptyText: {
      type: String,
      default: '尚未采集到 VLAN。点上面的「同步」采一次（采集时跑 show running-config），也可以直接点下面「新增 VLAN」下发。'
    }
  },
  data() {
    return {
      selected: [],
      dialogVisible: false,
      saving: false,
      modes: [
        { key: 'tagged' },
        { key: 'untagged' }
      ],
      form: { vlanIds: '', tagged: [], untagged: [] },
      /** VLAN ID 范围、一次最多几个、哪些端口能做成员，来自后端 */
      meta: null,
      preview: { commands: [], warnings: [] },
      previewError: ''
    }
  },
  computed: {
    /** 端口按类型分组：PON 一组、GE 一组、XGE 一组，每组每行摆 4 个 */
    portGroups() {
      // 分组顺序和「哪些类型能做成员」由后端给，页面不自己列类型
      const order = this.meta ? this.meta.portTypes : []
      const groups = new Map()
      this.memberPorts.forEach(port => {
        if (!port || !port.type) {
          return
        }
        const key = String(port.type).toLowerCase()
        if (!groups.has(key)) {
          groups.set(key, { key, ports: [] })
        }
        groups.get(key).ports.push(port)
      })
      return [...groups.values()].sort((a, b) => order.indexOf(a.key) - order.indexOf(b.key))
    },
    allPortKeys() {
      return this.memberPorts
        .filter(port => port && port.type && port.no != null)
        .map(port => String(port.type).toLowerCase() + port.no)
    },
    previewText() {
      if (this.previewError) {
        return this.previewError
      }
      if (!this.preview.commands.length) {
        return '（填好 VLAN ID 后显示要下发的命令）'
      }
      return this.preview.commands.join('\n')
    }
  },
  watch: {
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
    async openCreate() {
      await this.loadMeta()
      this.form = { vlanIds: '', tagged: [], untagged: [] }
      this.dialogVisible = true
    },
    loadMeta() {
      if (this.meta || !this.oltId) {
        return Promise.resolve()
      }
      return fetchVlanForm(this.oltId)
        .then(res => {
          this.meta = res.data
        })
        .catch(() => {
          this.$message.error('读取 VLAN 参数失败')
        })
    },
    schedulePreview() {
      if (this.previewTimer) {
        clearTimeout(this.previewTimer)
      }
      this.previewTimer = setTimeout(() => this.runPreview(), 300)
    },
    /** 让后端按页面填的内容把命令算出来；写法不对、数量超了都由后端指出 */
    async runPreview() {
      if (!this.oltId || !String(this.form.vlanIds || '').trim()) {
        this.preview = { commands: [], warnings: [] }
        this.previewError = ''
        return
      }
      try {
        const result = await previewVlan(this.oltId, {
          vlanIds: this.form.vlanIds,
          tagged: this.form.tagged,
          untagged: this.form.untagged
        })
        // 命令在 { code, data } 的 data 里
        const data = result.data || {}
        this.preview = { commands: data.commands || [], warnings: data.warnings || [] }
        this.previewError = ''
      } catch (e) {
        this.preview = { commands: [], warnings: [] }
        this.previewError = e && e.message ? e.message : '命令预演失败'
      }
    },
    isAllPicked(mode) {
      return this.allPortKeys.length > 0 && this.allPortKeys.every(key => this.form[mode].includes(key))
    },
    isPartPicked(mode) {
      const picked = this.form[mode].length
      return picked > 0 && !this.isAllPicked(mode)
    },
    togglePorts(mode, checked) {
      this.form[mode] = checked ? [...this.allPortKeys] : []
      this.exclusive(mode)
    },
    /** tagged 和 untagged 互斥：同一个口不能两种都占，勾一边就把另一边摘掉 */
    exclusive(mode) {
      // 有不有这条限制由后端说（这台设备只认一种带标签方式）
      if (this.meta && !this.meta.exclusiveTagMode) {
        return
      }
      const other = mode === 'tagged' ? 'untagged' : 'tagged'
      const picked = new Set(this.form[mode])
      this.form[other] = this.form[other].filter(key => !picked.has(key))
    },
    /**
     * 下发：命令和校验都在后端，这里先让后端算一遍——
     * 表达式写法、VLAN ID 范围、一次最多几个都不在页面判断，写错了后端会给原因。
     */
    async save() {
      const text = (this.form.vlanIds || '').trim()
      if (!text) {
        this.$message.warning('请填写 VLAN ID，例如 100 或 100-200,300')
        return
      }
      await this.runPreview()
      if (this.previewError) {
        this.$message.warning(this.previewError)
        return
      }
      if (!this.createHandler) {
        return
      }
      this.saving = true
      this.createHandler({ vlanIds: text, tagged: this.form.tagged, untagged: this.form.untagged })
        .then(() => {
          this.dialogVisible = false
        })
        .finally(() => {
          this.saving = false
        })
    },
    removeSelected() {
      const vlanIds = this.selected.map(row => row.vlanId).filter(id => id != null)
      if (!vlanIds.length || !this.deleteHandler) {
        return
      }
      this.$confirm(
        '删除 VLAN ' + vlanIds.join('、') + '？该 VLAN 上的端口成员配置会一起删掉，设备立即生效。',
        '删除 VLAN',
        { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
      )
        .then(() => this.deleteHandler(vlanIds))
        .then(() => {
          this.selected = []
        })
        .catch(() => {})
    },
    toggleAllRows(flag) {
      const table = this.$refs.table
      if (!table) {
        return
      }
      table.clearSelection()
      if (flag) {
        this.vlans.forEach(row => table.toggleRowSelection(row, true))
      }
    }
  }
}
</script>

<style lang="scss" scoped>
@import '~@/styles/olt.scss';

.vlan-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 2px 0;
}
.vlan-footer-right {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.vlan-link {
  padding: 0 4px;
}
.vlan-id-input {
  width: 260px;
}
.vlan-form-row {
  display: flex;
  align-items: flex-start;
  margin-bottom: 10px;
}
.vlan-form-label {
  flex: 0 0 78px;
  padding-top: 7px;
  color: #303133;
  text-align: left;
}
.vlan-form-body {
  flex: 1;
  min-width: 0;
}
.vlan-form-hint {
  margin-top: 4px;
  color: #7f8a99;
  font-size: 12px;
  line-height: 1.5;
}
.vlan-form-warn {
  color: #e6a23c;
}
.vlan-port-all {
  margin-bottom: 2px;
}
/* 每行 4 个口，PON 与 GE 各占一片，8 个 GE 正好两行 */
.vlan-port-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(96px, 1fr));
  gap: 2px 8px;
}
.vlan-port-grid ::v-deep .el-checkbox {
  margin-right: 0;
}
.vlan-preview {
  margin: 8px 0 0;
  border: 1px solid #dfe6ee;
  border-radius: 4px;

  pre {
    margin: 0;
    padding: 8px 10px;
    max-height: 160px;
    overflow: auto;
    color: #2c3e50;
    font-family: Consolas, Monaco, monospace;
    font-size: 12px;
    line-height: 1.5;
    white-space: pre-wrap;
    word-break: break-all;

    /* 校验没过时这里显示的是后端给的原因 */
    &.is-error {
      color: #c0392b;
    }
  }
}
.vlan-preview-warn {
  padding: 6px 10px;
  color: #8a6d3b;
  font-size: 12px;
  line-height: 1.6;
  background: #fcf8e3;
  border-bottom: 1px solid #f5e6c0;
}
.vlan-dialog-foot {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 24px;
}
</style>
