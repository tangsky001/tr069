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
/** 「配置」下拉里的动作，顺序按现场习惯排 */
const ONU_ACTIONS = [
  { code: 'detail', label: '查看详情' },
  { code: 'mac', label: '查看MAC地址信息' },
  { code: 'port', label: 'ONU端口配置' },
  { code: 'delete', label: '删除ONU' },
  { code: 'block', label: '添加到阻止列表' },
  { code: 'reboot', label: '重启ONU' },
  { code: 'optical', label: '关闭发光射机' },
  { code: 'unregister', label: '解注册ONU' },
  { code: 'config', label: '配置ONU信息' }
]

/**
 * ONU 平铺列表：一条 ONU 一行，不再按 PON 口折叠。
 *
 * <p>列按现场参考界面排：ONU ID、名称、MAC、状态、认证状态、注册时间、掉线信息、
 * 型号类字段、光功率、配置菜单。这台设备回显里没有的列照样占位，显示「-」——
 * 位置固定住，换一台设备就不用重新认表头。</p>
 */
export default {
  name: 'OltOntList',
  props: {
    /** 板卡 → 端口 → ONU，和 OltOntBoard 收的是同一份数据 */
    ontBoards: {
      type: Array,
      default: () => []
    },
    /** 标识列的表头：EPON 写「MAC 地址」，GPON 写「SN」 */
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
      actions: ONU_ACTIONS
    }
  },
  computed: {
    /** 摊平成一行一条 ONU，按 PON 口 → ONU ID 排序 */
    rows() {
      const rows = []
      const boards = this.ontBoards || []
      boards.forEach(slot => {
        const ports = slot.ports || []
        ports.forEach(port => {
          const onts = port.onts || []
          onts.forEach(ont => {
            rows.push({
              key: [slot.no, port.name, ont.id, ont.sn].join('-'),
              ponNo: port.no,
              portName: port.name,
              iface: port.iface,
              ontId: ont.id,
              name: ont.name,
              sn: ont.sn,
              stateText: ont.stateText,
              online: ont.online,
              authorized: ont.authorized,
              registerTime: ont.registerTime,
              lastOfflineTime: ont.lastOfflineTime,
              offlineReason: ont.offlineReason,
              deviceType: ont.deviceType,
              onuType: ont.onuType,
              distance: ont.distance,
              rxPower: ont.rxPower,
              desc: ont.desc
            })
          })
        })
      })
      rows.sort((a, b) => {
        const byPort = (a.ponNo || 0) - (b.ponNo || 0)
        if (byPort !== 0) {
          return byPort
        }
        return (a.ontId || 0) - (b.ontId || 0)
      })
      return rows
    }
  },
  methods: {
    onCommand(row, code) {
      // 交给外面接：命令要下到设备上，组件不掺和，先只把选中的 ONU 和动作抛上去
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
