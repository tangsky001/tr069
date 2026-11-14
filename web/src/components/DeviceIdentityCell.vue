<template>
  <!-- 表头模式：多行字段名 + 排序 + 光模块状态筛选 -->
  <div v-if="mode === 'header'" class="di-head">
    <div class="hd-row" @click="$emit('sort', 'manufacturer')">
      <span>终端厂家</span><i :class="sortIcon('manufacturer')" />
    </div>
    <div class="hd-row" @click="$emit('sort', 'productClass')">
      <span>终端型号</span><i :class="sortIcon('productClass')" />
    </div>
    <div class="hd-row">
      <span @click="$emit('sort', 'hardwareVersion')">硬件版本<i :class="sortIcon('hardwareVersion')" /></span>
      <span class="hd-sep">/</span>
      <span @click="$emit('sort', 'softwareVersion')">固件版本<i :class="sortIcon('softwareVersion')" /></span>
    </div>
    <div class="hd-row" @click="$emit('sort', 'pon')">
      <span>PON 类型</span><i :class="sortIcon('pon')" />
    </div>
    <el-select
      :value="opticFilter"
      size="mini"
      class="hd-select"
      placeholder="= 光模块状态 ="
      clearable
      @change="v => $emit('update:opticFilter', v || '')"
    >
      <el-option label="= 光模块状态 =" value="" />
      <el-option label="有光功率数据" value="ok" />
      <el-option label="无光功率数据" value="none" />
    </el-select>
  </div>

  <!-- 单元格模式：与表头同序铺值 -->
  <div v-else class="di-cell">
    <div class="cell-sub">{{ row.manufacturer || '-' }}</div>
    <div class="cell-sub">{{ row.productClass || '-' }}</div>
    <div class="cell-sub">{{ row.hardwareVersion || '-' }} / {{ row.softwareVersion || '-' }}</div>
    <div class="cell-sub pon-line">
      {{ ponType }}
      <template v-if="opticText">
        <i class="el-icon-view" /> {{ opticText }}
      </template>
    </div>
  </div>
</template>

<script>
/**
 * 设备终端信息：厂家 / 型号 / 硬件固件版本 / PON 类型 + 光功率。
 *
 * <p>两种用法：{@code mode="header"} 渲染多行字段名表头（排序与筛选通过事件抛给父组件），
 * 默认渲染单元格内容。父组件把它塞进 el-table-column 的 header / default 插槽即可。</p>
 */
export default {
  name: 'DeviceIdentityCell',
  props: {
    mode: { type: String, default: 'cell' },
    row: { type: Object, default: () => ({}) },
    biz: { type: Object, default: () => ({}) },
    sortKey: { type: String, default: '' },
    sortOrder: { type: String, default: '' },
    opticFilter: { type: String, default: '' }
  },
  computed: {
    // PON 类型与光功率换算都在后端完成：型号里写没写 GPON/EPON、
    // 0.1µW 转 dBm 的对数换算，都只保留一份
    ponType() {
      return this.biz.ponType || 'GE'
    },
    opticText() {
      return this.biz.opticText || ''
    }
  },
  methods: {
    sortIcon(key) {
      if (this.sortKey !== key) return 'el-icon-sort hd-sort'
      return this.sortOrder === 'asc' ? 'el-icon-caret-top hd-sort' : 'el-icon-caret-bottom hd-sort'
    }
  }
}
</script>

<style scoped>
.di-head {
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
.hd-sep {
  color: #909399;
}
.hd-select {
  width: 100%;
  margin-top: 4px;
}
.di-cell .cell-sub {
  color: #909399;
  font-size: 12px;
  margin-top: 2px;
}
.pon-line {
  color: #2f7d32;
}
</style>
