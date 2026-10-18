<template>
  <!-- 表头模式：运行时长 / 加入时间 / 最后汇报时间 / 汇报IP，均可排序 -->
  <div v-if="mode === 'header'" class="dr-head">
    <div v-for="key in keys" :key="key" class="hd-row" @click="$emit('sort', key)">
      <span>{{ labelOf(key) }}</span><i :class="sortIcon(key)" />
    </div>
  </div>

  <!-- 单元格模式：与表头同序铺值 -->
  <div v-else class="dr-cell">
    <div class="cell-sub">{{ runtimeText }}</div>
    <div class="cell-sub">{{ formatTime(row.createdAt) }}</div>
    <div class="cell-sub">{{ formatTime(row.lastContactTime) }}</div>
    <div class="cell-sub">{{ reportIp }}</div>
  </div>
</template>

<script>
export default {
  name: 'DeviceRuntimeCell',
  props: {
    mode: { type: String, default: 'cell' },
    row: { type: Object, default: () => ({}) },
    biz: { type: Object, default: () => ({}) },
    sortKey: { type: String, default: '' },
    sortOrder: { type: String, default: '' }
  },
  data() {
    return {
      keys: ['upTime', 'createdAt', 'lastContactTime', 'ip']
    }
  },
  computed: {
    runtimeText() {
      const info = this.biz.device || []
      const item = info.find(x => x.params && x.params.UpTime !== undefined)
      const v = item ? item.params.UpTime : ''
      return this.durationOf(v) || '-'
    },
    reportIp() {
      return this.biz.ip || '-'
    }
  },
  methods: {
    labelOf(key) {
      const map = {
        upTime: '运行时长',
        createdAt: '加入时间',
        lastContactTime: '最后汇报时间',
        ip: '汇报IP'
      }
      return map[key] || key
    },
    sortIcon(key) {
      if (this.sortKey !== key) return 'el-icon-sort hd-sort'
      return this.sortOrder === 'asc' ? 'el-icon-caret-top hd-sort' : 'el-icon-caret-bottom hd-sort'
    },
    formatTime(value) {
      return value ? String(value).replace('T', ' ') : '-'
    },
    durationOf(seconds) {
      const n = parseInt(seconds, 10)
      if (!n || n < 0) return ''
      const d = Math.floor(n / 86400)
      const h = Math.floor((n % 86400) / 3600)
      const m = Math.floor((n % 3600) / 60)
      const s = n % 60
      return (d ? d + '天' : '') + h + '小时' + m + '分' + s + '秒'
    }
  }
}
</script>

<style scoped>
.dr-head {
  padding: 2px 0;
}
.hd-row {
  display: flex;
  align-items: center;
  gap: 2px;
  color: #67a04e;
  font-size: 12px;
  line-height: 20px;
  cursor: pointer;
  user-select: none;
}
.hd-row:hover {
  color: #2f7d32;
}
.hd-sort {
  font-size: 12px;
}
.dr-cell .cell-sub {
  color: #303133;
  font-size: 12px;
  line-height: 20px;
}
</style>
