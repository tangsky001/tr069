<template>
  <div>
    <div v-if="!ontBoards.length" class="olt-empty">{{ emptyText }}</div>

    <div v-for="slot in ontBoards" :key="'ont-' + slot.no" class="ont-board">
      <div class="ont-board-title">
        槽位 {{ slot.no }} · {{ slot.type }}
        <span class="ont-board-sum">{{ slot.onuSummary }}</span>
      </div>
      <el-table :data="slot.ports" size="small" border>
        <el-table-column type="expand">
          <template slot-scope="{ row }">
            <el-table v-if="row.onts.length" :data="row.onts" size="mini" border class="ont-sub-table">
              <el-table-column label="ONT ID" prop="id" width="80" align="center" />
              <el-table-column :label="snLabel" prop="sn" width="210" />
              <el-table-column label="型号" prop="desc" />
              <el-table-column label="状态" width="90" align="center">
                <template slot-scope="scope">
                  <span :class="scope.row.online ? 'ont-online' : 'ont-offline'">
                    <i class="ont-dot" />{{ scope.row.stateText }}
                  </span>
                </template>
              </el-table-column>
            </el-table>
            <div v-else class="ont-sub-empty">该端口下没有 ONT</div>
          </template>
        </el-table-column>
        <el-table-column label="端口" prop="name" width="80" />
        <el-table-column label="接口（框/槽/口）" prop="iface" width="140" />
        <el-table-column label="ONT 总数" prop="onuTotal" width="100" align="center" />
        <el-table-column label="在线" prop="onuOnline" width="80" align="center" />
        <el-table-column label="离线" prop="offline" width="80" align="center" />
        <el-table-column label="光模块发光" width="120" align="center">
          <template slot-scope="{ row }">
            <span v-if="row.txPower">{{ row.txPower }}</span>
            <span v-else class="ont-sub-empty">--</span>
          </template>
        </el-table-column>
        <el-table-column label="光模块收光" width="120" align="center">
          <template slot-scope="{ row }">
            <span v-if="row.hasOptic">{{ row.rxPower || '--' }}</span>
            <span v-else class="ont-sub-empty">未采集</span>
          </template>
        </el-table-column>
        <el-table-column label="光模块温度" width="110" align="center">
          <template slot-scope="{ row }">
            <span v-if="row.opticTemperature">{{ row.opticTemperature }}</span>
            <span v-else class="ont-sub-empty">--</span>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script>
/**
 * ONT 配置 / 状态：按板卡分表，展开端口看该口下每条 ONT。
 *
 * GPON 设备上报 SN、EPON 设备上报 MAC，表头由外面给（snLabel）。
 */
export default {
  name: 'OltOntBoard',
  props: {
    ontBoards: {
      type: Array,
      default: () => []
    },
    snLabel: {
      type: String,
      default: 'SN / MAC'
    },
    emptyText: {
      type: String,
      default: '尚未采集到端口/ONT 数据，点上面的「同步」立即刷新。'
    }
  }
}
</script>

<style lang="scss" scoped>
@import '~@/styles/olt.scss';
</style>
