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
        <el-table-column label="映射关系" min-width="420">
          <template slot-scope="{ row }">
            <el-table :data="mappingRows(row)" size="mini" border class="dba-mapping">
              <el-table-column label="T-CONT索引" width="110">
                <template slot-scope="{ row: m }">
                  <span>{{ m.tcontId == null ? '-' : m.tcontId }}</span>
                  <el-button
                    type="text"
                    icon="el-icon-plus"
                    class="dba-plus"
                    @click="notSupported('增加 T-CONT')"
                  />
                </template>
              </el-table-column>
              <el-table-column label="DBA模板" width="110">
                <template slot-scope="{ row: m }">
                  <div>{{ m.dbaProfileId == null ? '-' : m.dbaProfileId }}</div>
                  <div class="dba-sub">{{ dbaName(m.dbaProfileId) }}</div>
                </template>
              </el-table-column>
              <el-table-column label="GEM Port索引" width="110" align="center">
                <template slot-scope="{ row: m }">{{ m.gemportId == null ? '-' : m.gemportId }}</template>
              </el-table-column>
              <el-table-column label="GEM 映射ID" width="100" align="center">
                <template slot-scope="{ row: m }">{{ m.mappingId == null ? '-' : m.mappingId }}</template>
              </el-table-column>
              <el-table-column label="VLAN">
                <template slot-scope="{ row: m }">{{ m.vlan || '-' }}</template>
              </el-table-column>
            </el-table>
            <el-button type="text" icon="el-icon-plus" class="dba-link" @click="notSupported('增加 GEM Port')">
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
 * 「新增 / 删除 DBA 模板」是真实下发（dba-profile add / delete），通过
 * createHandler / deleteHandler 由页面（mixin）执行，成功后页面会拿最新
 * 采集数据刷新；「增加 GEM Port」仍是占位。
 */
import { fetchDbaForm, previewDbaProfile } from '@/api/olt'

/** 表单改动后隔多久重新算命令 */
const PREVIEW_DELAY_MS = 300

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
      form: {}
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
