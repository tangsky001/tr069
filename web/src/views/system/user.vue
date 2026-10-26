<template>
  <div class="app-container">
    <el-form :inline="true" size="small">
      <el-form-item>
        <el-button type="primary" icon="el-icon-plus" @click="createVisible = true">新建账号</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="list" border stripe size="small">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="username" label="用户名" width="160" />
      <el-table-column prop="displayName" label="显示名" width="160" />
      <el-table-column label="状态" width="90" align="center">
        <template slot-scope="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="mini">
            {{ row.status === 1 ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="创建时间" width="170" />
      <el-table-column label="操作" width="280">
        <template slot-scope="{ row }">
          <el-button type="text" size="mini" @click="openPassword(row)">重置密码</el-button>
          <el-button type="text" size="mini" @click="toggleStatus(row)">
            {{ row.status === 1 ? '停用' : '启用' }}
          </el-button>
          <el-button type="text" size="mini" style="color:#f56c6c" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog title="新建账号" :visible.sync="createVisible" width="420px">
      <el-form :model="form" label-width="90px" size="small">
        <el-form-item label="用户名"><el-input v-model="form.username" /></el-form-item>
        <el-form-item label="密码"><el-input v-model="form.password" show-password /></el-form-item>
        <el-form-item label="显示名"><el-input v-model="form.displayName" /></el-form-item>
      </el-form>
      <div slot="footer">
        <el-button size="small" @click="createVisible = false">取消</el-button>
        <el-button size="small" type="primary" @click="submitCreate">确定</el-button>
      </div>
    </el-dialog>

    <el-dialog title="重置密码" :visible.sync="pwdVisible" width="400px">
      <el-form size="small">
        <el-form-item label="新密码">
          <el-input v-model="newPassword" show-password />
        </el-form-item>
      </el-form>
      <div slot="footer">
        <el-button size="small" @click="pwdVisible = false">取消</el-button>
        <el-button size="small" type="primary" @click="submitPassword">确定</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listUsers, createUser, deleteUser, changePassword, updateStatus } from '@/api/user'

export default {
  name: 'UserManage',
  data() {
    return {
      loading: false,
      list: [],
      createVisible: false,
      pwdVisible: false,
      current: {},
      newPassword: '',
      form: { username: '', password: '', displayName: '' }
    }
  },
  created() { this.load() },
  methods: {
    load() {
      this.loading = true
      listUsers().then(res => { this.list = res.data || [] }).finally(() => { this.loading = false })
    },
    submitCreate() {
      createUser(this.form).then(() => {
        this.$message.success('已创建')
        this.createVisible = false
        this.form = { username: '', password: '', displayName: '' }
        this.load()
      })
    },
    openPassword(row) { this.current = row; this.newPassword = ''; this.pwdVisible = true },
    submitPassword() {
      changePassword(this.current.id, this.newPassword).then(() => {
        this.$message.success('已重置')
        this.pwdVisible = false
      })
    },
    toggleStatus(row) {
      updateStatus(row.id, row.status === 1 ? 0 : 1).then(() => {
        this.$message.success('已更新')
        this.load()
      })
    },
    handleDelete(row) {
      this.$confirm('确认删除账号 ' + row.username + '？', '提示', { type: 'warning' })
        .then(() => deleteUser(row.id)).then(() => { this.$message.success('已删除'); this.load() })
        .catch(() => {})
    }
  }
}
</script>

<style scoped>
.app-container { padding: 20px; }
</style>
