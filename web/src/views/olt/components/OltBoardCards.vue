<template>
  <div>
    <div v-if="!slots.length" class="olt-empty">{{ emptyText }}</div>

    <div v-else class="board-grid" :class="{ 'board-grid-single': singleColumn }">
      <div v-for="slot in slots" :key="slot.no" class="board-card">
        <div class="board-row"><span class="k">板卡插槽</span><span class="v">{{ slot.no }}</span></div>
        <div class="board-row"><span class="k">板卡型号</span><span class="v">{{ slot.type }}</span></div>
        <div class="board-row">
          <span class="k">运行状态</span>
          <span class="v" :class="'board-status-' + slot.statusType">
            <i :class="slot.statusIcon" />{{ slot.statusText }}
          </span>
        </div>
        <div class="board-row"><span class="k">描述</span><span class="v">{{ slot.description || '-' }}</span></div>
        <div class="board-row"><span class="k">温度</span><span class="v">{{ slot.temperature == null ? '-' : slot.temperature + '°C' }}</span></div>
        <template v-if="slot.ports.length">
          <div class="board-row"><span class="k">端口 / ONT</span><span class="v">{{ slot.onuSummary }}</span></div>
        </template>
        <div class="board-row no-border"><span class="k">示意图</span><span class="v" /></div>
        <div class="board-photo-box" :class="{ 'board-photo-box-wide': isWideImage(slot) }">
          <div
            v-if="slot.image && !isImageMissing(slot.type)"
            class="board-photo-wrap"
            :class="{
              'board-photo-rows': slot.markers.length && !isWideImage(slot),
              'board-photo-wide': isWideImage(slot)
            }"
          >
            <img
              :src="slot.image"
              :alt="slot.type"
              class="board-img"
              :class="{ 'board-img-wide': isWideImage(slot) }"
              :style="wideImageStyle(slot)"
              loading="lazy"
              @load="onImageLoad(slot.type, $event)"
              @error="$emit('image-error', slot.type)"
            >
            <!-- 面板布局固定的型号：端口行就钉在图上对应物理口的高度，一行对一个箭头 -->
            <template v-if="slot.markers.length && !isWideImage(slot)">
              <div
                v-for="port in slot.ports"
                :key="'overlay-' + port.no"
                class="port-row port-overlay"
                :class="{
                  'port-row-clickable': port.onts.length,
                  'port-row-active': isActive(slot.no, port.no)
                }"
                :style="{ top: top(slot, port.no) + '%' }"
                @click="$emit('toggle', { slotNo: slot.no, portNo: port.no, count: port.onts.length })"
                @mouseenter="$emit('hover', key(slot.no, port.no))"
                @mouseleave="$emit('leave')"
              >
                <span class="port-caret">{{ port.onts.length ? (isOpen(slot.no, port.no) ? '▾' : '▸') : '' }}</span>
                <span class="port-name">{{ port.name }}</span>
                <span class="port-iface">{{ port.iface }}</span>
                <span v-if="port.linkText" class="port-link" :class="'port-link-' + port.link">{{ port.linkText }}</span>
                <span v-if="port.onuText" class="port-ont">{{ port.onuText }}</span>
              </div>
            </template>
            <!-- 横向整机图：端口行在图的下方按顺序排开，跑马线横向走到各自 PON 口的正下方再上折连上去 -->
            <template v-else-if="slot.markers.length">
              <template v-for="(port, index) in slot.ports">
                <span
                  :key="'lead-h-' + port.no"
                  class="port-lead-h"
                  :class="{ 'port-lead-active': isActive(slot.no, port.no) }"
                  :style="lead(slot, port, index)"
                />
                <span
                  :key="'lead-v-' + port.no"
                  class="port-lead-v"
                  :class="{ 'port-lead-active': isActive(slot.no, port.no) }"
                  :style="{ left: left(slot, port.no) + '%' }"
                />
                <div
                  :key="'overlayx-' + port.no"
                  class="port-row port-overlay-x"
                  :class="{
                    'port-row-clickable': port.onts.length,
                    'port-row-active': isActive(slot.no, port.no)
                  }"
                  :style="{ left: rowLeft(slot, index) + '%' }"
                  @click="$emit('toggle', { slotNo: slot.no, portNo: port.no, count: port.onts.length })"
                  @mouseenter="$emit('hover', key(slot.no, port.no))"
                  @mouseleave="$emit('leave')"
                >
                  <span class="port-caret">{{ port.onts.length ? (isOpen(slot.no, port.no) ? '▾' : '▸') : '' }}</span>
                  <span class="port-name">{{ port.name }}</span>
                  <span class="port-iface">{{ port.iface }}</span>
                  <span v-if="port.linkText" class="port-link" :class="'port-link-' + port.link">{{ port.linkText }}</span>
                  <span v-if="port.onuText" class="port-ont">{{ port.onuText }}</span>
                </div>
              </template>
            </template>
          </div>
          <div v-else class="board-no-image">暂无该型号示意图</div>

          <!-- 没有面板布局（或没图）的型号：端口行按普通列表排在示意图下面 -->
          <div v-if="!slot.markers.length && slot.ports.length" class="port-list">
            <div
              v-for="port in slot.ports"
              :key="'row-' + port.no"
              class="port-row"
              :class="{
                'port-row-clickable': port.onts.length,
                'port-row-active': isActive(slot.no, port.no)
              }"
              @click="$emit('toggle', { slotNo: slot.no, portNo: port.no, count: port.onts.length })"
              @mouseenter="$emit('hover', key(slot.no, port.no))"
              @mouseleave="$emit('leave')"
            >
              <span class="port-caret">{{ port.onts.length ? (isOpen(slot.no, port.no) ? '▾' : '▸') : '' }}</span>
              <span class="port-name">{{ port.name }}</span>
              <span class="port-iface">{{ port.iface }}</span>
              <!-- 光模块状态没采到时（有的版本没有 display port state）不画灯，免得被当成「不在位」 -->
              <i v-if="port.hasOptic" class="port-lamp" :class="'lamp-' + port.lamp" :title="port.lampText" />
              <span v-if="port.hasOptic" class="port-rx">{{ port.rxPower || '--' }}</span>
              <span v-if="port.linkText" class="port-link" :class="'port-link-' + port.link">{{ port.linkText }}</span>
              <span v-if="port.onuText" class="port-ont">{{ port.onuText }}</span>
            </div>
          </div>

          <!-- 点开的端口：ONT 明细面板，两种模式共用（overlay 模式渲染在示意图下方） -->
          <div v-for="port in slot.ports" :key="'panel-' + port.no">
            <div v-if="port.onts.length && isOpen(slot.no, port.no)" class="ont-panel">
              <div class="ont-panel-title">
                <span>{{ port.name }}（{{ port.iface }}）下的 ONT</span>
                <span class="ont-panel-close" @click="$emit('toggle', { slotNo: slot.no, portNo: port.no, count: 1 })">收起</span>
              </div>
              <div class="ont-list">
                <div v-for="ont in port.onts" :key="ont.id" class="ont-row">
                  <span class="ont-id">#{{ ont.id }}</span>
                  <span class="ont-sn">{{ ont.sn }}</span>
                  <span class="ont-desc">{{ ont.desc }}</span>
                  <span class="ont-state" :class="ont.online ? 'ont-online' : 'ont-offline'">
                    <i class="ont-dot" />{{ ont.stateText }}
                  </span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
/**
 * 板卡卡片区：插槽 / 型号 / 状态 / 描述 / 温度 / 示意图。
 *
 * 纯展示组件：板卡数据由 mixin 折算好从外面传进来，交互（展开、高亮、缺图）冒泡给页面处理，
 * 这样华为页、通用页可以各用各的空态文案，不需要各自抄一遍卡片结构。
 */
export default {
  name: 'OltBoardCards',
  props: {
    slots: {
      type: Array,
      default: () => []
    },
    /** 展开状态与 hover 状态由父持有（mixin 里的数据），这里只负责判定 */
    expandedOnts: {
      type: Object,
      default: () => ({})
    },
    hoverPort: {
      type: String,
      default: ''
    },
    /** 记为缺图的型号，避免同一个 404 反复请求 */
    missingImages: {
      type: Array,
      default: () => []
    },
    emptyText: {
      type: String,
      default: '尚无板卡数据，点上面的「同步」采集。'
    }
  },
  data() {
    return {
      /**
       * 各型号示意图的原始像素尺寸，图片加载后按 naturalWidth/naturalHeight 记下来。
       * 板卡图是竖长条（41x719 那类），整机图是横向的（鸿升光 HSGQ-E04 是 770x125），
       * 两种图的摆法不一样，所以按原图方向决定怎么显示。
       */
      imageSizes: {}
    }
  },
  computed: {
    /** 有横向示意图时卡片改成整行单列，横向图才铺得开 */
    singleColumn() {
      return this.slots.some(slot => this.isWideImage(slot))
    }
  },
  methods: {
    key(slotNo, portNo) {
      return slotNo + ':' + portNo
    },
    isOpen(slotNo, portNo) {
      return !!this.expandedOnts[this.key(slotNo, portNo)]
    },
    isActive(slotNo, portNo) {
      return this.hoverPort === this.key(slotNo, portNo)
    },
    top(slot, portNo) {
      const marker = slot.markers.find(item => item.no === portNo)
      return marker ? marker.top : 0
    },
    left(slot, portNo) {
      const marker = slot.markers.find(item => item.no === portNo)
      return marker ? marker.left : 0
    },
    /**
     * 横向图下端端口行的横向位置：几个口就把图下方等分成几格，行摆在各格中间。
     * 行不贴着口摆（口挨得太近会挤），横向的空档交给跑马线去走。
     */
    rowLeft(slot, index) {
      const total = slot.ports.length || 1
      return (index + 0.5) / total * 100
    },
    /** 跑马线的横段：从行的中心横向走到物理口的正下方 */
    lead(slot, port, index) {
      const rowX = this.rowLeft(slot, index)
      const portX = this.left(slot, port.no)
      return {
        left: Math.min(rowX, portX) + '%',
        width: Math.abs(rowX - portX) + '%'
      }
    },
    isImageMissing(type) {
      return this.missingImages.indexOf(type) >= 0
    },
    /** 图片加载完成后记下原始尺寸；拿不到（还没加载/被拦）就按竖条板的默认摆法 */
    onImageLoad(type, event) {
      const img = event && event.target
      if (!img || !img.naturalWidth) {
        return
      }
      this.$set(this.imageSizes, type, { width: img.naturalWidth, height: img.naturalHeight })
    },
    /**
     * 横向示意图：型号档案里声明了就照声明走（图还没加载完也能摆对），
     * 没声明的按图片加载后量到的原始尺寸判断，竖条板卡图照旧竖着放。
     */
    isWideImage(slot) {
      if (slot.wide) {
        return true
      }
      const size = this.imageSizes[slot.type]
      return !!size && size.width > size.height
    },
    /**
     * 横向图：铺满整行，但最多到原图宽度，不让小图被拉花。
     * 原始尺寸要等图片加载完才有，没量到时先不限宽（width:100% 兜底）。
     */
    wideImageStyle(slot) {
      const size = this.imageSizes[slot.type]
      if (!size) {
        return null
      }
      return this.isWideImage(slot) ? { maxWidth: size.width + 'px' } : null
    }
  }
}
</script>

<style lang="scss" scoped>
@import '~@/styles/olt.scss';
</style>
