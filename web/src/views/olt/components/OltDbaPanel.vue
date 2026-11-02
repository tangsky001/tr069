<template>
  <div>
    <div v-if="!dbaProfiles.length && !lineProfiles.length" class="olt-empty">{{ emptyText }}</div>

    <template v-else>
      <!-- 1. DBA 模板：控制每个 ONU 上行带宽的档位表 -->
      <div class="dba-title">
        DBA（动态带宽分配）模板，用于控制每个ONU 上行时的带宽。
        <el-tooltip content="display dba-profile all 采集；固定/保证/最大带宽单位 kbps" placement="top">
          <i class="el-icon-info dba-info" />
        </el-tooltip>
      </div>
      <el-table
        v-if="dbaProfiles.length"
        ref="dbaTable"
        :data="dbaProfiles"
        size="small"
        border
        @selection-change="rows => dbaSelected = rows"
      >
        <el-table-column type="selection" width="46" align="center" />
        <el-table-column label="模板ID" prop="profileId" width="90" align="center" />
        <el-table-column label="模板名" prop="name" min-width="140" />
        <el-table-column label="DBA 类型" prop="type" width="100" align="center" />
        <el-table-column label="固定带宽大小 kbps" width="140" align="center">
          <template slot-scope="{ row }">{{ rawKbps(row.fixKbps) }}</template>
        </el-table-column>
        <el-table-column label="保证带宽大小 kbps" width="140" align="center">
          <template slot-scope="{ row }">{{ rawKbps(row.assureKbps) }}</template>
        </el-table-column>
        <el-table-column label="最大带宽大小 kbps" width="140" align="center">
          <template slot-scope="{ row }">{{ rawKbps(row.maxKbps) }}</template>
        </el-table-column>
      </el-table>
      <div v-else class="vlan-empty-row">没有 DBA 模板</div>
      <div v-if="dbaProfiles.length || true" class="dba-footer">
        <el-button type="text" icon="el-icon-plus" class="dba-link" @click="openCreate">新增DBA模板</el-button>
        <span class="dba-footer-right">
          <el-button type="text" class="dba-link" @click="toggleAllDba(true)">全选</el-button>
          <el-button type="text" class="dba-link" @click="toggleAllDba(false)">全不选</el-button>
          <el-button
            type="text"
            icon="el-icon-delete"
            class="dba-danger"
            :disabled="!dbaSelected.length"
            @click="removeSelected"
          />
        </span>
      </div>

      <!-- 2. ONT 线路模板：映射关系直接铺在单元格里，一行一个 GEM port -->
      <div class="dba-title dba-title-line">
        ONT 线路模板
        <el-tooltip content="display ont-lineprofile gpon all 采集；T-CONT 引用 DBA 模板，GEM port 挂在 T-CONT 上按 VLAN 承载业务" placement="top">
          <i class="el-icon-info dba-info" />
        </el-tooltip>
      </div>
      <el-table
        v-if="lineProfiles.length"
        ref="lineTable"
        :data="lineProfiles"
        size="small"
        border
        @selection-change="rows => lineSelected = rows"
      >
        <el-table-column type="selection" width="46" align="center" />
        <el-table-column label="模板ID" prop="profileId" width="90" align="center" />
        <el-table-column label="模板名" min-width="140">
          <template slot-scope="{ row }">
            <div>{{ row.name || '-' }}</div>
            <el-tag v-if="isTr069Profile(row)" size="mini" type="info" class="dba-tag">tr069</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="PON 类型" width="100" align="center">
          <template>gpon</template>
        </el-table-column>
        <el-table-column label="映射关系" min-width="460">
          <template slot-scope="{ row: line }">
            <el-table :data="mappingRows(line)" size="mini" border class="dba-mapping">
              <el-table-column label="T-CONT索引" width="120">
                <template slot-scope="{ row: m }">
                  <span>{{ m.tcontId == null ? '-' : m.tcontId }}</span>
                  <el-button
                    type="text"
                    icon="el-icon-plus"
                    class="dba-plus"
                    title="给这条线路模板加一条 T-CONT（tcont <索引> dba-profile-id <DBA>）"
                    @click="openTcont(line)"
                  />
                </template>
              </el-table-column>
              <el-table-column label="DBA模板" width="110">
                <template slot-scope="{ row: m }">
                  <div>{{ m.dbaProfileId == null ? '-' : m.dbaProfileId }}</div>
                  <div class="dba-sub">{{ dbaName(m.dbaProfileId) }}</div>
                </template>
              </el-table-column>
              <el-table-column label="GEM Port索引" width="120" align="center">
                <template slot-scope="{ row: m }">{{ m.gemportId == null ? '-' : m.gemportId }}</template>
              </el-table-column>
              <el-table-column label="GEM 映射ID" width="120" align="center">
                <template slot-scope="{ row: m }">
                  <span>{{ m.mappingId == null ? '-' : m.mappingId }}</span>
                  <el-button
                    type="text"
                    icon="el-icon-plus"
                    class="dba-plus"
                    :disabled="m.gemportId == null"
                    title="给这个 GEM Port 加映射（gem mapping <GEM> <序号> vlan <VLAN>）"
                    @click="openGemMapping(line, m)"
                  />
                </template>
              </el-table-column>
              <el-table-column label="VLAN">
                <template slot-scope="{ row: m }">{{ m.vlan || '-' }}</template>
              </el-table-column>
            </el-table>
            <el-button type="text" icon="el-icon-plus" class="dba-link" @click="openGemport(line)">
              增加GEM Port
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-else class="vlan-empty-row">没有线路模板</div>
      <div v-if="lineProfiles.length" class="dba-footer">
        <span />
        <span class="dba-footer-right">
          <el-button type="text" class="dba-link" @click="toggleAllLine(true)">全选</el-button>
          <el-button type="text" class="dba-link" @click="toggleAllLine(false)">全不选</el-button>
          <el-button
            type="text"
            icon="el-icon-delete"
            class="dba-danger"
            :disabled="!lineSelected.length"
            @click="notSupported('删除线路模板')"
          />
        </span>
      </div>

      <!-- 新增 DBA 模板：下发 dba-profile add，表单对齐网管 -->
      <el-dialog
        title="新增DBA模板"
        :visible.sync="dialogVisible"
        width="480px"
        :close-on-click-modal="false"
        append-to-body
      >
        <el-form v-if="meta" label-width="110px" size="small" @submit.native.prevent>
          <el-form-item label="模板ID">
            <el-input-number
              v-model="form.profileId"
              :min="meta.profileIdMin"
              :max="meta.profileIdMax"
              :placeholder="meta.profileIdMin + '-' + meta.profileIdMax + ', 不能重复'"
              class="dba-id-input"
            />
          </el-form-item>
          <el-form-item label="模板名">
            <el-input v-model="form.name" placeholder="数字、字母及下划线组成" :maxlength="meta.nameMax" />
          </el-form-item>
          <el-form-item label="DBA 类型">
            <el-select v-model="form.type" style="width: 100%">
              <el-option v-for="type in meta.types" :key="type.code" :label="type.label" :value="type.code" />
            </el-select>
          </el-form-item>
          <!-- 哪几个带宽框该出现由后端按类型决定，页面不认识 type1~type5 的区别 -->
          <el-form-item v-if="needs('fix')" label="固定带宽大小">
            <el-input v-model="form.fixKbps" :placeholder="bandwidthPlaceholder" />
          </el-form-item>
          <el-form-item v-if="needs('assure')" label="保证带宽大小">
            <el-input v-model="form.assureKbps" :placeholder="bandwidthPlaceholder" />
          </el-form-item>
          <el-form-item v-if="needs('max')" label="最大带宽大小">
            <el-input v-model="form.maxKbps" :placeholder="bandwidthPlaceholder" />
          </el-form-item>
        </el-form>
        <div v-else class="olt-empty">正在读取模板参数…</div>

        <div class="dba-preview">
          <div v-if="preview.warnings.length" class="dba-preview-warn">
            <div v-for="(warning, index) in preview.warnings" :key="index">{{ warning }}</div>
          </div>
          <pre :class="{ 'is-error': previewError }">{{ previewText }}</pre>
        </div>
        <div class="dba-dialog-footer">
          <el-button type="primary" :loading="saving" @click="save">保存设置</el-button>
          <el-button type="warning" @click="dialogVisible = false">关闭</el-button>
        </div>
      </el-dialog>

      <!-- 线路模板 · 增加 T-CONT：tcont <索引> dba-profile-id <DBA 模板> -->
      <el-dialog
        title="增加 T-CONT"
        :visible.sync="tcontDialog.visible"
        width="460px"
        :close-on-click-modal="false"
        append-to-body
      >
        <div class="dba-form-row">
          <span class="dba-form-label">线路模版：</span>
          <div class="dba-form-value">{{ lineForm ? lineForm.profileLabel : '-' }}</div>
        </div>
        <div class="dba-form-row">
          <span class="dba-form-label">T-CONT 索引（{{ tcontRange }}）：</span>
          <el-input v-model="tcontDialog.tcontId" :placeholder="hintOf('tcont')" />
        </div>
        <div class="dba-form-row">
          <span class="dba-form-label">DBA 模板：</span>
          <el-select v-model="tcontDialog.dbaProfileId" placeholder="选这条 T-CONT 用的 DBA 模板" style="width: 100%">
            <el-option
              v-for="item in dbaOptionsOf"
              :key="item.profileId"
              :label="item.label"
              :value="item.profileId"
            />
          </el-select>
        </div>
        <div class="dba-preview">
          <pre>{{ linePreviewText }}</pre>
        </div>
        <div class="dba-dialog-footer">
          <el-button type="primary" :loading="submitting" @click="submitTcont">提交</el-button>
        </div>
      </el-dialog>

      <!-- 线路模板 · 新增 GEM Port：gem add <GEM 索引> eth tcont <T-CONT> encrypt on -->
      <el-dialog
        title="新增 GEM Port"
        :visible.sync="gemportDialog.visible"
        width="460px"
        :close-on-click-modal="false"
        append-to-body
      >
        <div class="dba-form-row">
          <span class="dba-form-label">线路模版：</span>
          <div class="dba-form-value">{{ lineForm ? lineForm.profileLabel : '-' }}</div>
        </div>
        <div class="dba-form-row">
          <span class="dba-form-label">T-CONT 索引（{{ tcontRange }}）：</span>
          <el-input v-model="gemportDialog.tcontId" :placeholder="hintOf('tcont')" />
        </div>
        <div class="dba-form-row">
          <span class="dba-form-label">GEM Port 索引（{{ gemportRange }}）：</span>
          <el-input v-model="gemportDialog.gemportId" :placeholder="hintOf('gemport')" />
        </div>
        <div class="dba-preview">
          <pre>{{ linePreviewText }}</pre>
        </div>
        <div class="dba-dialog-footer">
          <el-button type="primary" :loading="submitting" @click="submitGemport">提交</el-button>
        </div>
      </el-dialog>

      <!-- 线路模板 · 增加 GEM 映射：gem mapping <GEM> <映射序号> vlan <VLAN> -->
      <el-dialog
        title="增加 GEM 映射"
        :visible.sync="gemMappingDialog.visible"
        width="480px"
        :close-on-click-modal="false"
        append-to-body
      >
        <div class="dba-form-row">
          <span class="dba-form-label">线路模版：</span>
          <div class="dba-form-value">{{ lineForm ? lineForm.profileLabel : '-' }}</div>
        </div>
        <div class="dba-form-row">
          <span class="dba-form-label">GEM Port 索引：</span>
          <div class="dba-form-value">{{ gemMappingDialog.gemportId }}</div>
        </div>
        <div class="dba-form-row">
          <span class="dba-form-label">GEM 映射索引（{{ mappingRange }}）：</span>
          <el-input v-model="gemMappingDialog.mappingIds" :placeholder="hintOf('mapping')" />
        </div>
        <div class="dba-form-row">
          <span class="dba-form-label">业务 VLAN（1-4094，不可重复）：</span>
          <el-input v-model="gemMappingDialog.vlans" placeholder="多个 VLAN 用逗号分隔，和 GEM 映射索引一一对应" />
        </div>
        <div class="dba-preview">
          <pre>{{ linePreviewText }}</pre>
        </div>
        <div class="dba-dialog-footer">
          <el-button type="primary" :loading="submitting" @click="submitGemMapping">提交</el-button>
        </div>
      </el-dialog>
    </template>
  </div>
</template>

<script>
/**
 * DBA 模板与线路模板面板（对齐网管的展示）。
 *
 * DBA 表：模板ID / 名称 / 类型 / 固定·保证·最大带宽（kbps 原样数字）。
 * 线路模板表：映射关系内嵌在单元格里——每行一个 GEM port，顺带把承载它的
 * T-CONT 与那条 T-CONT 引用的 DBA 模板（ID + 名称）对上；没有 GEM 的模板
 * 退回只列 T-CONT 行。
 * 下发都是真实的：DBA 模板走 dba-profile add / delete；线路模板里的
 * T-CONT（tcont &lt;索引&gt; dba-profile-id &lt;DBA&gt;）、GEM Port（gem add … tcont …）、
 * GEM 映射（gem mapping &lt;GEM&gt; &lt;序号&gt; vlan &lt;VLAN&gt;）各有一个弹窗。
 * 全部通过 handler 由页面（mixin）执行，成功后后端只重读这条线路模板，页面换上新快照。
 */
import { fetchDbaForm, previewDbaProfile, fetchLineProfileForm, previewLineProfile } from '@/api/olt'

/** 表单改动后隔多久重新算命令 */
const PREVIEW_DELAY_MS = 300

/** 「3」→ 3；空、非整数、负数 → null（弹窗里的索引都是文本框，取值前先过一遍） */
function toIndex(text) {
  if (text == null || String(text).trim() === '') {
    return null
  }
  const value = Number(String(text).trim())
  return Number.isInteger(value) && value >= 0 ? value : null
}

export default {
  name: 'OltDbaPanel',
  props: {
    /** 设备 ID，取表单约束和命令预览要用 */
    oltId: {
      type: [Number, String],
      default: null
    },
    dbaProfiles: {
      type: Array,
      default: () => []
    },
    lineProfiles: {
      type: Array,
      default: () => []
    },
    /** 新增 DBA 模板（表单），返回 Promise，成功后页面自动刷新 */
    createHandler: {
      type: Function,
      default: null
    },
    /** 删除 DBA 模板（ID 数组），返回 Promise */
    deleteHandler: {
      type: Function,
      default: null
    },
    /** 线路模板加 T-CONT（下发 tcont <索引> dba-profile-id <DBA>） */
    tcontHandler: {
      type: Function,
      default: null
    },
    /** 线路模板加 GEM Port（下发 gem add <GEM> eth tcont <T-CONT> encrypt on） */
    gemportHandler: {
      type: Function,
      default: null
    },
    /** 给 GEM Port 加映射（下发 gem mapping <GEM> <序号> vlan <VLAN>） */
    gemMappingHandler: {
      type: Function,
      default: null
    },
    emptyText: {
      type: String,
      default: '尚未采集到 DBA / 线路模板。点上面的「同步」，采集时会跑 display dba-profile all 与 display ont-lineprofile gpon all。'
    }
  },
  data() {
    return {
      dbaSelected: [],
      lineSelected: [],
      dialogVisible: false,
      saving: false,
      /** 五类模板的字段矩阵与取值约束，来自后端 */
      meta: null,
      preview: { commands: [], warnings: [] },
      previewError: '',
      form: {},
      /** 线路模板三个弹窗：下发中统一转圈，防连点 */
      submitting: false,
      tcontDialog: { visible: false, line: null, tcontId: '', dbaProfileId: null },
      gemportDialog: { visible: false, line: null, tcontId: '', gemportId: '' },
      gemMappingDialog: { visible: false, line: null, gemportId: null, mappingIds: '', vlans: '' },
      /**
       * 线路模板弹窗的约束与预览都在后端：页面只拿结果来渲染——
       * 默认值、已用索引、提示文字来自 lineForm，命令来自 linePreview。
       */
      lineForm: null,
      linePreview: { commands: [], warnings: [] },
      linePreviewError: '',
      linePreviewTimer: null
    }
  },
  computed: {
    dbaMap() {
      const map = {}
      this.dbaProfiles.forEach(profile => {
        map[profile.profileId] = profile
      })
      return map
    },
    /** 当前类型要填哪几个带宽字段：矩阵由后端给，页面不认识 type1~type5 */
    currentFields() {
      const hit = this.meta && this.meta.types
        ? this.meta.types.find(type => type.code === this.form.type)
        : null
      return hit ? hit.fields : []
    },
    bandwidthPlaceholder() {
      return this.meta
        ? '单位 kbps，' + this.meta.bandwidthMin + '-' + this.meta.bandwidthMax
        : '单位 kbps'
    },
    previewText() {
      if (this.previewError) {
        return this.previewError
      }
      if (!this.preview.commands.length) {
        return '（填好参数后显示要下发的命令）'
      }
      return this.preview.commands.join('\n')
    },
    /**
     * 弹窗里那三个索引框的取值范围，由后端给（页面上不写死 0-127 这类数字）。
     * 后端还没返回前先用空串，标签上就不显示范围。
     */
    tcontRange() {
      return this.rangeOf('tcont')
    },
    gemportRange() {
      return this.rangeOf('gemport')
    },
    mappingRange() {
      return this.rangeOf('mapping')
    },
    /** 新增 T-CONT 时的 DBA 下拉：选项和默认选中项都由后端算好 */
    dbaOptionsOf() {
      return this.lineForm && this.lineForm.dbaOptions ? this.lineForm.dbaOptions : []
    },
    /** 当前打开的弹窗要预演的参数；三个都没开返回 null */
    linePreviewPayload() {
      const profileIdOf = dialog => (dialog.line ? dialog.line.profileId : null)
      if (this.tcontDialog.visible) {
        return {
          kind: 'tcont',
          profileId: profileIdOf(this.tcontDialog),
          tcontId: this.tcontDialog.tcontId,
          dbaProfileId: this.tcontDialog.dbaProfileId
        }
      }
      if (this.gemportDialog.visible) {
        return {
          kind: 'gemport',
          profileId: profileIdOf(this.gemportDialog),
          tcontId: this.gemportDialog.tcontId,
          gemportId: this.gemportDialog.gemportId
        }
      }
      if (this.gemMappingDialog.visible) {
        return {
          kind: 'mapping',
          profileId: profileIdOf(this.gemMappingDialog),
          gemportId: this.gemMappingDialog.gemportId,
          mappingIds: this.gemMappingDialog.mappingIds,
          vlans: this.gemMappingDialog.vlans
        }
      }
      return null
    },
    /** 后端算好的命令（含提醒）展示到弹窗底部 */
    linePreviewText() {
      if (this.linePreviewError) {
        return this.linePreviewError
      }
      const lines = []
      ;(this.linePreview.warnings || []).forEach(warning => lines.push('注意：' + warning))
      if (!this.linePreview.commands.length) {
        lines.push('（填好参数后显示要下发的命令）')
        return lines.join('\n')
      }
      return lines.concat(this.linePreview.commands).join('\n')
    }
  },
  watch: {
    form: {
      deep: true,
      handler() {
        this.schedulePreview()
      }
    },
    /** 弹窗里改动就重新预演：命令和提醒都由后端算 */
    linePreviewPayload() {
      this.scheduleLinePreview()
    }
  },
  beforeDestroy() {
    if (this.previewTimer) {
      clearTimeout(this.previewTimer)
    }
    if (this.linePreviewTimer) {
      clearTimeout(this.linePreviewTimer)
    }
  },
  methods: {
    needs(field) {
      return this.currentFields.includes(field)
    },
    /** 打开弹窗：默认类型由后端给 */
    async openCreate() {
      await this.loadMeta()
      this.form = {
        profileId: undefined,
        name: '',
        type: this.meta ? this.meta.defaultType : '',
        fixKbps: '',
        assureKbps: '',
        maxKbps: ''
      }
      this.dialogVisible = true
    },
    loadMeta() {
      if (this.meta || !this.oltId) {
        return Promise.resolve()
      }
      return fetchDbaForm(this.oltId)
        .then(res => {
          this.meta = res.data
        })
        .catch(() => {
          this.$message.error('读取 DBA 模板参数失败')
        })
    },
    /**
     * 打开弹窗前先把后端算好的约束取回来：默认值、已占用的索引、灰字提示。
     * 页面不再自己数「哪些号用过了」——那份逻辑在 OltService.lineProfileForm 里。
     */
    loadLineForm(profileId, gemportId) {
      this.lineForm = null
      this.linePreview = { commands: [], warnings: [] }
      this.linePreviewError = ''
      if (!this.oltId || profileId == null) {
        return Promise.resolve()
      }
      return fetchLineProfileForm(this.oltId, profileId, gemportId)
        .then(res => {
          this.lineForm = res.data
        })
        .catch(err => {
          const data = (err && err.response && err.response.data) || {}
          this.$message.error(data.message || '读取线路模板参数失败')
        })
    },
    /** 弹窗里改了就重新预演：命令和提醒都由后端算 */
    scheduleLinePreview() {
      if (this.linePreviewTimer) {
        clearTimeout(this.linePreviewTimer)
      }
      this.linePreviewTimer = setTimeout(() => this.runLinePreview(), PREVIEW_DELAY_MS)
    },
    runLinePreview() {
      const payload = this.linePreviewPayload
      if (!payload || payload.profileId == null) {
        this.linePreview = { commands: [], warnings: [] }
        this.linePreviewError = ''
        return
      }
      previewLineProfile(this.oltId, payload)
        .then(res => {
          const data = res.data || {}
          this.linePreview = { commands: data.commands || [], warnings: data.warnings || [] }
          this.linePreviewError = ''
        })
        .catch(err => {
          const data = (err && err.response && err.response.data) || {}
          this.linePreview = { commands: [], warnings: [] }
          this.linePreviewError = data.message || '命令预演失败'
        })
    },
    /** 索引框的取值范围（标签上那句「0-127」），由后端表单给 */
    rangeOf(kind) {
      const form = this.lineForm ? this.lineForm[kind] : null
      return form ? form.min + '-' + form.max : ''
    },
    /** 索引框里的灰字提示（例如「可用的 GEM Port: 3-1023」），同样是后端给的 */
    hintOf(kind) {
      const form = this.lineForm ? this.lineForm[kind] : null
      return form ? form.hint : ''
    },
    schedulePreview() {
      if (this.previewTimer) {
        clearTimeout(this.previewTimer)
      }
      this.previewTimer = setTimeout(() => this.runPreview(), PREVIEW_DELAY_MS)
    },
    /** 让后端把 dba-profile add 算出来；校验没过也由后端指出 */
    async runPreview() {
      if (!this.oltId || !this.form.type) {
        return
      }
      try {
        const result = await previewDbaProfile(this.oltId, this.payload())
        // 命令在 { code, data } 的 data 里
        const data = result.data || {}
        this.preview = { commands: data.commands || [], warnings: data.warnings || [] }
        this.previewError = ''
      } catch (e) {
        this.preview = { commands: [], warnings: [] }
        this.previewError = e && e.message ? e.message : '命令预演失败'
      }
    },
    payload() {
      const fields = this.currentFields
      return {
        profileId: this.form.profileId,
        name: this.form.name,
        type: this.form.type,
        // 不属于当前类型的字段不提交，免得 type4 还带着个固定带宽
        fixKbps: fields.includes('fix') ? Number(this.form.fixKbps) : null,
        assureKbps: fields.includes('assure') ? Number(this.form.assureKbps) : null,
        maxKbps: fields.includes('max') ? Number(this.form.maxKbps) : null
      }
    },
    /** 命令和校验都在后端：算不出命令就拦下，不再本地判一遍 */
    async save() {
      await this.runPreview()
      if (this.previewError) {
        this.$message.warning(this.previewError)
        return
      }
      if (!this.createHandler) {
        this.$message.info('下发能力未接入')
        return
      }
      this.saving = true
      Promise.resolve(this.createHandler(this.payload()))
        .then(() => {
          this.dialogVisible = false
        })
        .catch(() => {})
        .finally(() => {
          this.saving = false
        })
    },
    removeSelected() {
      const ids = this.dbaSelected.map(row => row.profileId)
      this.$confirm('确定删除选中的 ' + ids.length + ' 条 DBA 模板？删除后 OLT 上绑定的线路模板会受影响。', '删除确认', {
        type: 'warning'
      }).then(() => {
        if (!this.deleteHandler) {
          this.$message.info('下发能力未接入')
          return
        }
        return this.deleteHandler(ids)
      }).catch(() => {})
    },
    /** kbps 原样数字，跟网管上的写法一致（列头已写明单位） */
    rawKbps(kbps) {
      return kbps == null ? '-' : kbps
    },
    dbaName(profileId) {
      const profile = this.dbaMap[profileId]
      return profile && profile.name ? profile.name : ''
    },
    isTr069Profile(row) {
      return /tr069/i.test(row.name || '')
    },
    /**
     * 映射关系一行一个 GEM port，T-CONT 的 DBA 模板顺带对上；
     * 模板还没配 GEM 时退回只列 T-CONT（DBA 模板列照常显示）。
     */
    mappingRows(row) {
      const tcontMap = {}
      ;(row.tconts || []).forEach(t => {
        tcontMap[t.tcontId] = t
      })
      const gemports = row.gemports || []
      if (!gemports.length) {
        return (row.tconts || []).map(t => ({
          tcontId: t.tcontId,
          dbaProfileId: t.dbaProfileId
        }))
      }
      // 一个 GEM port 可挂多条映射：跟网管一致，同 GEM 的映射ID / VLAN 用逗号合并到一行
      const rows = []
      gemports.forEach(g => {
        const dbaProfileId = tcontMap[g.tcontId] ? tcontMap[g.tcontId].dbaProfileId : null
        const mappings = (g.mappings && g.mappings.length)
          ? g.mappings
          : (g.mappingId != null || g.vlan ? [{ index: g.mappingId, vlan: g.vlan }] : [])
        const ids = mappings.map(m => m.index).filter(id => id != null)
        const vlans = mappings.map(m => m.vlan).filter(v => v != null && v !== '')
        rows.push({
          tcontId: g.tcontId,
          dbaProfileId,
          gemportId: g.gemportId,
          // GEM 内映射序号：gem mapping <gemport> <序号> vlan <vlan>
          mappingId: ids.length ? ids.join(',') : null,
          vlan: vlans.length ? vlans.join(',') : null,
          mappingMode: g.mappingMode
        })
      })
      return rows
    },
    toggleAllDba(all) {
      const table = this.$refs.dbaTable
      if (!table) return
      if (all) {
        table.toggleAllSelection()
      } else {
        table.clearSelection()
      }
    },
    toggleAllLine(all) {
      const table = this.$refs.lineTable
      if (!table) return
      if (all) {
        table.toggleAllSelection()
      } else {
        table.clearSelection()
      }
    },
    /** 加 T-CONT：默认值（第一个空号、DBA 下拉）都由后端表单给 */
    openTcont(line) {
      this.tcontDialog = { visible: true, line, tcontId: '', dbaProfileId: null }
      this.loadLineForm(line.profileId, null).then(() => {
        if (!this.lineForm) {
          return
        }
        const next = this.lineForm.tcont.availableFrom
        this.tcontDialog.tcontId = next == null ? '' : String(next)
        this.tcontDialog.dbaProfileId = this.lineForm.defaultDbaId
        this.runLinePreview()
      })
    },
    submitTcont() {
      if (!this.tcontHandler) {
        this.$message.info('下发能力未接入')
        return
      }
      // 页面只做「文本 → 数字」的转换，缺不缺、超没超范围全由后端判
      this.submitting = true
      Promise.resolve(this.tcontHandler({
        profileId: this.tcontDialog.line.profileId,
        tcontId: toIndex(this.tcontDialog.tcontId),
        dbaProfileId: this.tcontDialog.dbaProfileId
      }))
        .then(() => {
          this.tcontDialog.visible = false
        })
        .catch(() => {})
        .finally(() => {
          this.submitting = false
        })
    },
    /**
     * 新增 GEM Port：T-CONT 索引默认填「已配的 GEM Port 挂着的那条」（后端算），
     * GEM Port 索引留空，框里的可用区间提示也是后端给的。
     */
    openGemport(line) {
      this.gemportDialog = { visible: true, line, tcontId: '', gemportId: '' }
      this.loadLineForm(line.profileId, null).then(() => {
        if (!this.lineForm) {
          return
        }
        const value = this.lineForm.tcont.value
        this.gemportDialog.tcontId = value == null ? '' : String(value)
        this.runLinePreview()
      })
    },
    submitGemport() {
      if (!this.gemportHandler) {
        this.$message.info('下发能力未接入')
        return
      }
      this.submitting = true
      Promise.resolve(this.gemportHandler({
        profileId: this.gemportDialog.line.profileId,
        tcontId: toIndex(this.gemportDialog.tcontId),
        gemportId: toIndex(this.gemportDialog.gemportId)
      }))
        .then(() => {
          this.gemportDialog.visible = false
        })
        .catch(() => {})
        .finally(() => {
          this.submitting = false
        })
    },
    /** 加映射：得先有 GEM Port；默认序号由后端按这个 GEM 已占用的算 */
    openGemMapping(line, mapping) {
      if (!mapping || mapping.gemportId == null) {
        this.$message.warning('先给这条模板加 GEM Port，映射才有地方挂')
        return
      }
      this.gemMappingDialog = {
        visible: true,
        line,
        gemportId: mapping.gemportId,
        mappingIds: '',
        vlans: ''
      }
      this.loadLineForm(line.profileId, mapping.gemportId).then(() => {
        if (!this.lineForm) {
          return
        }
        const next = this.lineForm.mapping.availableFrom
        this.gemMappingDialog.mappingIds = next == null ? '' : String(next)
        this.runLinePreview()
      })
    },
    submitGemMapping() {
      if (!this.gemMappingHandler) {
        this.$message.info('下发能力未接入')
        return
      }
      this.submitting = true
      Promise.resolve(this.gemMappingHandler({
        profileId: this.gemMappingDialog.line.profileId,
        gemportId: this.gemMappingDialog.gemportId,
        mappingIds: this.gemMappingDialog.mappingIds,
        vlans: this.gemMappingDialog.vlans
      }))
        .then(() => {
          this.gemMappingDialog.visible = false
        })
        .catch(() => {})
        .finally(() => {
          this.submitting = false
        })
    },
    notSupported(label) {
      this.$message.info(label + '需要向 OLT 下发配置，下发能力稍后接入，当前仅展示')
    }
  }
}
</script>

<style lang="scss" scoped>
@import '~@/styles/olt.scss';

.dba-title {
  color: #606266;
  font-size: 13px;
  margin: 6px 0 8px;
}
.dba-title-line {
  margin-top: 18px;
}
.dba-info {
  color: #909399;
  margin-left: 4px;
}
.dba-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 4px 0 2px;
}
.dba-footer-right {
  display: flex;
  align-items: center;
}
.dba-link {
  padding: 0;
  font-size: 12px;
}
.dba-danger {
  padding: 0;
  color: #f56c6c;
}
.dba-tag {
  margin-top: 2px;
}
.dba-mapping {
  width: 100%;
}
.dba-plus {
  padding: 0;
  margin-left: 4px;
}
.dba-sub {
  color: #909399;
  font-size: 12px;
}
.dba-id-input {
  width: 100%;
}
/* 线路模板弹窗：跟网管一样「标签一行、输入框一行」，标签里带取值范围 */
.dba-form-row {
  margin-bottom: 14px;
}
.dba-form-label {
  display: block;
  margin-bottom: 6px;
  color: #606266;
  font-size: 13px;
}
/* 只读那一行的值：灰底，看着就不像能改的（模板是哪条由后端定，页面不给改） */
.dba-form-value {
  padding: 0 12px;
  height: 32px;
  line-height: 32px;
  border-radius: 4px;
  background: #f5f7fa;
  border: 1px solid #e4e7ed;
  color: #606266;
  font-size: 13px;
}
.dba-hint {
  margin-left: 8px;
  color: #909399;
  font-size: 12px;
}
.dba-dialog-footer {
  text-align: left;
  padding-left: 110px;
}
.dba-preview {
  margin: 8px 0;
  border: 1px solid #dfe6ee;
  border-radius: 4px;

  pre {
    margin: 0;
    padding: 8px 10px;
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
.dba-preview-warn {
  padding: 6px 10px;
  color: #8a6d3b;
  font-size: 12px;
  line-height: 1.6;
  background: #fcf8e3;
  border-bottom: 1px solid #f5e6c0;
}
</style>
