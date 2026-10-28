import {
  fetchOltDetail,
  fetchOltRecords,
  testOltById,
  quickSyncOlt,
  refreshOlt,
  createDbaProfile,
  deleteDbaProfiles,
  createOltVlan,
  deleteOltVlans,
  fetchOltActions,
  quickSetupOlt,
  execOltAction,
  fetchOltOptions
} from '@/api/olt'
import { compressVlans as compressVlanRange } from '../utils'

/**
 * OLT 详情页公共逻辑：取台账、板卡、VLAN、采集记录，翻成页面上要显示的样子。
 *
 * 华为（huawei.vue）与第三方（generic.vue，鸿升光/中兴/烽火都走它）共用这一份：
 * 数据怎么拉、板卡和端口怎么折算、状态怎么翻中文，都在这里。
 * 各页只负责按自家的面板结构摆，新增一个厂商时复制一份视图组件即可。
 *
 * 依赖宿主页面提供一个 detailId（通常取路由上的 id），来源由页面自己决定。
 */
export const oltDetailMixin = {
  data() {
    return {
      loading: false,
      syncing: false,
      testing: false,
      detail: { device: null, boards: [], records: [], vlan: null, template: null },
      // 页面自动刷新：后台每 5 分钟自己去采，这里只按间隔把最新数据拉到页面上，不碰设备
      autoRefresh: true,
      pollSeconds: 10,
      pollTimer: null,
      // 实时数据：打开页面会直连 OLT 采最新，库里那份只用来秒开和连不上时兜底
      liveAt: null,
      liveError: '',
      refreshing: false,
      // 后端正在后台补采慢数据（端口状态 / ONT / VLAN / 模板），板卡已经先出来了
      deepSyncing: false,
      // 展开看 ONT 明细的端口，key 是「槽位:端口」
      expandedOnts: {},
      // 鼠标停在端口行/示意图标注上时，两边一起高亮
      hoverPort: '',
      // 「设备配置」页签的命令动作目录（按厂商 profile 给，页面只负责渲染）
      commandActions: [],
      // 码表与阈值：厂商、板卡档案、视图名、ONU 动作、VLAN 类型、板卡类别、槽位兜底、温度阈值
      options: null
    }
  },
  computed: {
    device() {
      return this.detail.device || {}
    },
    /** 鸿升光整机（EPON 与 GPON 都是）：板卡区不用机框插槽，改画横向整机面板 */
    isHsgq() {
      const vendor = this.device.vendor
      return vendor === 'HSGQ' || vendor === 'HSGQ_GPON'
    },
    boards() {
      return this.detail.boards || []
    },
    records() {
      return this.detail.records || []
    },
    hasDevice() {
      return !!this.detail.device
    },
    slots() {
      return this.boards.map(board => {
        // 板卡型号能对上档案时，用档案里的面板样式和端口数兜底
        // 面板样式、端口数、状态中文与等级：都是服务端按型号档案算好后随板卡来的
        const type = board.boardType || '-'
        const ports = this.buildPorts(board)
        return {
          no: board.slotNo,
          type,
          portMode: board.portMode || '',
          portCount: board.portCount,
          status: board.status,
          statusText: board.statusText || board.status || '-',
          statusType: board.statusLevel || 'info',
          statusIcon: this.statusIconOf(board.statusLevel),
          description: board.description || '',
          temperature: board.temperature,
          panel: board.panelKind || 'generic',
          controlBoard: !!board.controlBoard,
          // 整机（鸿升光）才有：前面板有几个 PON / 上联口 / NMS，由型号决定
          frontPanel: board.frontPanel || null,
          ports,
          onuSummary: this.buildOnuSummary(board)
        }
      })
    },
    /**
     * 机框要画多少个槽位：优先用 display board 0 槽位表里的最大 SlotID + 1
     * （空槽位也在表里，所以 0..12 就是 13 个槽位），没采到就按板卡最大槽位兜底。
     */
    chassisSlotCount() {
      const count = this.device.slotCount
      if (count && count > 0) {
        return count
      }
      const max = this.boards.reduce((value, board) => {
        return board.slotNo == null ? value : Math.max(value, board.slotNo)
      }, -1)
      // 兜底的槽位数由后端给（设备没回槽位数时按这个画）
      const fallback = this.options ? this.options.defaultSlotCount : 0
      return Math.max(fallback, max + 1)
    },
    /** 「ONT 配置/状态」页只列有端口的板卡（业务板），主控、上联板没有 ONT */
    ontBoards() {
      return this.slots.filter(slot => slot.ports.length)
    },
    ontPortTotal() {
      return this.ontBoards.reduce((sum, slot) => sum + slot.ports.length, 0)
    },
    /** 全局 ONT 汇总：各板各口加起来，和每行「23 ONT / 22 在线」能对上 */
    ontSummary() {
      let total = 0
      let online = 0
      this.ontBoards.forEach(slot => {
        slot.ports.forEach(port => {
          total += port.onuTotal || 0
          online += port.onuOnline || 0
        })
      })
      return { total, online, offline: Math.max(0, total - online) }
    },
    vlanInfo() {
      return this.detail.vlan || null
    },
    vlanList() {
      return (this.vlanInfo && this.vlanInfo.vlans) || []
    },
    portVlans() {
      return (this.vlanInfo && this.vlanInfo.portVlans) || []
    },
    /**
     * 能做 VLAN 成员的端口（「创建 VLAN」弹窗的复选框）。
     *
     * <p>采集回来的清单里本该已含 PON 口，但早期快照只有上联电口（show port state all 不列 PON），
     * 这里用板卡的 PON 口补一次，免得没重新同步时弹窗里少一排 PON。GPON 的 PON 口不做二层成员，不补。</p>
     */
    memberPorts() {
      const collected = (this.vlanInfo && this.vlanInfo.memberPorts) || []
      if (collected.some(port => /^(epon|gpon)$/i.test(port.type || ''))) {
        return collected
      }
      const ponType = this.boards.some(board => (board.portMode || '').toUpperCase() === 'EPON')
        ? 'epon'
        : null
      if (!ponType) {
        return collected
      }
      const ponPorts = []
      this.boards
        .filter(board => (board.portMode || '').toUpperCase() === 'EPON')
        .forEach(board => {
          (board.ports || []).forEach(port => {
            const no = port.portNo
            ponPorts.push({
              type: ponType,
              no,
              label: port.portName || 'PON' + String(no).padStart(2, '0')
            })
          })
        })
      return ponPorts.length ? [...ponPorts, ...collected] : collected
    },
    vlanIpInterfaces() {
      return (this.vlanInfo && this.vlanInfo.ipInterfaces) || []
    },
    staticRoutes() {
      return (this.vlanInfo && this.vlanInfo.staticRoutes) || []
    },
    /** DBA 模板 + 线路模板快照（display dba-profile all / display ont-lineprofile gpon all） */
    templateInfo() {
      return this.detail.template || null
    },
    dbaProfiles() {
      return (this.templateInfo && this.templateInfo.dbaProfiles) || []
    },
    lineProfiles() {
      return (this.templateInfo && this.templateInfo.lineProfiles) || []
    },
    /** ONT 能力模板：display ont-srvprofile gpon all（含 TR069 管理参数） */
    srvProfiles() {
      return (this.templateInfo && this.templateInfo.srvProfiles) || []
    },
    /** TR069 服务器模板：display ont tr069-server-profile all（ACS 地址与账号） */
    tr069Profiles() {
      return (this.templateInfo && this.templateInfo.tr069Profiles) || []
    },
    /** 流量限速模板：display traffic table ip all（CIR 承诺 / PIR 峰值） */
    trafficTables() {
      return (this.templateInfo && this.templateInfo.trafficTables) || []
    },
    /**
     * DBA 模板 ID → 这一档带宽的说明，线路模板的 T-CONT 顺着 ID 查过来就能显示带宽档位。
     * 查不到（模板没采到或 ID 对不上）显示「DBA N（未采到）」，不猜。
     */
    dbaMap() {
      const map = {}
      this.dbaProfiles.forEach(profile => {
        map[profile.profileId] = profile
      })
      return map
    },
    /** 按 VLAN 类型分组，和网管上「单层智能 VLAN：41、45-46」的写法一致 */
    vlanTypeGroups() {
      const labels = this.options ? this.options.vlanTypeLabels : {}
      const groups = {}
      this.vlanList.forEach(vlan => {
        const type = (vlan.type || 'other').toLowerCase()
        if (!groups[type]) {
          groups[type] = { ids: [], std: 0, serv: 0 }
        }
        groups[type].ids.push(vlan.vlanId)
        groups[type].std += vlan.stdPortNum || 0
        groups[type].serv += vlan.servPortNum || 0
      })
      return Object.keys(groups).map(type => ({
        type,
        label: labels[type] || type,
        text: this.compressVlans(groups[type].ids),
        std: groups[type].std,
        serv: groups[type].serv
      }))
    },
    /** VLAN 类型分组，再加最后一排「二层互通 VLAN」（VLAN-Con 不为空的那些） */
    vlanGroupRows() {
      const rows = this.vlanTypeGroups.slice()
      const connectIds = this.vlanList
        .filter(vlan => vlan.vlanConNum != null)
        .map(vlan => vlan.vlanId)
      const labels = this.options ? this.options.vlanTypeLabels : {}
      rows.push({
        type: 'connect',
        label: labels.connect || '',
        text: connectIds.length ? this.compressVlans(connectIds) : '无',
        std: null,
        serv: null
      })
      return rows
    },
    /** 固件版本按「版本 / 补丁」拼，和现场 OLT 的写法一致 */
    firmwareText() {
      const parts = [this.device.softwareVersion, this.device.patchVersion].filter(item => !!item)
      return parts.length ? parts.join(' / ') : '-'
    },
    /** 板卡类别统计，形如：共 7 块板卡（业务板 2 / 主控板 2 / 上联板 2 / 电源板 1） */
    boardSummary() {
      if (this.boards.length === 0) return ''
      // 整机（鸿升光）没有板卡可言，直接报型号规格：8 × GPON + 4 × GE + 4 × XGE
      const whole = this.boards.find(board => board.frontPanel)
      if (whole) {
        return whole.frontPanel.summary || whole.description || ''
      }
      const labels = this.options ? this.options.boardCategoryLabels : {}
      const counts = {}
      this.boards.forEach(board => {
        const kind = board.boardKind || 'UNKNOWN'
        counts[kind] = (counts[kind] || 0) + 1
      })
      const detail = Object.keys(counts).map(kind => (labels[kind] || kind) + ' ' + counts[kind]).join(' / ')
      return '共 ' + this.boards.length + ' 块板卡（' + detail + '）'
    },
    statusText() {
      const map = { ONLINE: '在线', OFFLINE: '离线', UNKNOWN: '未探测' }
      return map[this.device.status] || '未探测'
    },
    statusTag() {
      if (this.device.status === 'ONLINE') return 'success'
      if (this.device.status === 'OFFLINE') return 'danger'
      return 'info'
    },
    webAccountText() {
      const accounts = this.device.webAccounts || []
      return accounts.length === 0 ? '不限制' : accounts.join('、')
    },
    /**
     * 页面上这行「数据是哪来的」：OLT 的数据以设备为准，能连上就显示实时采到的，
     * 连不上才退回库里最后一次的快照，并把时间标出来，免得把旧数据当现在的看。
     */
    liveText() {
      if (this.refreshing) {
        return '正在直连 OLT 采集最新数据…'
      }
      if (this.liveError) {
        const fallback = this.device.lastSyncAt ? '（' + this.device.lastSyncAt + '）' : ''
        return '实时采集失败：' + this.liveError + '，下面是库里最后一次的快照' + fallback
      }
      if (this.liveAt) {
        const base = '实时数据 · ' + this.formatClock(this.liveAt) + ' 直连 OLT 采到，已写入库'
        return this.deepSyncing ? base + '，端口 / ONT / VLAN / 模板正在后台补充采集…' : base
      }
      return this.device.lastSyncAt
        ? '库快照 ' + this.device.lastSyncAt + '，正在取实时数据…'
        : '正在直连 OLT 采集…'
    }
  },
  watch: {
    // 改了间隔或开关就重启定时器，不用刷新页面
    pollSeconds() {
      this.startPolling()
    },
    autoRefresh(value) {
      if (value) {
        this.startPolling()
      } else {
        this.stopPolling()
      }
    },
    // 后台补采开始/结束时换一下轮询间隔：补采中拉密点，补完恢复
    deepSyncing() {
      this.startPolling()
    },
    // 换设备就重新拉该厂商的命令动作目录（鸿升光才有，其它厂商是空表）
    detailId: {
      immediate: true,
      handler(id) {
        if (id) {
          this.loadActions()
        } else {
          this.commandActions = []
        }
      }
    }
  },
  created() {
    this.startPolling()
    this.loadOptions()
  },
  beforeDestroy() {
    this.stopPolling()
  },
  methods: {
    /** 码表与阈值由后端一次性给出：厂商、板卡档案、视图名、VLAN 类型、温度阈值… */
    loadOptions() {
      return fetchOltOptions()
        .then(res => {
          this.options = res.data
        })
        .catch(() => {
          this.options = null
        })
    },
    /**
     * 自动刷新：按间隔拉详情，后台定时任务采集到的数据不用等手点同步就能看到。
     * 只在页面空闲（没有正在进行的加载、同步）时拉，避免和用户操作抢。
     */
    startPolling() {
      this.stopPolling()
      if (!this.autoRefresh) return
      // 后台补采期间拉密一点（3 秒），补完恢复页面选的间隔
      const seconds = this.deepSyncing ? 3 : this.pollSeconds
      this.pollTimer = setInterval(() => {
        // 实时采集中不打断：那一次已经在连设备了，这里只是把库里的变化拉过来
        if (this.detailId && !this.syncing && !this.loading && !this.refreshing) {
          this.loadDetailSilently(this.detailId)
        }
      }, seconds * 1000)
    },
    stopPolling() {
      if (this.pollTimer) {
        clearInterval(this.pollTimer)
        this.pollTimer = null
      }
    },
    loadDetailSilently(id) {
      if (!id) return
      return fetchOltDetail(id)
        .then(res => {
          this.applyDetail(res.data)
        })
        .catch(() => {})
    },
    loadDetail(id) {
      if (!id) return Promise.resolve()
      this.loading = true
      return fetchOltDetail(id)
        .then(res => {
          // 先用库里的快照把页面撑起来（秒开），紧接着直连 OLT 采最新的覆盖上来
          this.applyDetail(res.data)
        })
        .catch(() => {})
        .finally(() => {
          this.loading = false
          this.refreshLive(id)
        })
    },
    /**
     * 实时刷新：直连 OLT 采最新并写库，成功就把页面换成最新那份。
     * 失败不抹掉已经显示的快照，只在页面上说明「现在是旧的、为什么没取到新的」。
     *
     * 分两段：先只采板卡（display board 0，一两秒）把机框和型号画出来，
     * 端口状态 / ONT 明细 / VLAN / 模板由后端后台接着采，页面轮询把补齐的数据拉过来。
     * 这样第一次打开 OLT 不用干等十几秒才看到东西。
     */
    refreshLive(id) {
      const target = id || this.detailId
      if (!target || this.refreshing) return Promise.resolve()
      this.refreshing = true
      this.liveError = ''
      return quickSyncOlt(target)
        .then(res => {
          this.applyDetail(res.data)
          this.liveAt = new Date()
        })
        .catch(err => {
          const data = (err && err.response && err.response.data) || {}
          this.liveError = data.message || (err && err.message) || '连不上设备'
        })
        .finally(() => {
          this.refreshing = false
        })
    },
    /**
     * 完整实时刷新：连设备把端口状态、ONT 明细、VLAN、模板一起采回来（十几秒）。
     * 「同步ONT状态」这类明确就是要慢数据的按钮走它，其余场景走 refreshLive 的两段式。
     */
    refreshFull(id) {
      const target = id || this.detailId
      if (!target || this.refreshing) return Promise.resolve()
      this.refreshing = true
      this.liveError = ''
      return refreshOlt(target)
        .then(res => {
          this.applyDetail(res.data)
          this.liveAt = new Date()
        })
        .catch(err => {
          const data = (err && err.response && err.response.data) || {}
          this.liveError = data.message || (err && err.message) || '连不上设备'
        })
        .finally(() => {
          this.refreshing = false
        })
    },
    /**
     * 对 OLT 做过操作之后调：重新采一次最新数据，既显示到页面也写库。
     *
     * 现在页面上还没真的下发配置（VLAN 那些编辑只是本地暂存），等下发命令接上后，
     * 在下发成功的回调里调这个方法即可——保证页面看到的和设备上真的一致。
     */
    afterOltOperation() {
      return this.refreshLive(this.detailId)
    },
    /**
     * 下发配置的公共收尾：接口返回的就是下发后重采的最新数据，
     * 换到页面上并把来源标记为实时，成功弹个确认。
     */
    applyAfterConfig(res, label) {
      this.applyDetail(res.data)
      this.liveAt = new Date()
      this.liveError = ''
      this.$message.success(label + '成功，已重新采集最新数据')
      return res
    },
    /** 新增 DBA 模板（下发 dba-profile add），DBA 面板的弹窗调用 */
    createDba(payload) {
      return createDbaProfile(this.detailId, payload)
        .then(res => this.applyAfterConfig(res, '新增DBA模板'))
    },
    /** 删除勾选的 DBA 模板（下发 dba-profile delete） */
    deleteDba(profileIds) {
      return deleteDbaProfiles(this.detailId, profileIds)
        .then(res => this.applyAfterConfig(res, '删除DBA模板'))
    },
    /** 新增 VLAN（鸿升光下发 vlan standard），VLAN 面板的弹窗调用 */
    createVlan(payload) {
      return createOltVlan(this.detailId, payload)
        .then(res => this.applyAfterConfig(res, '新增VLAN'))
    },
    /** 删除勾选的 VLAN（下发 no vlan） */
    deleteVlan(vlanIds) {
      return deleteOltVlans(this.detailId, vlanIds)
        .then(res => this.applyAfterConfig(res, '删除VLAN'))
    },
    /** 拉「设备配置」页签的命令动作目录（按厂商 profile 给，没接入动作目录的厂商是空表） */
    loadActions() {
      if (!this.detailId) {
        this.commandActions = []
        return Promise.resolve([])
      }
      return fetchOltActions(this.detailId)
        .then(res => {
          this.commandActions = res.data || []
          return this.commandActions
        })
        .catch(() => {
          // 老厂商没有动作目录：接口报错就当没有，别把页面卡住
          this.commandActions = []
          return []
        })
    },
    /**
     * 一键开局：把弹窗选好的一揽子命令一次性下发。
     *
     * <p>POST /api/olt/{id}/quick-setup，后端按「建 VLAN → 改端口模式 → 建 TR-069 模板」
     * 的顺序编命令，返回的就是下发后重采的最新数据。</p>
     */
    handleQuickSetup(payload) {
      return quickSetupOlt(this.detailId, payload)
        .then(res => this.applyAfterConfig(res, '一键开局'))
    },
    /** 执行一条命令动作：配置类用返回的详情刷新页面，查询类把回显交给组件显示 */
    execAction(payload) {
      return execOltAction(this.detailId, payload).then(res => {
        const data = res.data || {}
        if (data.detail) {
          this.applyDetail(data.detail)
          this.liveAt = new Date()
          this.liveError = ''
          this.$message.success((data.label || '配置') + ' 已下发，已重新采集')
        }
        return data
      })
    },
    formatClock(date) {
      if (!date) return ''
      const pad = value => (value < 10 ? '0' + value : '' + value)
      return pad(date.getHours()) + ':' + pad(date.getMinutes()) + ':' + pad(date.getSeconds())
    },
    loadRecords() {
      if (!this.detailId) return
      fetchOltRecords(this.detailId, 20)
        .then(res => {
          this.detail = { ...this.detail, records: res.data || [] }
        })
        .catch(() => {})
    },
    handleTest() {
      if (!this.detailId) return
      this.testing = true
      testOltById(this.detailId)
        .then(res => {
          const data = res.data || {}
          this.$alert(data.message || (data.success ? '连接成功' : '连接失败'), data.success ? '诊断测试通过' : '诊断测试失败', {
            type: data.success ? 'success' : 'error'
          })
          this.loadDetail(this.detailId)
        })
        .catch(() => {})
        .finally(() => {
          this.testing = false
        })
    },
    handleSync(silent) {
      if (!this.detailId) return
      this.syncing = true
      // 和打开页面一样分两段：板卡先出来，慢数据后台补，同步按钮不用等到全部采完
      return quickSyncOlt(this.detailId)
        .then(res => {
          this.applyDetail(res.data)
          // 同步也是连设备采的，采到的就是实时数据
          this.liveAt = new Date()
          this.liveError = ''
          if (!silent) {
            this.$message.success('同步完成：' + ((res.data && res.data.device && res.data.device.syncSummary) || '已刷新板卡信息'))
          }
        })
        .catch(() => {})
        .finally(() => {
          this.syncing = false
        })
    },
    /** 详情接口与同步接口返回的结构一致，统一在这里落到 this.detail */
    applyDetail(data) {
      const body = data || {}
      this.detail = {
        device: body.device || null,
        boards: body.boards || [],
        records: body.records || [],
        vlan: body.vlan || null,
        template: body.template || null
      }
      // 后端后台还在补采慢数据时保持提示与 3 秒轮询，结束（false）后自动恢复
      this.deepSyncing = !!body.deepSyncing
    },
    /**
     * 端口光模块指示灯：在位亮绿灯，不在位灰灯，故障或类型不匹配黄灯。
     * 没采到状态就整段不渲染，避免一排灰灯看着像「全部不在位」。
     */
    buildPorts(board) {
      // 设备回显只列配了 ONT 的端口（P0-P2 有数据、P3-P7 空着时，回显里就没有 P3-P7），
      // 但钉行模式要求每个物理口都有一行——缺的口按端口号补占位，行里只有名字和接口号。
      // display board 0 有些版本连端口数都不带（port_count=0），端口数由后端按型号档案兜底
      const ports = this.completePorts(board, board.ports || [])
      return ports
        .map(port => {
          const status = (port.opticStatus || '').toLowerCase()
          // opticalPresent 为 null 说明这台设备没有端口光模块命令，不画灯
          const hasOptic = port.opticalPresent != null
          let lamp = 'unknown'
          let lampText = '光模块状态未采集'
          if (port.opticalPresent === true) {
            lamp = 'on'
            lampText = '光模块在位'
          } else if (port.opticalPresent === false) {
            lamp = 'off'
            lampText = '光模块不在位'
          }
          if (/abnormal|mismatch|fault/.test(status)) {
            lamp = 'warn'
            lampText = '光模块异常（' + port.opticStatus + '）'
          }
          // 链路状态来自 display board 0/<槽位> 的 Link 列，离线的口同步时也一起记进来
          const link = (port.linkStatus || '').toLowerCase()
          // 接口标识和 OLT 命令行一致：框、槽、口，例如 0/1/0
          const frameNo = port.frameNo == null ? 0 : port.frameNo
          const slotNo = port.slotNo == null ? board.slotNo : port.slotNo
          const iface = slotNo == null ? '' : frameNo + '/' + slotNo + '/' + port.portNo
          // 上行口放通了哪些 VLAN（display port vlan 的快照），PON 口一般没有
          const vlanText = this.portVlanTextOf(iface)
          // 每个 ONT 一条：页面点开端口后逐个显示，在线和不在线都列
          const onts = (port.onts || []).map(ont => ({
            id: ont.ontId,
            sn: ont.sn || '-',
            desc: ont.description || '',
            online: ont.online === true,
            stateText: ont.online === true ? '在线' : '离线',
            // 配置状态（normal / initial / failed），display ont info 的 Config 列
            configText: ont.configState || '',
            matchText: ont.matchState || '',
            // 下面这些只有部分厂商回显里带，采不到就是空，页面统一显示「-」
            name: ont.name || '',
            authorized: ont.authorized,
            registerTime: ont.registerTime || '',
            lastOfflineTime: ont.lastOfflineTime || '',
            offlineReason: ont.offlineReason || '',
            deviceType: ont.deviceType || '',
            onuType: ont.onuType || '',
            distance: ont.distance,
            rxPower: ont.rxPower || ''
          }))
          // 口的 ONT 汇总：设备汇总行缺失（老快照 / 回显没打汇总行）时按明细数兜底，
          // 否则明明有 ONT 在线的口会显示「0 ONT / 0 在线」并被画成离线
          let onuTotal = port.onuTotal == null ? 0 : port.onuTotal
          let onuOnline = port.onuOnline == null ? 0 : port.onuOnline
          if (!onuTotal && onts.length) {
            onuTotal = onts.length
            onuOnline = onts.filter(ont => ont.online).length
          }
          const onuText = port.onuTotal != null || onuTotal > 0
            ? onuTotal + ' ONT / ' + onuOnline + ' 在线'
            : ''
          return {
            no: port.portNo,
            name: port.portName || ('P' + port.portNo),
            iface,
            onts,
            onuTotal,
            onuOnline,
            offline: Math.max(0, onuTotal - onuOnline),
            // 光模块数值：发光/温度/偏置/电压来自接口视图的 display port state，收光来自 display board
            rxPower: port.rxPower,
            txPower: port.txPower,
            opticTemperature: port.opticTemperature,
            biasCurrent: port.biasCurrent,
            supplyVoltage: port.supplyVoltage,
            moduleStatus: port.moduleStatus,
            hasOptic,
            onuText,
            link,
            linkText: link === 'online' ? '在线' : link === 'offline' ? '离线' : '',
            vlanText,
            lamp,
            lampText
          }
        })
        .sort((a, b) => (a.no || 0) - (b.no || 0))
    },
    /**
     * 端口数补齐到板卡的 portCount：回显里没出现的端口号按 0..portCount-1 补占位，
     * 名字与已有端口的命名一致（P3、P4…）。已有端口数够了就不动（鸿升光 1..4 号不会多出 0 号口）。
     */
    completePorts(board, ports) {
      // 端口数后端已按型号档案兜底，这里只按它补占位口
      const total = board.portCount
      if (!total || ports.length >= total) {
        return ports
      }
      const have = new Set(ports.map(port => port.portNo))
      const frameNo = ports.length && ports[0].frameNo != null ? ports[0].frameNo : 0
      const missing = []
      for (let no = 0; no < total; no++) {
        if (!have.has(no)) {
          missing.push({
            frameNo,
            slotNo: board.slotNo,
            portNo: no,
            portName: 'P' + no
          })
        }
      }
      return ports.concat(missing)
    },
    /** 把 1,41,45,46 压成 1、41、45-46：连续段合成区间，和网管上的写法一致 */
    compressVlans(vlans) {
      return compressVlanRange(vlans)
    },
    /**
     * 某个接口放通的 VLAN（上行口才有）：按接口号去 display port vlan 的快照里找，
     * 面板上每一口的「VLAN …」就是从这里来的，没采集到就返回空串。
     */
    portVlanTextOf(iface) {
      if (!iface) return ''
      const row = (this.portVlans || []).find(item => item && item.iface === iface)
      if (!row) return ''
      if (row.vlanText) return row.vlanText
      return row.vlans && row.vlans.length ? compressVlanRange(row.vlans) : ''
    },
    /** 「槽位:端口」作为展开 ONT 明细的 key */
    ontKey(slotNo, portNo) {
      return slotNo + ':' + portNo
    },
    isOntsOpen(slotNo, portNo) {
      return !!this.expandedOnts[this.ontKey(slotNo, portNo)]
    },
    toggleOnts(slotNo, portNo, count) {
      if (!count) return
      const key = this.ontKey(slotNo, portNo)
      const next = { ...this.expandedOnts }
      if (next[key]) {
        delete next[key]
      } else {
        next[key] = true
      }
      this.expandedOnts = next
    },
    /** 端口和面板上的口共用同一个 key，hover 时两边一起亮 */
    isPortActive(slotNo, portNo) {
      return this.hoverPort === this.ontKey(slotNo, portNo)
    },
    hoverOn(slotNo, portNo) {
      this.hoverPort = this.ontKey(slotNo, portNo)
    },
    hoverOff() {
      this.hoverPort = ''
    },
    /** 板卡下所有端口的 ONT 合计，放在「端口 / ONT」这一行的右侧 */
    buildOnuSummary(board) {
      const ports = board.ports || []
      let total = 0
      let online = 0
      let known = 0
      ports.forEach(port => {
        if (port.onuTotal != null) {
          total += port.onuTotal
          online += port.onuOnline || 0
          known++
        }
      })
      return known ? (total + ' ONT / ' + online + ' 在线') : ''
    },
    /**
     * 状态等级 → 图标：等级的判定在服务端（型号档案 + 状态翻译规则），
     * 这里只是把等级翻成一个图标，属于页面表现。
     */
    statusIconOf(level) {
      if (level === 'success') return 'el-icon-success'
      if (level === 'danger') return 'el-icon-error'
      if (level === 'warning') return 'el-icon-warning-outline'
      return 'el-icon-info'
    }
  }
}
