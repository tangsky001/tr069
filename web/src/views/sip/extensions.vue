<template>
  <div class="app-container">
    <el-card shadow="never">
      <div slot="header" class="card-header">
        <span>分机管理</span>
        <div>
          <el-tag size="mini" type="info">注册 {{ registeredCount }} / 共 {{ extensions.length }}</el-tag>
          <el-button v-if="$has('sip:update')" size="mini" type="primary" icon="el-icon-plus" style="margin-left:10px" @click="openAdd">添加分机</el-button>
          <el-button v-if="$has('sip:update')" size="mini" icon="el-icon-s-operation" style="margin-left:8px" @click="openGenerate">批量生成</el-button>
          <el-upload
            v-if="$has('sip:update')"
            :show-file-list="false"
            accept=".txt,.csv"
            :http-request="doImport"
            style="display:inline-block;margin-left:8px"
          >
            <el-button size="mini" icon="el-icon-upload2">批量导入</el-button>
          </el-upload>
        </div>
      </div>

      <el-table v-loading="loading" :data="extensions" border size="small">
        <el-table-column label="分机号" prop="extension" width="110" align="center">
          <template slot-scope="{ row }">
            <span class="status-dot" :class="row.registered ? 'online' : 'offline'" />
            <span>{{ row.extension }}</span>
          </template>
        </el-table-column>
        <el-table-column label="密码" prop="password" width="120">
          <template slot-scope="{ row }">
            <span class="mono">{{ row.password }}</span>
          </template>
        </el-table-column>
        <el-table-column label="备注" prop="remark" min-width="130">
          <template slot-scope="{ row }">{{ row.remark || '-' }}</template>
        </el-table-column>
        <el-table-column label="注册状态" width="100" align="center">
          <template slot-scope="{ row }">
            <el-tag size="mini" :type="row.registered ? 'success' : 'info'">
              {{ row.registered ? '在线' : '离线' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="通话状态" width="100" align="center">
          <template slot-scope="{ row }">
            <el-tag size="mini" :type="row.inCall ? 'warning' : 'info'">
              {{ row.inCall ? '通话中' : '空闲' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="注册地址" min-width="160">
          <template slot-scope="{ row }">
            <span class="mono">{{ row.address || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="终端" min-width="140">
          <template slot-scope="{ row }">
            <span class="mono">{{ row.userAgent || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="剩余有效期" width="110" align="center">
          <template slot-scope="{ row }">
            {{ row.registered ? formatExpire(row.expireSeconds) : '-' }}
          </template>
        </el-table-column>
        <el-table-column v-if="$has('sip:update') || $has('sip:delete')" label="操作" width="120" align="center">
          <template slot-scope="{ row }">
            <el-button v-if="$has('sip:update')" type="text" size="mini" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="$has('sip:delete')" type="text" size="mini" class="danger-text" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog :title="editForm.id ? '编辑分机' : '添加分机'" :visible.sync="dialogVisible" width="420px" :close-on-click-modal="false">
      <el-form label-width="80px" size="small">
        <el-form-item label="分机号">
          <el-input v-model="editForm.extension" :disabled="!!editForm.id" placeholder="如 1001" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="editForm.password" placeholder="留空默认同分机号" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="editForm.remark" placeholder="选填" />
        </el-form-item>
      </el-form>
      <div slot="footer">
        <el-button size="small" @click="dialogVisible = false">取消</el-button>
        <el-button size="small" type="primary" :loading="saving" @click="onSave">保存</el-button>
      </div>
    </el-dialog>

    <el-dialog title="批量生成号段" :visible.sync="genDialogVisible" width="440px" :close-on-click-modal="false">
      <el-form label-width="110px" size="small">
        <el-form-item label="前缀">
          <el-input v-model="genForm.prefix" placeholder="2~3 位数字，如 82" style="width:200px" />
        </el-form-item>
        <el-form-item label="起止号码">
          <el-input-number v-model="genForm.start" :min="1" :max="999" controls-position="right" style="width:110px" />
          <span style="margin:0 6px">~</span>
          <el-input-number v-model="genForm.end" :min="1" :max="999" controls-position="right" style="width:110px" />
        </el-form-item>
        <el-form-item label="统一密码">
          <el-input v-model="genForm.password" placeholder="留空默认同分机号" />
        </el-form-item>
        <el-form-item label="排除含4">
          <el-switch v-model="genForm.exclude4" />
          <span class="tip-line" style="margin-left:10px">跳过第 3/4 位含 4 的号码（如 8404/8424/8204/8214）</span>
        </el-form-item>
      </el-form>
      <div class="tip-line" style="margin-bottom:10px">示例：前缀 82、起止 1~99 → 生成 8201~8299；已存在的分机自动跳过</div>
      <div slot="footer">
        <el-button size="small" @click="genDialogVisible = false">取消</el-button>
        <el-button size="small" type="primary" :loading="saving" @click="onGenerate">生成</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { fetchSipExtensions, addSipExt, updateSipExt, deleteSipExt, importSipExts, generateSipExts } from '@/api/sip'

/** SIP 分机管理：增删改、批量导入/生成、注册状态一览 */
export default {
  name: 'SipExtensions',
  data() {
    return {
      extensions: [],
      loading: false,
      autoRefresh: true,
      pollTimer: null,
      dialogVisible: false,
      genDialogVisible: false,
      saving: false,
      editForm: { id: null, extension: '', password: '', remark: '' },
      genForm: { prefix: '82', start: 1, end: 99, password: '', exclude4: true }
    }
  },
  computed: {
    registeredCount() {
      return this.extensions.filter(e => e.registered).length
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
      fetchSipExtensions()
        .then(res => {
          this.extensions = res.data || []
        })
        .catch(() => {})
        .then(() => {
          this.loading = false
        })
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
    formatExpire(sec) {
      if (sec == null) return '-'
      if (sec >= 3600) return Math.floor(sec / 3600) + ' 时'
      if (sec >= 60) return Math.floor(sec / 60) + ' 分'
      return sec + ' 秒'
    },
    openAdd() {
      this.editForm = { id: null, extension: '', password: '', remark: '' }
      this.dialogVisible = true
    },
    openEdit(row) {
      this.editForm = { id: row.id, extension: row.extension, password: row.password, remark: row.remark || '' }
      this.dialogVisible = true
    },
    onSave() {
      const f = this.editForm
      if (!f.id && !f.extension.trim()) {
        this.$message.warning('分机号不能为空')
        return
      }
      this.saving = true
      const req = f.id
        ? updateSipExt(f.id, { password: f.password, remark: f.remark })
        : addSipExt({ extension: f.extension.trim(), password: f.password, remark: f.remark })
      req.then(() => {
        this.$message.success('已保存')
        this.dialogVisible = false
        this.load()
      })
        .catch(() => {})
        .then(() => {
          this.saving = false
        })
    },
    onDelete(row) {
      this.$confirm(`确认删除分机 ${row.extension} ?`, '提示', { type: 'warning' })
        .then(() => deleteSipExt(row.id))
        .then(() => {
          this.$message.success('已删除')
          this.load()
        })
        .catch(() => {})
    },
    /** 文件批量导入：每行 分机号 密码 [备注] */
    doImport(option) {
      const formData = new FormData()
      formData.append('file', option.file)
      return importSipExts(formData).then(res => {
        const r = res.data || {}
        if (r.errors && r.errors.length) {
          this.$message.warning(`导入完成：新增 ${r.added}，覆盖 ${r.updated}，失败 ${r.errors.length}`)
        } else {
          this.$message.success(`导入完成：新增 ${r.added}，覆盖 ${r.updated}`)
        }
        this.load()
      }).catch(() => {})
    },
    openGenerate() {
      this.genForm = { prefix: '82', start: 1, end: 99, password: '', exclude4: true }
      this.genDialogVisible = true
    },
    onGenerate() {
      const f = this.genForm
      if (!/^\d{2,3}$/.test(String(f.prefix).trim())) {
        this.$message.warning('前缀须为 2~3 位数字')
        return
      }
      if (f.start < 1 || f.end < f.start) {
        this.$message.warning('起止号码范围不正确')
        return
      }
      this.saving = true
      generateSipExts({ prefix: f.prefix, start: f.start, end: f.end, exclude4: f.exclude4, password: f.password })
        .then(res => {
          const r = res.data || {}
          this.$message.success(`生成完成：新增 ${r.added}，跳过 ${r.skipped}（含排除含4/已存在）`)
          this.genDialogVisible = false
          this.load()
        })
        .catch(() => {})
        .then(() => {
          this.saving = false
        })
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
.status-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  margin-right: 6px;
  border-radius: 50%;
  vertical-align: middle;
}
.status-dot.online {
  background: #67c23a;
  box-shadow: 0 0 4px #67c23a;
}
.status-dot.offline {
  background: #f56c6c;
}
.tip-line {
  color: #909399;
  font-size: 12px;
}
.danger-text {
  color: #f56c6c;
}
</style>
