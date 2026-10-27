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
        <!-- 上导轨：机框型号 + 风扇格栅 -->
        <div class="chassis-rail">
          <span class="chassis-brand">MA5683T</span>
          <span class="chassis-fan">
            <i v-for="n in 9" :key="'fan-' + n" />
          </span>
          <span class="chassis-rail-tail">{{ count }} 槽位</span>
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
              <!-- 槽位里就是这块板：60 宽槽位只放板卡本体（55 宽），型号/灯/端口都印在板上 -->
              <olt-board-panel
                compact
                :board="slot.board"
                :panel="slot.board.panel"
                :hover-port="hoverPort"
                :expanded-onts="expandedOnts"
                @toggle="$emit('toggle', $event)"
                @hover="onHover"
                @leave="$emit('leave')"
              />
            </template>
            <div v-else class="chassis-bay-empty">
              <span class="chassis-bay-empty-no">{{ slot.index }}</span>
              <span class="chassis-bay-empty-text">空槽位</span>
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

/**
 * 机框视图：把板卡**插进** MA5683T 机框的槽位里。
 *
 * 机框外壳用 HTML/CSS 画（上导轨风扇格栅、下导轨槽位号），12 个槽位等宽排成一排，
 * 每个槽位放的是真正的板卡面板（`OltBoardPanel`——竖放板卡 + 右侧逐口标注），
 * 槽位号就是 `display board 0` 的 SlotID：板卡按 `slotNo` 落到对应槽位，没插板的槽位画成空槽位。
 *
 * 一排 12 个槽位比页面宽，机框横向滚动；初次渲染自动滚到选中的那块板上，不用自己找。
 */
export default {
  name: 'OltChassis',
  components: { OltBoardPanel },
  props: {
    /** 已采集到的板卡（mixin 里的 slots） */
    slots: {
      type: Array,
      default: () => []
    },
    /**
     * 机框槽位数：来自 display board 0 的槽位表（最大 SlotID + 1，空槽位也算），
     * 没采到就按 12 个业务槽位兜底；出现更大槽位号时再补齐。
     */
    slotCount: {
      type: Number,
      default: 12
    },
    /** 机框型号（设备型号），显示在框顶上 */
    model: {
      type: String,
      default: ''
    },
    /** 当前选中的槽位号（外面持有，点槽位时 select 出去） */
    activeSlot: {
      type: [Number, String],
      default: null
    },
    /** 下面两件原样传给板卡面板 */
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
    /** 实际槽位数：至少 12 个，有板子插在更大的槽位号上就补齐 */
    count() {
      const maxNo = this.boards.reduce((max, board) => Math.max(max, board.no == null ? -1 : board.no), -1)
      return Math.max(this.slotCount, maxNo + 1)
    },
    /**
     * 槽位仓和槽位号用同一套栅格，保证号码对准槽位。
     * 槽位宽 60（板卡 55 插进去，两边各留 2.5），12 个槽位 + 框一共约 760 宽，一屏放得下整台机器。
     */
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
  border: 1px solid #343c47;
  border-radius: 5px;
  background: #171c22;
}
/* 上下导轨 */
.chassis-rail {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 26px;
  padding: 0 10px;
  border-radius: 4px 4px 0 0;
  background: #1d232b;
  border-bottom: 1px solid #2c333d;
}
.chassis-brand {
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 1.4px;
  color: #cfd8e3;
}
.chassis-fan {
  display: inline-flex;
  gap: 3px;
  margin-left: auto;
  padding: 2px 5px;
  border: 1px solid #2c333d;
  border-radius: 3px;
  background: #10141a;
}
.chassis-fan i {
  width: 2px;
  height: 12px;
  background: #3a434f;
}
.chassis-rail-tail {
  font-size: 11px;
  color: #6f7883;
}
/* ---- 槽位仓 ---- */
.chassis-bays {
  display: grid;
  gap: 3px;
  padding: 4px;
  background: #0d1116;
}
.chassis-bay {
  box-sizing: border-box;
  /* 槽位就是 60 × 700，和板卡面板 viewBox 一样大：板卡 55×700 插进去，上下顶齐不留缝 */
  width: 60px;
  height: 700px;
  padding: 0;
  /* 边框用 inset 阴影画：真边框会吃掉左右各 1px，SVG 按 58 宽等比缩放就矮一截，底下会空出一条缝 */
  box-shadow: inset 0 0 0 1px #242a33;
  border-radius: 4px;
  background: #0b0e12;
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
/* 空槽位：虚线仓 + 槽位号，和插了板的槽位一样高 */
.chassis-bay-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  height: 100%;
  border: 1px dashed #2a313a;
  border-radius: 4px;
  background: #0f1318;
}
.chassis-bay-empty-no {
  font-size: 20px;
  font-weight: 600;
  color: #39404a;
}
.chassis-bay-empty-text {
  font-size: 11px;
  color: #333a44;
}
/* 下导轨的槽位号 */
.chassis-slotnos {
  display: grid;
  gap: 3px;
  padding: 0 4px 4px;
  background: #0d1116;
}
.chassis-slotno {
  padding: 3px 0;
  border-radius: 0 0 4px 4px;
  background: #1d232b;
  border: 1px solid #2c333d;
  border-top: none;
  color: #7f8a99;
  font-size: 11px;
  text-align: center;
}
.chassis-slotno-active {
  color: #4a9eff;
  border-color: #3f6f9f;
}
</style>
