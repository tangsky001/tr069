<template>
  <div class="board-panel" :class="{ 'board-panel-compact': compact }">
    <svg
      class="board-panel-svg"
      :viewBox="viewBox"
      preserveAspectRatio="xMidYMin meet"
      role="img"
      :aria-label="ariaLabel"
    >
      <defs>
        <!-- 板卡底板：深灰金属，上亮下暗 -->
        <linearGradient :id="ids.plate" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stop-color="#454b55" />
          <stop offset="36%" stop-color="#363b43" />
          <stop offset="100%" stop-color="#23272d" />
        </linearGradient>
        <!-- 端口金属笼 -->
        <linearGradient :id="ids.socket" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stop-color="#6b737f" />
          <stop offset="55%" stop-color="#4a515b" />
          <stop offset="100%" stop-color="#33383f" />
        </linearGradient>
        <!-- 螺丝：金属圆头 -->
        <radialGradient :id="ids.screw" cx="35%" cy="30%" r="75%">
          <stop offset="0%" stop-color="#8a929e" />
          <stop offset="70%" stop-color="#4d535c" />
          <stop offset="100%" stop-color="#2b2f35" />
        </radialGradient>
        <!-- 拉丝纹理：一层极淡的横线，让底板有金属拉丝感 -->
        <pattern :id="ids.brush" width="6" height="6" patternUnits="userSpaceOnUse">
          <line x1="0" y1="0.5" x2="6" y2="0.5" stroke="#ffffff" stroke-opacity="0.035" stroke-width="1" />
        </pattern>
        <!-- 导轨竖纹 -->
        <pattern :id="ids.rail" width="4" height="10" patternUnits="userSpaceOnUse">
          <line x1="0" y1="0" x2="0" y2="10" stroke="#000000" stroke-opacity="0.45" stroke-width="1" />
        </pattern>
      </defs>

      <!-- 机框导轨：板卡竖着插在槽位里，左右两条导轨露在外面 -->
      <rect class="bp-rail" x="0" y="0" :width="width" :height="height" rx="5" />
      <!-- 两侧导轨：插进机框时槽位自己有隔板，就只画板卡本体 -->
      <rect v-if="!compact" x="1" y="1" width="5" :height="height - 2" :fill="'url(#' + ids.rail + ')'" />
      <rect v-if="!compact" :x="plateRight" y="1" width="5" :height="height - 2" :fill="'url(#' + ids.rail + ')'" />

      <!-- 板卡底板（竖放，窄条） -->
      <rect
        class="bp-plate"
        x="6"
        y="6"
        :width="plateW"
        :height="height - 12"
        rx="3"
        :fill="'url(#' + ids.plate + ')'"
      />
      <rect
        class="bp-plate-brush"
        x="6"
        y="6"
        :width="plateW"
        :height="height - 12"
        rx="3"
        :fill="'url(#' + ids.brush + ')'"
      />
      <rect class="bp-plate-edge" x="6" y="6" :width="plateW" height="1.2" rx="0.6" />
      <rect class="bp-plate-shadow" x="6" :y="height - 9" :width="plateW" height="1.2" rx="0.6" />

      <!-- 顶部助拔器（上下一掰就能把板卡抽出来） -->
      <g class="bp-handle">
        <rect x="23" y="10" width="21" height="12" rx="4" fill="#3b414a" stroke="#5b626d" stroke-width="0.8" />
        <rect x="28" y="13" width="11" height="6" rx="3" fill="#1d2127" stroke="#4a515b" stroke-width="0.7" />
      </g>

      <!-- 板上四角螺丝 -->
      <g class="bp-screws">
        <g v-for="screw in screws" :key="'screw-' + screw.x + '-' + screw.y">
          <circle :cx="screw.x" :cy="screw.y" r="2.8" :fill="'url(#' + ids.screw + ')'" stroke="#1b1e23" stroke-width="0.8" />
          <line :x1="screw.x - 1.4" :y1="screw.y" :x2="screw.x + 1.4" :y2="screw.y" stroke="#22262c" stroke-width="1" />
          <line :x1="screw.x" :y1="screw.y - 1.4" :x2="screw.x" :y2="screw.y + 1.4" stroke="#22262c" stroke-width="1" />
        </g>
      </g>

      <!-- 丝印：型号 / 槽位 / 端口类型（板卡窄，字号跟着收，别压到右边） -->
      <text class="bp-model" x="9" y="34">{{ modelText }}</text>
      <text class="bp-slot" x="59" y="34" text-anchor="end">SLOT {{ slotText }}</text>
      <text class="bp-subtitle" x="9" y="45">{{ subtitle }}</text>

      <!-- 板卡灯：RUN / ALM / ACT / ENV -->
      <g class="bp-board-leds">
        <text class="bp-board-led-label" x="9" y="58">RUN</text>
        <circle class="bp-led" :class="'bp-led-' + runState" cx="22" cy="55.5" r="2" />
        <text class="bp-board-led-label" x="34" y="58">ALM</text>
        <circle class="bp-led" :class="'bp-led-' + alarmState" cx="47" cy="55.5" r="2" />
        <text class="bp-board-led-label" x="9" y="69">ACT</text>
        <circle class="bp-led" :class="'bp-led-' + actState" cx="22" cy="66.5" r="2" />
        <text class="bp-board-led-label" x="34" y="69">ENV</text>
        <circle class="bp-led" :class="'bp-led-' + envState" cx="47" cy="66.5" r="2" />
      </g>

      <!-- 主控板的管理区：RESET 按钮 + CON 控制台 + ETH 管理网口 + ESC，真板卡就印在板子左侧 -->
      <g v-if="mgmtRows.length" class="bp-mgmt">
        <g class="bp-reset">
          <circle class="bp-reset-ring" cx="11" :cy="mgmtTop - 10" r="3.4" />
          <circle class="bp-reset-dot" cx="11" :cy="mgmtTop - 10" r="1.2" />
          <text class="bp-mgmt-reset" x="16" :y="mgmtTop - 7.5">RESET</text>
        </g>
        <template v-if="!compact">
          <line class="bp-lead" x1="67" :y1="mgmtTop - 10" x2="75" :y2="mgmtTop - 10" />
          <line class="bp-lead" x1="75" :y1="mgmtTop - 14" x2="75" :y2="mgmtTop - 6" />
          <text class="bp-anno-sub" x="81" :y="mgmtTop - 7.5">复位按钮</text>
        </template>

        <g v-for="(row, index) in mgmtRows" :key="'mgmt-' + row.name">
          <!-- 管理口本体：RJ45 电口，和业务口同一列（左边缘 20），方角金属笼 -->
          <rect
            class="bp-socket-body"
            x="20"
            :y="mgmtRowY(index) + 22"
            width="26"
            height="32"
            rx="1"
            :fill="'url(#' + ids.socket + ')'"
          />
          <rect class="bp-socket-hole" x="22" :y="mgmtRowY(index) + 25" width="22" height="26" rx="0.6" />
          <!-- 8 根金手指只占口的上半截，拉满整口会像条形码，看着像口被压扁了 -->
          <line
            v-for="pin in 8"
            :key="'mpin-' + index + '-' + pin"
            class="bp-pin"
            x1="24"
            :y1="mgmtRowY(index) + 29"
            x2="24"
            :y2="mgmtRowY(index) + 36"
            :transform="'translate(' + (pin - 1) * 2.8 + ' 0)'"
          />
          <!-- ETH 的两个链路灯（绿=Link、黄=Act）：像真 RJ45 一样嵌在口内下沿，不占板面空间 -->
          <template v-if="row.leds">
            <circle class="bp-mgmt-link" cx="27" :cy="mgmtRowY(index) + 45" r="1.8" />
            <circle class="bp-mgmt-act" cx="39" :cy="mgmtRowY(index) + 45" r="1.8" />
          </template>
          <!-- 竖排丝印 CON / ETH / ESC，和真板卡一样贴在口的右边 -->
          <text
            class="bp-mgmt-name"
            x="52"
            :y="mgmtRowY(index) + 38"
            :transform="'rotate(-90 52 ' + (mgmtRowY(index) + 38) + ')'"
            text-anchor="middle"
          >{{ row.name }}</text>

          <!-- 标注列：管理口没有 ONT，只写清楚是什么口（插进机框时不画，槽位放不下） -->
          <template v-if="!compact">
            <line class="bp-lead" x1="67" :y1="mgmtRowY(index) + 38" x2="75" :y2="mgmtRowY(index) + 38" />
            <line class="bp-lead" x1="75" :y1="mgmtRowY(index) + 34" x2="75" :y2="mgmtRowY(index) + 42" />
            <text class="bp-anno-name bp-anno-mgmt" x="81" :y="mgmtRowY(index) + 34">{{ row.name }}</text>
            <text class="bp-anno-sub" x="81" :y="mgmtRowY(index) + 52">{{ row.desc }}</text>
          </template>
        </g>
      </g>

      <!-- 运行灯带：和端口本体分开渲染，单独排成两列（0/2/4/6 在左列、1/3/5/7 在右列），像真板卡的 PON 灯区 -->
      <g v-if="ports.length" class="bp-lamp-strip">
        <text class="bp-strip-title" x="20" :y="portTop + 8">{{ stripTitle }}</text>
        <g v-for="(port, index) in ports" :key="'lamp-' + port.no">
          <title>{{ tooltip(port) }}</title>
          <text
            class="bp-strip-no"
            :x="index % 2 === 0 ? 24 : 43"
            :y="ledCellY(index) + 9"
            :text-anchor="index % 2 === 0 ? 'end' : 'start'"
          >{{ portNo(port) }}</text>
          <circle
            v-if="stateOf(port) !== 'offline'"
            class="bp-led-halo"
            :class="'bp-led-halo-' + stateOf(port)"
            :cx="index % 2 === 0 ? 29 : 38"
            :cy="ledCellY(index) + 6.5"
            r="4.6"
          />
          <circle
            class="bp-led-bezel"
            :cx="index % 2 === 0 ? 29 : 38"
            :cy="ledCellY(index) + 6.5"
            r="3"
          />
          <circle
            class="bp-led"
            :class="'bp-led-' + stateOf(port)"
            :cx="index % 2 === 0 ? 29 : 38"
            :cy="ledCellY(index) + 6.5"
            r="2"
          />
        </g>
      </g>

      <!-- 端口：纵向一列排下来（和真板卡一样，8 口就是 0..7 一列），灯不在口上，在上面的灯带里 -->
      <g v-if="ports.length" class="bp-ports">
        <g
          v-for="(port, index) in ports"
          :key="'port-' + port.no"
          class="bp-cell"
          :class="{
            'bp-cell-active': isActive(port.no),
            'bp-cell-open': isOpen(port.no),
            'bp-cell-clickable': port.onts.length > 0
          }"
          @click="$emit('toggle', { slotNo: board.no, portNo: port.no, count: port.onts.length })"
          @mouseenter="$emit('hover', board.no, port.no)"
          @mouseleave="$emit('leave')"
        >
          <title>{{ tooltip(port) }}</title>

          <!-- 行底纹：一行一个口，鼠标移到哪个口亮哪个 -->
          <rect class="bp-row-bg" x="10" :y="rowY(index) + 3" width="48" height="56" rx="2.5" />

          <!-- 口序号丝印（真板卡上每个口左边都印着 0/1/2…） -->
          <text class="bp-port-no" x="17" :y="rowY(index) + 34" text-anchor="end">{{ portNo(port) }}</text>

          <!-- 端口本体：PON/10GE 画 SFP 光口笼，GE 画 RJ45 电口 -->
          <g v-if="panel === 'ge'" class="bp-socket">
            <rect
              class="bp-socket-body"
              x="20"
              :y="rowY(index) + 17"
              width="28"
              height="28"
              rx="1"
              :fill="'url(#' + ids.socket + ')'"
            />
            <rect class="bp-socket-hole" x="22" :y="rowY(index) + 20" width="24" height="22" rx="0.6" />
            <line
              v-for="pin in 8"
              :key="'pin-' + index + '-' + pin"
              class="bp-pin"
              x1="24"
              :y1="rowY(index) + 23"
              :x2="24"
              :y2="rowY(index) + 30"
              :transform="'translate(' + (pin - 1) * 2.9 + ' 0)'"
            />
          </g>
          <g v-else class="bp-socket">
            <rect
              class="bp-socket-body"
              x="20"
              :y="rowY(index) + 17"
              width="28"
              height="28"
              rx="1"
              :fill="'url(#' + ids.socket + ')'"
            />
            <rect class="bp-socket-hole" x="22" :y="rowY(index) + 20" width="24" height="22" rx="0.6" />
            <circle class="bp-fiber" cx="30" :cy="rowY(index) + 31" r="2.8" />
            <circle class="bp-fiber" cx="38" :cy="rowY(index) + 31" r="2.8" />
            <!-- 光口下方的卡扣，像真的 SFP 笼 -->
            <rect class="bp-socket-latch" x="28" :y="rowY(index) + 46" width="12" height="3" rx="0.8" />
          </g>
        </g>
      </g>

      <!-- 整板汇总：钉在板卡底部的金属面上（原来挂在标注列下面，像浮在外面） -->
      <g v-if="onlineTotal > 0" class="bp-total">
        <rect x="10" :y="totalY" width="48" height="14" rx="7" />
        <circle class="bp-total-dot" cx="16" :cy="totalY + 7" r="2" />
        <text class="bp-total-text" x="36" :y="totalY + 10.4" text-anchor="middle">{{ onlineTotal }} ONT 在线</text>
      </g>

      <!-- 底部丝印：机框型号 + 激光等级贴（窄板只放得下字，三角形省了） -->
      <text v-if="chassisModel" class="bp-frame" x="14" :y="height - 16">{{ chassisModel }}</text>
      <text class="bp-laser-text" x="58" :y="height - 16" text-anchor="end">CLASS 1</text>
    </svg>
  </div>
</template>

<script>
/**
 * 板卡面板（SVG，竖放 + 右侧标注）：复刻 MA5683T 机框业务板的外观，深色运维风格。
 *
 * 左边是竖着插在机框导轨里的窄条板卡：金属灰底板 + 拉丝 + 螺丝 + 助拔器 + 型号/槽位丝印 +
 * RUN/ALM/ACT/ENV 板卡灯；业务区**上下分两块**（和真板卡一样）：
 *   1) 上面是运行灯带：`PON` 丝印 + 8 颗灯分两列排（0/2/4/6 左列、1/3/5/7 右列），
 *      每颗灯有自己的灯座和灯芯（绿闪=在线、红常亮=告警、灰=离线）；
 *   2) 下面是端口列：一列竖排，每行一个 SFP 口笼 / RJ45，左边印口序号；
 * 灯单独渲染，不挂在端口上——点端口、看 tooltip 都在下面对应的口上；
 * 主控板（H802SCUN 这类）在灯下面还有管理区：RESET 复位孔 + CON 控制台 + ETH 管理网口 + ESC，
 * 口名用竖排丝印贴在口的右边，和真板卡一样。
 *
 * 口名、接口号、ONT 数、光功率这些文字放不进 55 宽的板卡，改成鼠标悬停的 `<title>`。
 * 板卡下方还有一枚「N ONT 在线」的整板汇总。
 *
 * 所有板卡等高（机框槽位高度），端口少的板下面就是空白金属面板。
 * 纯展示：端口数据由外面折算好传进来（board.ports），交互冒泡给页面处理。
 */
import { boardLampState, portLampState } from '../utils'
import { fetchOltOptions } from '@/api/olt'

// 板卡宽度 55、机框（槽位）高度 700：板卡就是插进机框的一条窄板，比例按真机框来
const PANEL_W = 237
const PLATE_W = 55
// 业务区从 76 开始：先是运行灯带（PON 灯区），灯带下面才是端口列
const PORT_TOP = 76
// 灯带上方「PON」丝印那一截 / 灯带一行的高（灯两列，8 口就是 4 行）
const LED_HEAD = 18
const LED_PITCH = 13
// 端口一行的高（一列竖排，8 口 = 496）
const PORT_ROW_H = 62
// 主控板的管理区（RESET + CON + ETH + ESC）：从板卡灯下面开始，每行 76 高
const MGMT_TOP = 118
const MGMT_H = 76
const TOTAL_H = 24
const BOTTOM_H = 24
// 机框槽位高度：真机框里每块板都一样高（8 口业务板满口时的高度），
// 2/4 口板和主控板画不满的那一截就是空白金属面板，不再各自拉长拉短
const SLOT_H = 652

export default {
  name: 'OltBoardPanel',
  props: {
    /** 板卡数据（mixin 里 slots 的一项）：型号、槽位、端口、状态、温度 */
    board: {
      type: Object,
      required: true
    },

    /** 面板样式：pon（PON 业务板）/ ge（主控、以太网上行）/ xge（10GE 上联）/ generic */
    panel: {
      type: String,
      default: 'generic'
    },
    /** 当前鼠标悬停的端口，形如「槽位:端口」，由父组件持有 */
    hoverPort: {
      type: String,
      default: ''
    },
    /** 已展开看 ONT 的端口，key 同 hoverPort */
    expandedOnts: {
      type: Object,
      default: () => ({})
    },
    /**
     * 机框模式：只画板卡本体（55 宽），省掉左侧导轨和右侧逐口标注列，
     * 这样一块板正好塞进 60 宽的机框槽位里。
     */
    compact: {
      type: Boolean,
      default: false
    },
    /** 机框型号（设备型号），印在板卡底部丝印上；没传就不印机框型号 */
    chassisModel: {
      type: String,
      default: ''
    }
  },
  data() {
    return {
      /** 温度告警阈值等，来自 GET /api/olt/options */
      options: null
    }
  },
  computed: {
    ports() {
      return this.board.ports || []
    },
    width() {
      return PANEL_W
    },
    plateW() {
      return PLATE_W
    },
    /** 主控板才有管理区：CON 控制台、ETH 管理网口、ESC 扩展口（真板卡丝印就是这三个） */
    mgmtRows() {
      if (!this.board.controlBoard) return []
      return [
        { name: 'CON', desc: '控制台串口' },
        { name: 'ETH', desc: '管理网口', leds: true },
        { name: 'ESC', desc: '级联 / 扩展口' }
      ]
    },
    /** 管理区从哪开始：板卡灯下面留出 RESET 的位置 */
    mgmtTop() {
      return MGMT_TOP
    },
    /** 业务端口从哪一行开始：有管理区就整体往下让 */
    portTop() {
      return this.mgmtRows.length ? MGMT_TOP + this.mgmtRows.length * MGMT_H : PORT_TOP
    },
    /** 整板在线 ONT 合计：板卡下方「N ONT 在线」用 */
    onlineTotal() {
      return this.ports.reduce((sum, port) => sum + (port.onuOnline || 0), 0)
    },
    /** 灯带下面才是端口列：灯两列排，8 个口就是 4 行灯 */
    portsTop() {
      return this.portTop + LED_HEAD + Math.ceil(this.ports.length / 2) * LED_PITCH + 8
    },
    /** 灯区丝印标题：PON 板写 PON，以太口板写 GE / 10GE */
    stripTitle() {
      if (this.panel === 'pon') return 'PON'
      if (this.panel === 'ge') return 'GE'
      if (this.panel === 'xge') return '10GE'
      return 'PORT'
    },
    /** 画完端口的那一刀：端口少就停在槽位高度（剩下的面板是空的），端口多才往下长 */
    slotEnd() {
      return Math.max(this.portsTop + this.ports.length * PORT_ROW_H, SLOT_H)
    },
    /** 竖放、等高：不管 2 口、4 口、8 口还是主控板，板卡都是同一个槽位高度 */
    height() {
      return this.slotEnd + TOTAL_H + BOTTOM_H
    },
    viewBox() {
      // 机框模式：视口裁到只放得下板卡本体（板卡 6..61 居中在 60 宽的视口里，两边各 3.5）
      return this.compact ? '3.5 0 60 ' + this.height : '0 0 ' + this.width + ' ' + this.height
    },
    /** 整板汇总徽标的位置：统一钉在槽位底下一行，各块板对齐 */
    totalY() {
      return this.slotEnd + 4
    },
    /** 多个面板同页时 SVG 的渐变 id 不能重名，按实例编号区分 */
    ids() {
      const uid = 'bp' + this._uid
      return {
        plate: uid + '-plate',
        socket: uid + '-socket',
        screw: uid + '-screw',
        brush: uid + '-brush',
        rail: uid + '-rail'
      }
    },
    modelText() {
      return this.board.type || '未知板卡'
    },
    slotText() {
      return this.board.no == null ? '-' : this.board.no
    },
    /** 丝印副标题：口类型 + 口数（窄板只有 76 宽，写太长会压到槽位字） */
    subtitle() {
      const mode = this.board.portMode || ''
      const text = [mode, this.ports.length ? this.ports.length + '口' : '', this.board.controlBoard ? '主控' : '']
        .filter(Boolean)
        .join(' ')
      return text.length > 11 ? text.slice(0, 10) + '…' : text
    },
    runState() {
      return boardLampState(this.board.statusType)
    },
    /** ALM：板卡状态异常常亮红 */
    alarmState() {
      const type = this.board.statusType
      return type === 'danger' || type === 'warning' ? 'alarm' : 'offline'
    },
    /** ACT：板卡下有在线 ONT 就闪 */
    actState() {
      return this.onlineTotal > 0 ? 'online' : 'offline'
    },
    /** ENV：温度过高告警，没采到温度就灰着 */
    envState() {
      const temp = this.board.temperature
      if (temp == null) return 'offline'
      // 告警阈值由后端给，页面不自己定「多少度算高」
      const limit = this.options ? this.options.envTempAlarmC : null
      if (limit == null) return 'offline'
      return temp >= limit ? 'alarm' : 'offline'
    },
    /** 板卡右边缘（导轨就贴在板卡右边） */
    plateRight() {
      return 6 + this.plateW
    },
    screws() {
      const h = this.height
      return [
        { x: 11, y: 14 },
        { x: 56, y: 14 },
        { x: 11, y: h - 12 },
        { x: 56, y: h - 12 }
      ]
    },
    ariaLabel() {
      return this.modelText + ' 槽位 ' + this.slotText + ' 面板示意'
    }
  },
  created() {
    fetchOltOptions()
      .then(res => {
        this.options = res.data
      })
      .catch(() => {
        this.options = null
      })
  },
  methods: {
    /** 端口一列竖排：第 index 个口的行顶 y */
    rowY(index) {
      return this.portsTop + index * PORT_ROW_H
    },
    /** 灯带里第 index 颗灯的格子顶 y（两列 → 每两颗一行） */
    ledCellY(index) {
      return this.portTop + LED_HEAD + Math.floor(index / 2) * LED_PITCH
    },
    /** 管理区第 index 个口（CON / ETH / ESC）的行首 y */
    mgmtRowY(index) {
      return this.mgmtTop + index * MGMT_H
    },
    key(portNo) {
      return this.board.no + ':' + portNo
    },
    isActive(portNo) {
      return this.hoverPort === this.key(portNo)
    },
    isOpen(portNo) {
      return !!this.expandedOnts[this.key(portNo)]
    },
    /** 端口灯：告警优先，其次在线（有在线 ONT 或链路在线），都没有算离线 */
    stateOf(port) {
      return portLampState(port)
    },
    portNo(port) {
      return port.no == null ? '-' : port.no
    },
    portLabel(port) {
      const no = this.portNo(port)
      if (this.panel === 'pon') {
        return 'PON ' + no
      }
      const mode = (this.board.portMode || '').toUpperCase()
      const prefix = mode.indexOf('10GE') >= 0 || mode.indexOf('XGE') >= 0 ? '10GE' : mode || 'PORT'
      return prefix + ' ' + no
    },
    /** 标注第二行：PON 口看 ONT 总数/在线数，上行口看链路状态 */
    onuText(port) {
      if (this.panel !== 'pon') {
        return '链路 ' + (port.linkText || '未采集')
      }
      if (!port.onuText) {
        return 'ONT 未采集'
      }
      if (!port.onuTotal) {
        return '无 ONT'
      }
      return (port.onuOnline || 0) + ' / ' + port.onuTotal + ' ONT 在线'
    },
    /** 标注第三行：接口号 + 上行口放通的 VLAN */
    subText(port) {
      const parts = []
      if (port.iface) {
        parts.push(port.iface)
      }
      if (port.vlanText) {
        parts.push('VLAN ' + port.vlanText)
      }
      return parts.join(' · ') || '—'
    },
    /** 光模块数值一行：发光 / 收光 / 温度 / 偏置 / 电压（display port state 采的，没采到就写未采集） */
    opticText(port) {
      const parts = []
      if (port.txPower) parts.push('发光 ' + port.txPower)
      if (port.rxPower) parts.push('收光 ' + port.rxPower)
      if (port.opticTemperature) parts.push('温度 ' + port.opticTemperature)
      if (port.biasCurrent) parts.push('偏置 ' + port.biasCurrent)
      if (port.supplyVoltage) parts.push('电压 ' + port.supplyVoltage)
      if (parts.length) {
        return parts.join(' · ')
      }
      return port.hasOptic ? '光模块数值未采集' : '光模块未采集'
    },
    tooltip(port) {
      const stateText = { online: '在线', alarm: '告警', offline: '离线' }[this.stateOf(port)]
      return [
        this.portLabel(port),
        port.iface || '',
        stateText,
        port.onuText || 'ONT 未采集',
        port.vlanText ? 'VLAN ' + port.vlanText : '',
        this.opticText(port)
      ]
        .filter(Boolean)
        .join(' · ')
    }
  }
}
</script>

<style lang="scss" scoped>
@import '~@/styles/olt.scss';

.board-panel {
  margin-top: 6px;
}
/* 插进机框时不留边距：板卡要和槽位上下顶齐，不能出现缝隙 */
.board-panel-compact {
  margin-top: 0;
  line-height: 0;
}
.board-panel-svg {
  display: block;
  width: 100%;
  height: auto;
}
/* 机框导轨：板卡竖着插进去，两边比板卡更深 */
.bp-rail {
  fill: #0f1216;
  stroke: #262b33;
  stroke-width: 1;
}
.bp-plate {
  stroke: #4d545e;
  stroke-width: 1;
}
.bp-plate-brush {
  pointer-events: none;
}
.bp-plate-edge {
  fill: #6a7381;
  opacity: 0.5;
}
.bp-plate-shadow {
  fill: #0d0f13;
  opacity: 0.6;
}
/* 丝印文字：白灰色的板卡印刷字 */
.bp-model,
.bp-slot,
.bp-status,
.bp-subtitle,
.bp-frame,
.bp-port-no,
.bp-anno-name,
.bp-anno-rx,
.bp-anno-onu,
.bp-anno-sub,
.bp-empty,
.bp-head-status,
.bp-head-sub,
.bp-total-text,
.bp-laser-text,
.bp-board-led-label,
.bp-mgmt-reset,
.bp-mgmt-name {
  font-family: Consolas, Monaco, 'Microsoft YaHei', sans-serif;
}
/* 板卡窄（55 宽），板上的丝印字号跟着收，不然型号会顶到槽位丝印上 */
.bp-model {
  fill: #e8edf5;
  font-size: 6.6px;
  font-weight: 700;
  letter-spacing: 0.3px;
}
.bp-slot {
  fill: #98a2b0;
  font-size: 5.8px;
  letter-spacing: 0.3px;
}
.bp-subtitle {
  fill: #8d97a4;
  font-size: 5.6px;
}
.bp-frame {
  fill: #6f7883;
  font-size: 5.2px;
  letter-spacing: 0.8px;
}
.bp-laser-text {
  fill: #b9a373;
  font-size: 5px;
  letter-spacing: 0.4px;
}
.bp-board-led-label {
  fill: #8b95a3;
  font-size: 5.2px;
  letter-spacing: 0.2px;
}
/* ---- 端口本体 ---- */
.bp-row-bg {
  fill: #22262d;
  opacity: 0.55;
}
.bp-row-line {
  stroke: #0d0f13;
  stroke-opacity: 0.5;
  stroke-width: 1;
}
.bp-cell {
  cursor: default;
}
.bp-cell-clickable {
  cursor: pointer;
}
.bp-cell:hover .bp-row-bg {
  fill: #1b2634;
  opacity: 1;
}
.bp-cell-active .bp-row-bg {
  fill: #1b2634;
  opacity: 1;
  stroke: #4a9eff;
  stroke-width: 1;
}
.bp-cell-open .bp-row-bg {
  fill: #16202c;
  opacity: 1;
  stroke: #4a9eff;
  stroke-width: 1;
  stroke-dasharray: 4 3;
}
.bp-port-no {
  fill: #98a2b0;
  font-size: 6.4px;
}
/* ---- 运行灯带：PON 丝印 + 两列灯各自的口序号（灯和端口分开画） ---- */
.bp-strip-title {
  fill: #c0c8d2;
  font-size: 6.5px;
  font-weight: 600;
  letter-spacing: 1.2px;
}
.bp-strip-no {
  fill: #dbe3ee;
  font-size: 7.2px;
  font-weight: 700;
}
/* ---- 主控板管理区：RESET 按钮 + CON / ETH / ESC 竖排丝印 ---- */
.bp-reset-ring {
  fill: #1b1f26;
  stroke: #767f8b;
  stroke-width: 1;
}
.bp-reset-dot {
  fill: #767f8b;
}
.bp-mgmt-reset {
  fill: #c0c8d2;
  font-size: 6px;
  letter-spacing: 0.5px;
}
.bp-mgmt-name {
  fill: #c0c8d2;
  font-size: 6.5px;
  font-weight: 600;
  letter-spacing: 0.6px;
}
.bp-mgmt-link {
  fill: #35d07f;
  stroke: #14171c;
  stroke-width: 0.7;
}
.bp-mgmt-act {
  fill: #f0b34a;
  stroke: #14171c;
  stroke-width: 0.7;
}
.bp-anno-mgmt {
  fill: #a9b6c7;
}
.bp-socket-body {
  stroke: #767f8b;
  stroke-width: 0.9;
}
.bp-socket-hole {
  fill: #101318;
  stroke: #5a626d;
  stroke-width: 0.7;
}
.bp-fiber {
  fill: #05070a;
  stroke: #767f8b;
  stroke-width: 0.7;
}
.bp-socket-latch {
  fill: #2c323a;
}
.bp-pin {
  stroke: #7f8894;
  stroke-width: 0.7;
}
/* 运行灯：单独一颗，灯座 + 灯芯，离线也是一颗看得见的灯 */
.bp-led-bezel {
  fill: #12161b;
  stroke: #2b323b;
  stroke-width: 0.7;
}
.bp-led {
  stroke: #14171c;
  stroke-width: 0.8;
}
.bp-led-online {
  fill: #35d07f;
  animation: bp-blink 1.3s ease-in-out infinite;
}
.bp-led-alarm {
  fill: #ff4d4f;
}
.bp-led-offline {
  fill: #565c66;
}
.bp-led-halo {
  stroke: none;
  animation: bp-blink 1.3s ease-in-out infinite;
}
.bp-led-halo-online {
  fill: #35d07f;
  opacity: 0.16;
}
.bp-led-halo-alarm {
  fill: #ff4d4f;
  opacity: 0.2;
  animation: none;
}
@keyframes bp-blink {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.3;
  }
}
/* ---- 右侧标注列：引出线 + 每口明细 ---- */
.bp-lead {
  stroke: #3d4653;
  stroke-width: 1;
}
.bp-anno-name {
  fill: #8fb8ff;
  font-size: 9px;
  font-weight: 600;
  letter-spacing: 0.2px;
}
.bp-anno-rx {
  fill: #9aa5b3;
  font-size: 8px;
}
.bp-anno-onu {
  font-size: 8.2px;
}
.bp-anno-online {
  fill: #55d187;
}
.bp-anno-alarm {
  fill: #ff7875;
}
.bp-anno-offline {
  fill: #7f8a99;
}
.bp-anno-sub {
  fill: #6f7b8a;
  font-size: 7.4px;
}
.bp-empty {
  fill: #6f7883;
  font-size: 8.5px;
}
/* 整板汇总徽标 */
.bp-total rect {
  fill: #16202c;
  stroke: #2b3a48;
  stroke-width: 1;
}
.bp-total-dot {
  fill: none;
  stroke: #55d187;
  stroke-width: 1.2;
}
.bp-total-text {
  fill: #9fb0c6;
  font-size: 6.6px;
}
/* 标注列表头：运行状态 + 温度 */
.bp-head-status {
  font-size: 9px;
  font-weight: 600;
  fill: #98a2b0;
}
.bp-status-success {
  fill: #4ad07f;
}
.bp-status-danger {
  fill: #ff6b6b;
}
.bp-status-warning {
  fill: #f0b34a;
}
.bp-status-info {
  fill: #98a2b0;
}
.bp-head-sub {
  fill: #7f8a99;
  font-size: 7.5px;
}
.bp-head-line {
  stroke: #2a303a;
  stroke-width: 1;
}
</style>
