<template>
  <div class="app-container">
    <el-card shadow="never">
      <div slot="header" class="card-header">
        <span>呼叫队列</span>
        <el-button v-if="$has('sip:update')" size="mini" type="primary" icon="el-icon-plus" @click="openQueue(null)">添加队列</el-button>
      </div>
      <el-table v-loading="loading" :data="queues" border size="small">
        <el-table-column label="队列号" prop="queueNo" width="90" align="center" />
        <el-table-column label="队列名称" prop="queueName" width="160">
          <template slot-scope="{ row }">{{ row.queueName || '-' }}</template>
        </el-table-column>
        <el-table-column label="目标分机（忙音顺延）" min-width="180">
          <template slot-scope="{ row }">
            <span class="mono">{{ row.targets }}</span>
          </template>
        </el-table-column>
        <el-table-column label="允许成员" min-width="150">
          <template slot-scope="{ row }">
            <span class="tip-line">{{ row.members ? row.members : '所有分机' }}</span>
          </template>
        </el-table-column>
        <el-table-column v-if="$has('sip:update') || $has('sip:delete')" label="操作" width="120" align="center">
          <template slot-scope="{ row }">
            <el-button v-if="$has('sip:update')" type="text" size="mini" @click="openQueue(row)">编辑</el-button>
            <el-button v-if="$has('sip:delete')" type="text" size="mini" class="danger-text" @click="onDeleteQueue(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="tip-line" style="margin-top:10px">
        拨打队列号接通总台：按目标顺序找第一个空闲分机，8888 忙音自动转 9999；同一队列号可配多条，成员匹配优先
      </div>
    </el-card>

    <el-dialog :title="queueForm.id ? '编辑队列' : '添加队列'" :visible.sync="dialogVisible" width="480px" :close-on-click-modal="false">
      <el-form label-width="90px" size="small">
        <el-form-item label="队列号">
          <el-input v-model="queueForm.queueNo" placeholder="拨打接通的号码，如 0/1/9" style="width:160px" />
        </el-form-item>
        <el-form-item label="队列名称">
          <el-input v-model="queueForm.queueName" placeholder="如 总台8888" />
        </el-form-item>
        <el-form-item label="目标分机">
          <el-select v-model="queueForm.targets" multiple filterable allow-create placeholder="选择/输入多个目标，按顺序忙音顺延" style="width:100%">
            <el-option v-for="e in extensionOptions" :key="e" :label="e" :value="e" />
          </el-select>
        </el-form-item>
        <el-form-item label="允许成员">
          <el-select v-model="queueForm.members" multiple filterable placeholder="不选=所有分机可拨打本队列" style="width:100%">
            <el-option v-for="e in extensionOptions" :key="e" :label="e" :value="e" />
          </el-select>
        </el-form-item>
      </el-form>
      <div class="tip-line" style="margin-bottom:10px">示例：队列 0 → 目标 8888,9999,6666；8888 忙音时自动呼叫 9999</div>
      <div slot="footer">
        <el-button size="small" @click="dialogVisible = false">取消</el-button>
        <el-button size="small" type="primary" :loading="saving" @click="onSaveQueue">保存</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { fetchSipQueues, saveSipQueue, deleteSipQueue, fetchSipExtensions } from '@/api/sip'

/** 呼叫队列管理：队列号/目标分机/允许成员配置 */
export default {
  name: 'SipQueues',
  data() {
    return {
      queues: [],
      extensionOptions: [],
      loading: false,
      dialogVisible: false,
      saving: false,
      queueForm: { id: null, queueNo: '', queueName: '', targets: [], members: [] }
    }
  },
  created() {
    this.load()
  },
  methods: {
    load() {
      this.loading = true
      Promise.all([fetchSipQueues(), fetchSipExtensions()])
        .then(([queueRes, extRes]) => {
          this.queues = queueRes.data || []
          this.extensionOptions = (extRes.data || []).map(e => e.extension)
        })
        .catch(() => {})
        .then(() => {
          this.loading = false
        })
    },
    openQueue(row) {
      if (row) {
        this.queueForm = {
          id: row.id,
          queueNo: row.queueNo,
          queueName: row.queueName || '',
          targets: (row.targets || '').split(',').filter(s => s),
          members: (row.members || '').split(',').filter(s => s)
        }
      } else {
        this.queueForm = { id: null, queueNo: '', queueName: '', targets: ['8888'], members: [] }
      }
      this.dialogVisible = true
    },
    onSaveQueue() {
      const f = this.queueForm
      if (!f.queueNo.trim()) {
        this.$message.warning('队列号不能为空')
        return
      }
      if (!f.targets.length) {
        this.$message.warning('目标分机不能为空')
        return
      }
      this.saving = true
      saveSipQueue({
        id: f.id,
        queueNo: f.queueNo.trim(),
        queueName: f.queueName,
        targets: f.targets.join(','),
        members: f.members.join(',')
      })
        .then(() => {
          this.$message.success('已保存')
          this.dialogVisible = false
          this.load()
        })
        .catch(() => {})
        .then(() => {
          this.saving = false
        })
    },
    onDeleteQueue(row) {
      this.$confirm(`确认删除队列 ${row.queueNo}（${row.queueName || ''}）?`, '提示', { type: 'warning' })
        .then(() => deleteSipQueue(row.id))
        .then(() => {
          this.$message.success('已删除')
          this.load()
        })
        .catch(() => {})
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
.danger-text {
  color: #f56c6c;
}
</style>
