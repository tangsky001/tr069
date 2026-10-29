<template>
  <div class="app-container">
    <el-button
      v-if="canManage"
      type="primary"
      icon="el-icon-plus"
      size="small"
      @click="openCreate"
    >新建角色</el-button>
    <span class="tip">系统管理员拥有全部权限，它的权限不显示在这里，也改不动</span>

    <el-table v-loading="loading" :data="list" border size="small" style="margin-top:12px">
      <el-table-column prop="name" label="角色" min-width="140" />
      <el-table-column prop="code" label="编码" width="160" />
      <el-table-column label="人数" width="80" align="center">
        <template slot-scope="{ row }">{{ row.userCount }}</template>
      </el-table-column>
      <el-table-column label="权限" min-width="260">
        <template slot-scope="{ row }">
          <span v-if="row.builtin" class="all">全部权限</span>
          <span v-else-if="!row.permissions.length" class="none">一个权限都没有</span>
          <template v-else>
            <el-tag
              v-for="p in row.permissions"
              :key="p"
              size="mini"
              :type="p.endsWith(':manage') ? 'warning' : 'info'"
              class="perm-tag"
            >{{ p }}</el-tag>
          </template>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="160" align="center">
        <template slot-scope="{ row }">
          <el-button
            type="text"
            :disabled="row.builtin || !canManage"
            @click="openPermissions(row)"
          >权限</el-button>
          <el-button
            type="text"
            :disabled="row.builtin || !canManage"
            @click="onDelete(row)"
          >删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 权限勾选：按模块分组，每组「查看 / 管理」两格 -->
    <el-dialog
      :title="'权限 - ' + (currentRow ? currentRow.name : '')"
      :visible.sync="permVisible"
      width="600px"
      append-to-body
    >
      <el-table :data="groups" size="small" border max-height="420">
        <el-table-column prop="name" label="模块" width="130" />
        <el-table-column label="查看" width="90" align="center">
          <template slot-scope="{ row }">
            <el-checkbox
              :value="has(row.view)"
              :disabled="currentRow && currentRow.builtin"
              @change="v => toggle(row.view, v)"
            />
          </template>
        </el-table-column>
        <el-table-column label="管理" width="90" align="center">
          <template slot-scope="{ row }">
            <el-checkbox
              :value="has(row.manage)"
              :disabled="currentRow && currentRow.builtin"
              @change="v => toggle(row.manage, v)"
            />
          </template>
        </el-table-column>
        <el-table-column label="说明" min-width="150">
          <template slot-scope="{ row }">
            能{{ row.name }}的{{ '查看 / 改动' }}
          </template>
        </el-table-column>
      </el-table>
      <div class="tip">
        查看 = 打开页面与查询，管理 = 建、改、删与下发。没有查看权的话，管理权也是空的。
      </div>
      <span slot="footer">
        <el-button @click="permVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="saving"
          :disabled="currentRow && currentRow.builtin"
          @click="submitPermissions"
        >保存</el-button>
      </span>
    </el-dialog>

    <el-dialog title="新建角色" :visible.sync="createVisible" width="400px" append-to-body>
      <el-form label-width="80px">
        <el-form-item label="编码">
          <el-input v-model="createForm.code" placeholder="如 MONITOR，字母数字下划线" />
        </el-form-item>
        <el-form-item label="角色名">
          <el-input v-model="createForm.name" placeholder="如 监控员" />
        </el-form-item>
      </el-form>
      <div class="tip">新建的角色一个权限都没有，建完去勾</div>
      <span slot="footer">
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitCreate">保存</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import {
  fetchRoles, createRole, saveRolePermissions, removeRole, fetchPermissions
} from '@/api/system'

export default {
  name: 'SystemRoles',
  data() {
    return {
      list: [],
      groups: [],
      loading: false,
      saving: false,
      permVisible: false,
      currentRow: null,
      checked: [],
      createVisible: false,
      createForm: { code: '', name: '' }
    }
  },
  computed: {
    // 没有 system:manage 的人只看不改，系统管理员除外
    canManage() {
      const perms = this.$store.getters.permissions || []
      return this.$store.getters.isSuperAdmin || perms.indexOf('system:manage') >= 0
    }
  },
  created() {
    this.load()
  },
  methods: {
    load() {
      this.loading = true
      fetchPermissions()
        .then(res => { this.groups = (res.data && res.data.groups) || [] })
        .catch(() => {})
      fetchRoles()
        .then(res => { this.list = res.data || [] })
        .catch(() => {})
        .then(() => { this.loading = false })
    },
    has(code) {
      return this.checked.indexOf(code) >= 0
    },
    toggle(code, on) {
      if (on) {
        if (!this.has(code)) this.checked.push(code)
      } else {
        this.checked = this.checked.filter(c => c !== code)
      }
    },
    openPermissions(row) {
      this.currentRow = row
      // 系统管理员是全选且改不动，勾上只是为了让它看着是全的
      this.checked = row.permissions ? row.permissions.slice() : []
      this.permVisible = true
    },
    submitPermissions() {
      if (!this.currentRow) return
      this.saving = true
      saveRolePermissions(this.currentRow.code, this.checked)
        .then(() => {
          this.$message.success('权限已保存')
          this.permVisible = false
          this.load()
        })
        .catch(() => {})
        .then(() => { this.saving = false })
    },
    openCreate() {
      this.createForm = { code: '', name: '' }
      this.createVisible = true
    },
    submitCreate() {
      if (!this.createForm.code || !this.createForm.name) {
        this.$message.warning('编码与角色名都要填')
        return
      }
      this.saving = true
      createRole(this.createForm)
        .then(() => {
          this.$message.success('已创建，记得给它勾权限')
          this.createVisible = false
          this.load()
        })
        .catch(() => {})
        .then(() => { this.saving = false })
    },
    async onDelete(row) {
      try {
        await this.$confirm(
          row.userCount > 0
            ? '还有 ' + row.userCount + ' 个人用着这个角色，先给他们换角色才能删'
            : '删除角色「' + row.name + '」？',
          '提示',
          { type: 'warning' }
        )
      } catch (e) {
        return
      }
      removeRole(row.code)
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
.tip {
  margin-left: 10px;
  color: #909399;
  font-size: 12px;
}
.perm-tag {
  margin-right: 4px;
  margin-bottom: 4px;
}
.all {
  color: #67c23a;
}
.none {
  color: #909399;
}
</style>
