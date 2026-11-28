<template>
  <div>
    <div v-if="!rows.length" class="olt-empty">{{ emptyText }}</div>

    <el-table v-else :data="rows" size="small" border max-height="620">
      <el-table-column label="ONU ID" width="80" align="center">
        <template slot-scope="{ row }">{{ row.ponNo }}/{{ row.ontId }}</template>
      </el-table-column>
      <el-table-column label="名称" min-width="130">
        <template slot-scope="{ row }">
          <span v-if="row.name">{{ row.name }}</span>
          <span v-else class="ont-sub-empty">-</span>
        </template>
      </el-table-column>
      <el-table-column :label="snLabel" width="150">
        <template slot-scope="{ row }">{{ row.sn }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90" align="center">
        <template slot-scope="{ row }">
          <span :class="row.online ? 'ont-online' : 'ont-offline'">
            <i class="ont-dot" />{{ row.stateText }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="认证状态" width="100" align="center">
        <template slot-scope="{ row }">
          <span v-if="row.authorized === true" class="ont-auth">
            <i class="el-icon-success" /> true
          </span>
          <span v-else-if="row.authorized === false" class="ont-auth ont-auth-no">
            <i class="el-icon-error" /> false
          </span>
          <span v-else class="ont-sub-empty">-</span>
        </template>
      </el-table-column>
      <el-table-column label="注册时间" width="150" align="center">
        <template slot-scope="{ row }">{{ row.registerTime || '-' }}</template>
      </el-table-column>
      <el-table-column label="最后掉线时间" width="150" align="center">
        <template slot-scope="{ row }">{{ row.lastOfflineTime || '-' }}</template>
      </el-table-column>
      <el-table-column label="掉线原因" width="110" align="center">
        <template slot-scope="{ row }">{{ row.offlineReason || '-' }}</template>
      </el-table-column>
      <el-table-column label="设备类型" width="100" align="center">
        <template slot-scope="{ row }">{{ row.deviceType || '-' }}</template>
      </el-table-column>
      <el-table-column label="ONU 类型" width="100" align="center">
        <template slot-scope="{ row }">{{ row.onuType || '-' }}</template>
      </el-table-column>
      <el-table-column label="逻辑距离(米)" width="110" align="center">
        <template slot-scope="{ row }">{{ row.distance != null ? row.distance : '-' }}</template>
      </el-table-column>
      <el-table-column label="接收光功率" width="110" align="center">
        <template slot-scope="{ row }">{{ row.rxPower || '-' }}</template>
      </el-table-column>
      <el-table-column label="配置" width="100" align="center">
        <template slot-scope="{ row }">
          <el-dropdown trigger="click" size="mini" @command="code => onCommand(row, code)">
            <span class="ont-config-link">配置 <i class="el-icon-arrow-down" /></span>
            <el-dropdown-menu slot="dropdown">
              <el-dropdown-item v-for="item in actions" :key="item.code" :command="item.code">
                {{ item.label }}
              </el-dropdown-item>
            </el-dropdown-menu>
          </el-dropdown>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script>
import { fetchOltOptions } from '@/api/olt'

/** ONU 平铺列表：一条 ONU 一行，缺省列显示「-」占位 */
export default {
  name: 'OltOntList',
  props: {
    /** 后端摊平并排好序的 ONU 行 */
    ontRows: {
      type: Array,
      default: () => []
    },
    /** 标识列表头：EPON 用 MAC，GPON 用 SN */
    snLabel: {
      type: String,
      default: 'SN / MAC'
    },
    emptyText: {
      type: String,
      default: '尚未采集到 ONU 数据。点上面的「同步」立即刷新。'
    }
  },
  data() {
    return {
      /** 「配置」下拉动作，由后端下发 */
      actions: []
    }
  },
  computed: {
    rows() {
      return this.ontRows || []
    }
  },
  created() {
    fetchOltOptions()
      .then(res => {
        this.actions = res.data && res.data.onuActions ? res.data.onuActions : []
      })
      .catch(() => {
        this.actions = []
      })
  },
  methods: {
    onCommand(row, code) {
      this.$emit('command', { onu: row, code })
      const action = this.actions.find(item => item.code === code)
      const label = action ? action.label : code
      this.$message({
        type: 'info',
        message: '「' + label + '」还没接入设备命令，这台 OLT 上先手动操作'
      })
    }
  }
}
</script>

<style lang="scss" scoped>
@import '~@/styles/olt.scss';

.ont-config-link {
  color: #409eff;
  cursor: pointer;
  font-size: 12px;
}

.ont-auth {
  color: #67c23a;
  font-size: 12px;

  &-no {
    color: #f56c6c;
  }
}
</style>
