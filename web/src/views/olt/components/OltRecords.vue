<template>
  <div>
    <div class="record-head">
      <span>最近 20 次连接与同步记录，展开可看设备原始回显。</span>
      <el-button size="mini" icon="el-icon-refresh" @click="$emit('refresh')">刷新</el-button>
    </div>
    <el-table :data="records" border size="small">
      <el-table-column type="expand">
        <template slot-scope="{ row }">
          <pre class="raw-output">{{ row.rawPreview || '本次无回显（连接阶段即失败）' }}</pre>
        </template>
      </el-table-column>
      <el-table-column label="时间" width="170">
        <template slot-scope="{ row }">{{ row.createdAt ? String(row.createdAt).replace('T', ' ') : '-' }}</template>
      </el-table-column>
      <el-table-column label="动作" width="100" align="center">
        <template slot-scope="{ row }">
          <el-tag size="mini" :type="actionOf(row).type">{{ actionOf(row).text }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="结果" width="90" align="center">
        <template slot-scope="{ row }">
          <el-tag size="mini" :type="row.success ? 'success' : 'danger'">{{ row.success ? '成功' : '失败' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="说明" prop="message" min-width="320" show-overflow-tooltip />
      <el-table-column label="耗时" width="100" align="center">
        <template slot-scope="{ row }">{{ row.costMs == null ? '-' : row.costMs + ' ms' }}</template>
      </el-table-column>
      <el-table-column label="操作人" width="120">
        <template slot-scope="{ row }">{{ row.operator || '-' }}</template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script>
/**
 * 采集记录：每次诊断、同步的连接耗时与原始回显。
 *
 * 三个厂商页共用，排障时看的就是这张表——采到的回显在这儿，拉不到数据时先看它。
 */

/**
 * 动作类型 → 标签文案与颜色。类型由后端随记录给（TEST/SYNC/CONFIG/QUERY/DELETE），
 * 这里只做展示映射：查是查、删是删，不能再把查询和删除都叫「诊断」。
 */
const ACTION_TAGS = {
  TEST: { text: '诊断', type: 'info' },
  SYNC: { text: '同步', type: 'success' },
  CONFIG: { text: '配置', type: 'warning' },
  QUERY: { text: '查询', type: '' },
  DELETE: { text: '删除', type: 'danger' }
}

export default {
  name: 'OltRecords',
  props: {
    records: {
      type: Array,
      default: () => []
    }
  },
  methods: {
    actionOf(row) {
      return ACTION_TAGS[row && row.action] || { text: row && row.action ? row.action : '-', type: 'info' }
    }
  }
}
</script>

<style lang="scss" scoped>
@import '~@/styles/olt.scss';
</style>
