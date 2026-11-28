<template>
  <div>
    <div class="la-toolbar">
      <el-button
        size="small"
        type="primary"
        icon="el-icon-search"
        :loading="loading"
        @click="$emit('refresh')"
      >查询聚合组</el-button>
      <el-button
        size="small"
        type="success"
        icon="el-icon-plus"
        :loading="applying"
        @click="openCreate"
      >创建聚合组</el-button>
      <span class="la-hint">成员口属性必须一致；加入聚合前先把口上的业务迁走，聚合按主控槽位（如 0/17）成组</span>
    </div>

    <el-table v-loading="loading" :data="groups" size="small" border stripe>
      <el-table-column label="主端口" prop="masterPort" width="110" align="center" />
      <el-table-column label="方向" prop="mode" width="90" align="center">
        <template slot-scope="{ row }">
          <el-tag size="mini" :type="row.mode === 'egress' ? 'warning' : 'primary'">{{ row.mode }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="成员口数" prop="portNum" width="90" align="center" />
      <el-table-column label="工作模式" prop="workmode" width="130" align="center" />
      <el-table-column label="最大链路数" width="100" align="center">
        <template slot-scope="{ row }">{{ row.maxLinkNumber || '-' }}</template>
      </el-table-column>
      <el-table-column label="最少链路数" width="100" align="center">
        <template slot-scope="{ row }">{{ row.leastLinkNumber || '-' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="90" align="center">
        <template slot-scope="{ row }">
          <el-button
            size="mini"
            type="danger"
            plain
            :loading="applying"
            @click="confirmDelete(row)"
          >删除</el-button>
        </template>
      </el-table-column>
      <div slot="empty">没有聚合组（设备回显 Failure: Link aggregation does not exist 时即无聚合组）</div>
    </el-table>

    <el-dialog title="创建链路聚合组" :visible.sync="createVisible" width="480px" append-to-body>
      <el-form label-width="110px" size="small">
        <el-form-item label="主控端口" required>
          <el-input v-model="form.masterFs" placeholder="框/槽，如 0/17" style="width: 200px" />
        </el-form-item>
        <el-form-item label="成员端口" required>
          <el-input v-model="form.ports" placeholder="如 0-1 或 0,1" style="width: 200px" />
          <div class="la-field-hint">同一槽位内的上联口号，第一个口即主端口</div>
        </el-form-item>
        <el-form-item label="聚合方向" required>
          <el-select v-model="form.direction" style="width: 200px">
            <el-option label="ingress（入方向）" value="ingress" />
            <el-option label="egress（出方向）" value="egress" />
          </el-select>
        </el-form-item>
        <el-form-item label="工作模式">
          <el-select v-model="form.workmode" style="width: 200px">
            <el-option label="lacp-static（静态 LACP）" value="lacp-static" />
            <el-option label="manual（手工静态聚合）" value="manual" />
          </el-select>
          <div class="la-field-hint">缺省 lacp-static；对端也要配聚合，链路才真正起聚</div>
        </el-form-item>
      </el-form>
      <div slot="footer">
        <el-button size="small" :loading="applying" type="primary" @click="submitCreate">下发创建</el-button>
        <el-button size="small" @click="createVisible = false">取消</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
/** 链路聚合面板：展示/创建/删除聚合组，查询与下发由父页面经 mixin 完成，本组件只渲染。 */
export default {
  name: 'OltLinkAggregationPanel',
  props: {
    groups: {
      type: Array,
      default: () => []
    },
    loading: {
      type: Boolean,
      default: false
    },
    applying: {
      type: Boolean,
      default: false
    }
  },
  data() {
    return {
      createVisible: false,
      form: {
        masterFs: '',
        ports: '',
        direction: 'ingress',
        workmode: 'lacp-static'
      }
    }
  },
  methods: {
    openCreate() {
      this.form = { masterFs: '', ports: '', direction: 'ingress', workmode: 'lacp-static' }
      this.createVisible = true
    },
    submitCreate() {
      if (!/^\d+\/\d+(\/\d+)?$/.test(this.form.masterFs.trim())) {
        this.$message.warning('主控端口格式应为 框/槽，如 0/17')
        return
      }
      if (!/^[\d,\-]+$/.test(this.form.ports.trim())) {
        this.$message.warning('成员端口列表格式如 0-1 或 0,1')
        return
      }
      this.$emit('create', { ...this.form })
      this.createVisible = false
    },
    confirmDelete(row) {
      this.$confirm(
        '将删除聚合组 ' + row.masterPort + '（' + row.portNum + ' 个成员口），' +
          '成员口恢复独立转发，上游链路会闪断，确定继续？',
        '删除聚合组',
        { type: 'warning', confirmButtonText: '确定删除', cancelButtonText: '取消' }
      )
        .then(() => {
          // 删除按设备 display 出来的完整主端口定位（0/17/0），现场 undo 口径
          this.$emit('remove', row.masterPort)
        })
        .catch(() => {})
    }
  }
}
</script>

<style scoped>
.la-toolbar {
  margin-bottom: 10px;
}
.la-hint {
  margin-left: 10px;
  color: #909399;
  font-size: 12px;
}
.la-field-hint {
  color: #909399;
  font-size: 12px;
  line-height: 18px;
}
</style>
