<template>
  <div class="app-container">
    <el-card shadow="never">
      <div slot="header" class="clearfix">
        <span>下发业务</span>
        <el-button style="float: right; padding: 3px 0" type="text" @click="goInstances">
          查看工单记录
        </el-button>
      </div>

      <el-form ref="form" :model="form" :rules="rules" label-width="120px" style="max-width: 640px">
        <el-form-item label="设备 SN" prop="sn">
          <el-input v-model="form.sn" placeholder="请输入设备序列号" clearable />
        </el-form-item>

        <el-form-item label="业务类型" prop="category">
          <el-select
            v-model="form.category"
            placeholder="请选择业务类型"
            style="width: 100%"
            @change="onCategoryChange"
          >
            <el-option
              v-for="item in categories"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>

        <template v-if="variables.length > 0">
          <el-divider content-position="left">业务参数</el-divider>
          <el-form-item v-for="v in variables" :key="v.name" :label="v.label">
            <el-input
              v-model="form.values[v.name]"
              :type="v.secret ? 'password' : 'text'"
              :placeholder="v.example"
              :show-password="v.secret"
              clearable
            />
            <div v-if="v.secret" class="tip">口令类参数，不会明文写入日志</div>
          </el-form-item>
        </template>

        <el-form-item label="工单流水号">
          <el-input v-model="form.worklistDesc" placeholder="留空则自动生成" clearable>
            <el-button slot="append" @click="genDesc">生成</el-button>
          </el-input>
          <div class="tip">同一流水号重复提交只受理一次，用于防止重复下发</div>
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="submitting" @click="submit">提交下发</el-button>
          <el-button @click="reset">重置</el-button>
        </el-form-item>
      </el-form>

      <el-alert
        v-if="result"
        :title="result.text"
        :type="result.ok ? 'success' : 'error'"
        :closable="false"
        show-icon
        style="margin-top: 8px"
      />
    </el-card>
  </div>
</template>

<script>
import { fetchVariables, createWorklist, newWorklistDesc } from '@/api/worklist'

/**
 * 下发业务：选设备 + 选业务 + 填变量 -> 生成工单。
 *
 * 变量清单由后端 TemplateVariables 定义，这里只负责渲染，新增业务不用改页面。
 */
const CATEGORIES = [
  { value: 'WIFI_24G', label: 'WiFi 2.4G' },
  { value: 'WIFI_58G', label: 'WiFi 5.8G' },
  { value: 'SIP', label: 'SIP 语音' },
  { value: 'WAN_DHCP', label: 'WAN 自动获取' },
  { value: 'WAN_PPPOE', label: 'WAN 拨号' },
  { value: 'WAN_STATIC', label: 'WAN 静态地址' },
  { value: 'WAN_BRIDGE', label: 'WAN 桥接' }
]

export default {
  name: 'WorklistDeliver',
  data() {
    return {
      categories: CATEGORIES,
      variables: [],
      form: {
        sn: '',
        category: '',
        values: {},
        worklistDesc: ''
      },
      submitting: false,
      result: null,
      rules: {
        sn: [{ required: true, message: '请输入设备序列号', trigger: 'blur' }],
        category: [{ required: true, message: '请选择业务类型', trigger: 'change' }]
      }
    }
  },
  created() {
    // 支持从设备详情/列表携带 SN 跳进来
    const sn = this.$route.query.sn
    if (sn) {
      this.form.sn = sn
    }
  },
  methods: {
    onCategoryChange(value) {
      this.form.values = {}
      this.variables = []
      if (!value) {
        return
      }
      fetchVariables(value)
        .then((res) => {
          const list = (res && res.data) || []
          this.variables = list
          list.forEach((v) => {
            this.$set(this.form.values, v.name, '')
          })
        })
        .catch(() => {
          this.variables = []
          this.$message.warning('该业务类型暂未定义变量清单')
        })
    },
    genDesc() {
      this.form.worklistDesc = newWorklistDesc(this.form.category)
    },
    submit() {
      this.$refs.form.validate((valid) => {
        if (!valid) {
          return
        }
        this.submitting = true
        this.result = null
        const payload = {
          sn: this.form.sn,
          category: this.form.category,
          values: this.form.values,
          worklistDesc: this.form.worklistDesc || newWorklistDesc(this.form.category),
          username: (this.$store.getters.name) || 'admin'
        }
        createWorklist(payload)
          .then((res) => {
            const w = (res && res.data) || {}
            this.result = {
              ok: true,
              text: `工单已受理：${w.id || '-'}，当前状态 ${w.status || 'build'}`
            }
            this.$message.success('提交成功，可在工单记录查看执行进度')
          })
          .catch((err) => {
            this.result = { ok: false, text: (err && err.message) || '提交失败' }
          })
          .finally(() => {
            this.submitting = false
          })
      })
    },
    reset() {
      this.$refs.form.resetFields()
      this.form.values = {}
      this.variables = []
      this.result = null
    },
    goInstances() {
      this.$router.push({ name: 'WorklistInstances' })
    }
  }
}
</script>

<style scoped>
.tip {
  font-size: 12px;
  color: #999;
  line-height: 1.5;
  margin-top: 2px;
}
</style>
