<template>
  <div class="chassis">
    <div class="chassis-bar">
      <span class="chassis-title">机框视图</span>
      <span class="chassis-sub">{{ model || 'OLT' }} · {{ count }} 槽位 · 已插 {{ boards.length }} 块</span>
      <span class="chassis-tip">板卡按槽位号插在机框里；没插板的槽位留空 · 点槽位高亮</span>
    </div>

    <!-- 槽位一排铺开，机框宽的时候左右滑 -->
    <div ref="scroll" class="chassis-scroll">
      <div class="chassis-frame">
        <!-- 上导轨：风扇 + STATUS 灯 + 厂商标 + 型号牌，照真机导轨摆 -->
        <div class="chassis-rail">
          <span class="rail-side">
            <span class="rail-fan"><i v-for="n in 4" :key="'lf-' + n" /></span>
            <span class="rail-status"><i /> STATUS</span>
          </span>
          <span class="rail-logo">{{ brand || model || 'OLT' }}</span>
          <span class="rail-model" :class="{ 'rail-model-empty': !model }">{{ model || '—' }}</span>
          <span class="rail-fan"><i v-for="n in 4" :key="'rf-' + n" /></span>
        </div>

        <!-- 槽位仓：等宽，板卡按 SlotID 插进去 -->
        <div class="chassis-bays" :style="gridStyle">
          <div
            v-for="slot in slotList"
            :key="'slot-' + slot.index"
            :ref="'bay-' + slot.index"
            class="chassis-bay"
            :class="{
              'chassis-bay-used': !!slot.board,
              'chassis-bay-active': isActive(slot)
            }"
            :title="bayTitle(slot)"
            @click="onSelect(slot)"
          >
            <template v-if="slot.board">
              <!-- 槽位里放的就是这块板的完整面板 -->
              <olt-board-panel
                compact
                :board="slot.board"
                :panel="slot.board.panel"
                :chassis-model="model"
                :hover-port="hoverPort"
                :expanded-onts="expandedOnts"
                @toggle="$emit('toggle', $event)"
                @hover="onHover"
                @leave="$emit('leave')"
              />
            </template>
            <div v-else class="chassis-bay-empty">
              <!-- 空槽位：银色盲面板（黑色拉手 + 通风箭头） -->
              <span class="blank-tab blank-tab-top" />
              <span class="blank-arrow" />
              <span class="blank-tab blank-tab-bottom" />
            </div>
          </div>
        </div>

        <!-- 下导轨：槽位号，和 display board 0 的 SlotID 一致 -->
        <div class="chassis-slotnos" :style="gridStyle">
          <span
            v-for="slot in slotList"
            :key="'no-' + slot.index"
            class="chassis-slotno"
            :class="{ 'chassis-slotno-active': isActive(slot) }"
          >{{ slot.index }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import OltBoardPanel from './OltBoardPanel'

/** 机框视图：板卡按槽位号插进机框槽位（槽位号同 display board 0 的 SlotID），支持点选并自动滚动。 */
export default {
  name: 'OltChassis',
  components: { OltBoardPanel },
  props: {
    /** 已采集到的板卡 */
    slots: {
      type: Array,
      default: () => []
    },
    /** 机框槽位数，没采到就按 12 兜底 */
    slotCount: {
      type: Number,
      default: 12
    },
    /** 机框型号，显示在框顶上 */
    model: {
      type: String,
      default: ''
    },
    /** 导轨正中的厂商标 */
    brand: {
      type: String,
      default: ''
    },
    /** 当前选中的槽位号，由父组件持有 */
    activeSlot: {
      type: [Number, String],
      default: null
    },
    /** 原样传给板卡面板 */
    hoverPort: {
      type: String,
      default: ''
    },
    expandedOnts: {
      type: Object,
      default: () => ({})
    }
  },
  computed: {
    boards() {
      return this.slots || []
    },
    /** 槽位数：后端算好传入，缺省按 12 兜底 */
    count() {
      return this.slotCount || 12
    },
    /** 槽位仓和槽位号用同一套栅格，保证号码对准槽位 */
    gridStyle() {
      return { gridTemplateColumns: 'repeat(' + this.count + ', 60px)' }
    },
    slotList() {
      const byNo = {}
      this.boards.forEach(board => {
        if (board.no != null) byNo[board.no] = board
      })
      const list = []
      for (let i = 0; i < this.count; i++) {
        list.push({ index: i, board: byNo[i] || null })
      }
      return list
    }
  },
  watch: {
    activeSlot() {
      this.$nextTick(this.scrollToActive)
    }
  },
  mounted() {
    this.$nextTick(this.scrollToActive)
  },
  methods: {
    isActive(slot) {
      return this.activeSlot !== null && this.activeSlot !== undefined && slot.index === this.activeSlot
    },
    /** 槽位太窄放不下文字，型号/状态放到鼠标悬停提示里 */
    bayTitle(slot) {
      if (!slot.board) return '空槽位 ' + slot.index
      return '槽位 ' + slot.index + ' · ' + slot.board.type + ' · ' + slot.board.statusText
    },
    onSelect(slot) {
      if (slot.board) {
        this.$emit('select', slot.index)
      }
    },
    /** 面板上鼠标停在某个口：把「槽位、端口」原样冒泡上去 */
    onHover(slotNo, portNo) {
      this.$emit('hover', slotNo, portNo)
    },
    /** 机框比页面宽：把选中的槽位滚到视口中间 */
    scrollToActive() {
      const box = this.$refs.scroll
      if (!box) return
      const ref = this.$refs['bay-' + this.activeSlot]
      const node = Array.isArray(ref) ? ref[0] : ref
      if (!node) return
      box.scrollLeft = node.offsetLeft - box.clientWidth / 2 + node.offsetWidth / 2
    }
  }
}
</script>

<style lang="scss" scoped>
@import '~@/styles/olt.scss';

.chassis {
  margin-top: 4px;
}
.chassis-bar {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding: 2px 2px 6px;
}
.chassis-title {
  font-size: 13px;
  font-weight: 600;
  color: #dfe6f0;
}
.chassis-sub {
  font-size: 11px;
  color: #7f8a99;
}
.chassis-tip {
  margin-left: auto;
  font-size: 11px;
  color: #5f6a78;
}
/* 12 个槽位铺开比页面宽，横向滚动 */
.chassis-scroll {
  overflow-x: auto;
  padding-bottom: 6px;
}
.chassis-frame {
  position: relative;
  display: inline-block;
  min-width: 100%;
  /* 机框外壳：深色金属边框，里面是银色的卡笼 */
  border: 1px solid #0e1116;
  border-radius: 7px;
  background: linear-gradient(#22262b, #14171b);
  box-shadow: inset 0 1px 0 #31363d, 0 2px 8px rgba(0, 0, 0, 0.45);
}
/* ---- 上导轨：风扇 + STATUS + 厂商标 + 型号牌 ---- */
.chassis-rail {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 34px;
  padding: 0 12px;
  border-radius: 6px 6px 0 0;
  background: linear-gradient(#26282c, #1a1c20);
  border-bottom: 1px solid #0c0e11;
}
.rail-side {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.rail-fan {
  display: inline-flex;
  gap: 3px;
  padding: 3px 5px;
  border: 1px solid #33373d;
  border-radius: 3px;
  background: #0f1114;
}
.rail-fan i {
  width: 2px;
  height: 10px;
  background: #4a515a;
}
.rail-status {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: #6f7883;
  font-size: 9px;
  letter-spacing: 1px;
  white-space: nowrap;
}
.rail-status i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #35c24f;
  box-shadow: 0 0 4px rgba(53, 194, 79, 0.8);
}
.rail-logo {
  flex: 1;
  text-align: center;
  color: #eceff3;
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 4px;
}
.rail-model {
  padding: 2px 10px;
  border: 1px solid #454b53;
  border-radius: 3px;
  background: #0f1114;
  color: #cfd6de;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 1px;
  white-space: nowrap;
}
.rail-model-empty {
  color: #565e68;
  border-color: #2c3138;
}
/* ---- 槽位仓：银色卡笼，空槽插的是盲面板 ---- */
.chassis-bays {
  display: grid;
  gap: 4px;
  padding: 6px;
  background: linear-gradient(#a7acb1, #bfc4c8);
}
.chassis-bay {
  box-sizing: border-box;
  /* 槽位尺寸与板卡面板 viewBox 一致 */
  width: 60px;
  height: 700px;
  padding: 0;
  /* 边框用 inset 阴影画，不挤占内容宽度 */
  box-shadow: inset 0 0 0 1px #8f959b;
  border-radius: 3px;
  background: #b3b8bd;
  overflow: hidden;
}
.chassis-bay-used {
  background: #14181e;
  box-shadow: inset 0 0 0 1px #2b313a;
}
.chassis-bay:hover {
  box-shadow: inset 0 0 0 1px #3f4c5c;
}
.chassis-bay-active,
.chassis-bay-active:hover {
  box-shadow: inset 0 0 0 1px #3f6f9f, 0 0 0 1px rgba(74, 158, 255, 0.25);
}
/* 空槽位：真机的银色盲面板——上下黑色拉手，中间一枚向上的通风箭头 */
.chassis-bay-empty {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  height: 100%;
  border-radius: 3px;
  background: linear-gradient(180deg, #e6e8ea, #c9cdd0 55%, #d9dcde);
  box-shadow: inset 0 0 0 1px #9aa0a5, inset 0 2px 3px rgba(255, 255, 255, 0.55);
}
.blank-tab {
  width: 28px;
  height: 9px;
  margin: 8px 0;
  border-radius: 2px;
  background: linear-gradient(#3c4147, #202429);
  box-shadow: inset 0 1px 0 #585d63;
}
.blank-arrow {
  position: relative;
  flex: 1;
  width: 10px;
  border-left: 1px solid #878d93;
}
.blank-arrow::before {
  content: '';
  position: absolute;
  top: -7px;
  left: -5.5px;
  border: 5px solid transparent;
  border-bottom: 8px solid #878d93;
}
/* 下导轨的槽位号：深色铭牌条，白字 */
.chassis-slotnos {
  display: grid;
  gap: 4px;
  padding: 0 6px 6px;
  background: linear-gradient(#a7acb1, #bfc4c8);
}
.chassis-slotno {
  padding: 3px 0;
  border-radius: 0 0 3px 3px;
  background: #23262b;
  border: 1px solid #0e1013;
  border-top: none;
  color: #c3c9d0;
  font-size: 11px;
  text-align: center;
}
.chassis-slotno-active {
  color: #4a9eff;
  border-color: #3f6f9f;
}
</style>
