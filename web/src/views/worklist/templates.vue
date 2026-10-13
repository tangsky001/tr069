<template>
  <div class="app-container">
    <el-card shadow="never" class="desc-card">
      <div class="desc">
        配置模板只描述「要开通成什么样」的开通意图（如 IP 协议、是否带 VLAN、是否绑定端口），
        不做任何下发动作。下发时由执行器按设备型号映射出该机型的真实参数后再生成报文。
      </div>
      <div class="create-bar">
        <span class="create-label">新增模板：</span>
        <el-button
          v-for="c in categories"
          :key="c.code"
          size="small"
          :class="['cat-btn', 'cat-' + c.code]"
          @click="openCreate(c.code)"
        >{{ c.label }}</el-button>
      </div>
    </el-card>

    <el-card shadow="never" class="filter-card">
      <el-form :inline="true" :model="query" size="small">
        <el-form-item label="模板类型">
          <el-select v-model="query.category" clearable placeholder="全部类型" style="width:180px">
            <el-option v-for="c in categories" :key="c.code" :label="c.label" :value="c.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" placeholder="模板名 / 分组 / 备注" clearable style="width:200px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="el-icon-search" @click="load">搜索</el-button>
          <el-button icon="el-icon-refresh" @click="resetQuery">清除</el-button>
        </el-form-item>
      </el-form>
      <div class="batch-bar">
        <span>共 {{ list.length }} 条</span>
      </div>
    </el-card>

    <el-table v-loading="loading" :data="list" border fit size="small" style="width:100%">
      <el-table-column label="ID" width="70">
        <template slot-scope="{ row }">
          <span>{{ row.id }}</span>
        </template>
      </el-table-column>
      <el-table-column label="名称 / 分组" min-width="180" show-overflow-tooltip>
        <template slot-scope="{ row }">
          <div class="cell-main">{{ row.name }}</div>
          <div class="cell-sub">{{ row.groupName || '-' }}</div>
        </template>
      </el-table-column>
      <el-table-column label="模板类型" width="130">
        <template slot-scope="{ row }">
          <el-tag size="mini" :type="tagType(row.category)">{{ row.categoryLabel || row.category }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="开通意图" min-width="260" show-overflow-tooltip>
        <template slot-scope="{ row }">
          <span>{{ intentSummary(row) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="备注" min-width="160" show-overflow-tooltip>
        <template slot-scope="{ row }">
          <span>{{ row.remark || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template slot-scope="{ row }">
          <el-tag size="mini" :type="row.enabled ? 'success' : 'info'">
            {{ row.enabled ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template slot-scope="{ row }">
          <el-button type="text" size="mini" icon="el-icon-edit" @click="openEdit(row)">编辑</el-button>
          <el-button type="text" size="mini" icon="el-icon-delete" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      :title="dialogTitle"
      :visible.sync="dialogVisible"
      width="720px"
      top="6vh"
      :close-on-click-modal="false"
    >
      <el-form :model="form" label-width="150px" size="small">
        <el-form-item label="模版类型">
          <el-select v-model="form.category" style="width:240px" :disabled="!!form.id" @change="onCategoryChange">
            <el-option v-for="c in categories" :key="c.code" :label="c.label" :value="c.code" />
          </el-select>
          <div v-if="form.id" class="mode-tip">类型不可修改，需要其它类型请新建</div>
        </el-form-item>
        <el-form-item label="模版名称" required>
          <el-input v-model="form.name" placeholder="模板名称" style="width:320px" maxlength="64" />
        </el-form-item>
        <el-form-item label="业务分组">
          <el-input v-model="form.groupName" placeholder="可空" style="width:320px" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" active-text="是" inactive-text="否" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" style="width:420px" />
        </el-form-item>
        <el-divider content-position="left">开通意图</el-divider>
        <div class="mode-tip intent-tip">
          只描述意图，真实参数路径在下发时按设备型号映射决定
        </div>
        <el-form-item v-for="f in fieldDefs" :key="f.key" :label="f.label">
          <el-select v-model="form.fields[f.key]" style="width:240px">
            <el-option v-for="o in f.options" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
      </el-form>

      <span slot="footer">
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import {
  fetchConfigTemplateMeta,
  fetchConfigTemplates,
  saveConfigTemplate,
  deleteConfigTemplate
} from '@/api/configTemplate'

function emptyForm() {
  return {
    id: null,
    name: '',
    category: 'WAN_DHCP',
    groupName: '',
    enabled: true,
    remark: '',
    fields: {}
  }
}

export default {
  name: 'TemplateList',
  data() {
    return {
      loading: false,
      saving: false,
      list: [],
      categories: [],
      // 类型 -> 该类型的意图字段定义
      fieldDefMap: {},
      query: { category: '', keyword: '' },
      dialogVisible: false,
      dialogTitle: '新增配置模板',
      form: emptyForm()
    }
  },
  computed: {
    fieldDefs() {
      return this.fieldDefMap[this.form.category] || []
    }
  },
  created() {
    this.loadMeta()
    this.load()
  },
  methods: {
    async loadMeta() {
      try {
        const res = await fetchConfigTemplateMeta()
        const meta = res.data || {}
        this.categories = meta.categories || []
        this.fieldDefMap = meta.fields || {}
      } catch (e) {
        this.categories = []
      }
    },
    async load() {
      this.loading = true
      try {
        const res = await fetchConfigTemplates(this.query)
        this.list = res.data || []
      } catch (e) {
        this.list = []
      } finally {
        this.loading = false
      }
    },
    resetQuery() {
      this.query = { category: '', keyword: '' }
      this.load()
    },
    tagType(code) {
      const map = {
        WAN_DHCP: 'success', WAN_PPPOE: 'success', WAN_STATIC: 'success', WAN_BRIDGE: 'success',
        WIFI_24G: 'danger', WIFI_58G: 'danger', SIP: 'warning'
      }
      return map[code] || 'info'
    },
    /** 意图字段 -> 「标签：取值」摘要，取值显示后端定义的中文 */
    intentSummary(row) {
      const defs = this.fieldDefMap[row.category] || []
      const fields = row.fields || {}
      if (!defs.length) return '-'
      return defs.map(d => {
        const value = fields[d.key]
        if (value === undefined || value === null || value === '') return null
        const hit = (d.options || []).find(o => String(o.value) === String(value))
        return d.label + '：' + (hit ? hit.label : value)
      }).filter(Boolean).join('　')
    },
    // ---------------- 对话框 ----------------
    openCreate(category) {
      this.form = emptyForm()
      if (category) this.form.category = category
      this.applyFieldDefaults()
      this.dialogTitle = '新增配置模板'
      this.dialogVisible = true
    },
    openEdit(row) {
      this.form = {
        id: row.id,
        name: row.name,
        category: row.category,
        groupName: row.groupName || '',
        enabled: row.enabled !== false,
        remark: row.remark || '',
        fields: Object.assign({}, row.fields || {})
      }
      this.applyFieldDefaults()
      this.dialogTitle = '编辑配置模板 - ' + row.name
      this.dialogVisible = true
    },
    onCategoryChange() {
      // 换类型后原意图字段不再适用，按新类型重置为默认值
      this.form.fields = {}
      this.applyFieldDefaults()
    },
    /** 未设置的意图字段按后端默认值补齐 */
    applyFieldDefaults() {
      const defs = this.fieldDefMap[this.form.category] || []
      defs.forEach(d => {
        if (this.form.fields[d.key] === undefined || this.form.fields[d.key] === null ||
          this.form.fields[d.key] === '') {
          this.$set(this.form.fields, d.key, d.defaultValue)
        }
      })
    },
    async submit() {
      if (!this.form.name.trim()) {
        this.$message.warning('请填写模板名称')
        return
      }
      this.saving = true
      try {
        await saveConfigTemplate({
          id: this.form.id,
          name: this.form.name.trim(),
          category: this.form.category,
          groupName: this.form.groupName,
          enabled: this.form.enabled,
          remark: this.form.remark,
          fields: this.form.fields
        })
        this.$message.success(this.form.id ? '模板已更新' : '模板已保存')
        this.dialogVisible = false
        this.load()
      } catch (e) {
        this.$message.error((e && e.message) || '保存失败')
      } finally {
        this.saving = false
      }
    },
    async handleDelete(row) {
      await this.$confirm('确认删除配置模板 ' + row.name + '？', '删除确认', {
        type: 'warning'
      }).catch(() => {
        throw new Error('cancel')
      })
      await deleteConfigTemplate(row.id)
      this.$message.success('已删除')
      this.load()
    }
  }
}
</script>

<style scoped>
.desc-card {
  margin-bottom: 12px;
}
.desc {
  color: #606266;
  font-size: 13px;
  line-height: 1.8;
}
.create-bar {
  margin-top: 10px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
}
.create-label {
  font-size: 13px;
  color: #909399;
  margin-right: 8px;
}
.cat-btn {
  margin: 0 6px 6px 0;
}
.cat-WAN_DHCP { background: #36cfc9; border-color: #36cfc9; color: #fff; }
.cat-WAN_PPPOE { background: #1890ff; border-color: #1890ff; color: #fff; }
.cat-WAN_STATIC { background: #5cb85c; border-color: #5cb85c; color: #fff; }
.cat-WAN_BRIDGE { background: #67c23a; border-color: #67c23a; color: #fff; }
.cat-WIFI_24G { background: #f78989; border-color: #f78989; color: #fff; }
.cat-WIFI_58G { background: #f56c6c; border-color: #f56c6c; color: #fff; }
.cat-SIP { background: #e6a23c; border-color: #e6a23c; color: #fff; }
.filter-card {
  margin-bottom: 12px;
}
.batch-bar {
  font-size: 13px;
  color: #606266;
}
.cell-main {
  font-weight: 600;
  color: #303133;
}
.cell-sub {
  color: #909399;
  font-size: 12px;
}
.mode-tip {
  color: #909399;
  font-size: 12px;
}
.intent-tip {
  margin: 0 0 10px 0;
}
</style>
