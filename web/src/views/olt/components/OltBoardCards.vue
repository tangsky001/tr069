<template>
  <div>
    <div v-if="!slots.length" class="olt-empty">{{ emptyText }}</div>

    <template v-else>
      <!-- 机框视图：板卡按槽位号插进 12 个槽位里，槽位里就是这块板的完整面板 -->
      <olt-chassis
        :slots="slots"
        :slot-count="slotCount"
        :model="model"
        :brand="brand"
        :active-slot="activeSlot"
        :hover-port="hoverPort"
        :expanded-onts="expandedOnts"
        @select="onSelectSlot"
        @toggle="$emit('toggle', $event)"
        @hover="onHover"
        @leave="$emit('leave')"
      />

      <!-- 图例：三种灯色 + 点击提示 -->
      <div class="board-legend">
        <span class="board-legend-item"><i class="board-dot board-dot-online" />绿闪 在线</span>
        <span class="board-legend-item"><i class="board-dot board-dot-alarm" />红常亮 告警</span>
        <span class="board-legend-item"><i class="board-dot board-dot-offline" />灰 离线</span>
        <span class="board-legend-tip">板卡竖放，端口从上到下排；点端口看该口下的 ONT</span>
      </div>

      <!-- 点开的端口：ONT 明细放到机框下方整行显示，槽位本身保持干净 -->
      <div v-if="openPanels.length" class="board-ont-details">
        <div v-for="item in openPanels" :key="item.key" class="ont-panel">
          <div class="ont-panel-title">
            <span>
              槽位 {{ item.slot.no }} · {{ item.slot.type }} · {{ portName(item.slot, item.port) }}（{{ item.port.iface }}）下的 ONT
            </span>
            <span
              class="ont-panel-close"
              @click="$emit('toggle', { slotNo: item.slot.no, portNo: item.port.no, count: 1 })"
            >收起</span>
          </div>
          <div class="ont-list">
            <div v-for="ont in item.port.onts" :key="ont.id" class="ont-row">
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
    </template>
  </div>
</template>

<script>
import OltChassis from './OltChassis'

/**
 * 板卡卡片区：按机框插槽竖着并排的板卡面板 + 展开后的 ONT 明细。
 *
 * 纯展示组件：板卡数据由 mixin 折算好从外面传进来，交互（展开、高亮）冒泡给页面处理，
 * 这样华为页、通用页可以各用各的空态文案，不需要各自抄一遍卡片结构。
 *
 * 板卡不在外面单独成卡：整排插进 OltChassis 的槽位里（槽位号就是 SlotID，槽位总数由
 * display board 0 的槽位表决定），
 * 每个槽位里放的就是 OltBoardPanel.vue 画的那张面板——竖放的金属灰板卡 + 丝印 +
 * 端口一列竖排 + 圆形状态灯 + 右侧逐口标注，不再依赖板卡照片，也不会出现缺图占位。
 * 竖板很窄，点端口展开的 ONT 明细统一放到机框下方整行显示。
 */
export default {
  name: 'OltBoardCards',
  components: { OltChassis },
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
    /** 机框槽位数：display board 0 槽位表里最大 SlotID + 1（空槽位也算），决定机框画几格 */
    slotCount: {
      type: Number,
      default: 12
    },
    /** 机框型号（设备型号），印在机框顶上 */
    model: {
      type: String,
      default: ''
    },
    /** 机框上的厂商标（华为/中兴/烽火…），导轨正中那个大字 */
    brand: {
      type: String,
      default: ''
    },
    emptyText: {
      type: String,
      default: '尚无板卡数据，点上面的「同步」采集。'
    }
  },
  data() {
    return {
      // 机框里选中的槽位：默认选第一块板，点机框槽位再切
      activeSlot: null
    }
  },
  computed: {
    /** 已展开的端口（带着板卡和端口的引用，方便下面直接渲染明细） */
    openPanels() {
      const list = []
      this.slots.forEach(slot => {
        const ports = slot.ports || []
        ports.forEach(port => {
          if (port.onts.length && this.isOpen(slot.no, port.no)) {
            list.push({ key: slot.no + ':' + port.no, slot, port })
          }
        })
      })
      return list
    }
  },
  watch: {
    slots: {
      immediate: true,
      handler(list) {
        if (!list || !list.length) {
          this.activeSlot = null
          return
        }
        // 板卡列表变了（重新同步）就保持原选中；原选中没了就退回第一块
        const stillExists = list.some(slot => slot.no === this.activeSlot)
        if (!stillExists) {
          this.activeSlot = list[0].no
        }
      }
    }
  },
  methods: {
    isOpen(slotNo, portNo) {
      return !!this.expandedOnts[slotNo + ':' + portNo]
    },
    /** 明细标题里的口名：PON 板写 PON 0，其它板沿用设备给的 P0/P1 */
    portName(slot, port) {
      if (slot.panel === 'pon') {
        return 'PON ' + port.no
      }
      return port.name || 'P' + port.no
    },
    /** 面板上鼠标停在某个口：把「槽位、端口」两个参数原样冒泡上去 */
    onHover(slotNo, portNo) {
      this.$emit('hover', slotNo, portNo)
    },
    /** 点机框里的槽位：高亮这个槽位（机框自己会把它滚到视口中间） */
    onSelectSlot(slotNo) {
      this.activeSlot = slotNo
    }
  }
}
</script>

<style lang="scss" scoped>
@import '~@/styles/olt.scss';
</style>
