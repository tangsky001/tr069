import {
  fetchOltDetail,
  fetchOltRecords,
  testOltById,
  quickSyncOlt,
  refreshOlt,
  confirmOltBoards,
  createDbaProfile,
  deleteDbaProfiles,
  addLineTcont,
  addLineGemport,
  addLineGemMapping,
  createOltVlan,
  deleteOltVlans,
  addOltPortVlan,
  removeOltPortVlan,
  deleteAllOnts,
  fetchOltActions,
  quickSetupOlt,
  execOltAction,
  fetchOltOptions,
  syncOltSection
} from '@/api/olt'

/**
 * 页签名 → 后端采集段（/olt/{id}/sync-section 的 scope）。
 *
 * <p>映射表由后端 /olt/options 的 codeTables.tabSections 下发：哪些页签采哪一段
 * 是设备采集知识，不该写死在页面里。页面只按表查，查不到说明这个页签不触发采集。</p>
 */

/**
 * OLT 详情页公共逻辑：取台账、板卡、VLAN、采集记录，翻成页面上要显示的样子。
 *
 * 华为（huawei.vue）与第三方（generic.vue，鸿升光/中兴/烽火都走它）共用这一份：

 *
 * 依赖宿主页面提供一个 detailId（通常取路由上的 id），来源由页面自己决定。
 */
export const oltDetailMixin = {
  data() {
    return {
      loading: false,
      syncing: false,
      testing: false,
      confirmingBoards: false,
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
          // 板卡灯态与 ONT 汇总文本由后端算好下发
          lampState: board.lampState || '',
          onuSummary: board.onuSummary || ''
        }
      })
    },
    /** 机框画几个槽位：后端按 display board 0 的槽位表算好（设备没回时按板卡最大槽位兜底） */
    chassisSlotCount() {
      return this.detail.chassisSlotCount || 0
    },
    /** 「ONT 配置/状态」页只列有端口的板卡（业务板），主控、上联板没有 ONT */
    ontBoards() {
      return this.slots.filter(slot => slot.ports.length)
    },
    /** ONT 页汇总句里的端口数：后端数好 */
    ontPortTotal() {
      return this.detail.ontPortTotal || 0
    },
    /** ONT 页汇总句里的业务板块数：后端数好 */
    ontBoardCount() {
      return this.detail.ontBoardCount || 0
    },
    /**
     * ONT 摊平行（板卡 × 端口 × ONT 一条一行）：后端 detail.ontRows 已摊好，
     * 页面不再自己遍历板卡树、也不再按 {@code ont.id / ont.stateText} 取错字段。
     */
    ontRows() {
      return this.detail.ontRows || []
    },
    /** ONT 列表的「板卡端口」筛选下拉，后端一起给 */
    ontPortOptions() {
      return this.detail.ontPortOptions || []
    },
    /** ONT 按板卡分表的视图（板卡 → 端口 → ONT），后端把端口补齐与光功率都算好了 */
    ontCards() {
      return this.detail.ontCards || []
    },
    /** 全局 ONT 汇总：total / online / offline 由后端算好 */
    ontSummary() {
      return this.detail.ontSummary || { total: 0, online: 0, offline: 0 }
    },
    /** 「删除业务板 ONU」用：哪几块板有 ONT、各多少条，后端按槽位排好 */
    deletableBoards() {
      return this.detail.deletableBoards || []
    },
    deletableOntTotal() {
      return this.detail.deletableOntTotal || 0
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
     * <p>早期快照只有上联电口时按 EPON 板卡补 PON 口的规则在后端 detail 组装时完成
     * （GPON 的 PON 口不做二层成员，不补），这里直接透传后端结果。</p>
     */
    memberPorts() {
      return (this.vlanInfo && this.vlanInfo.memberPorts) || []
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
    /** VLAN 类型分组（含二层互通那排）：分组、压缩文本、标准/业务口数都在后端算好 */
    vlanGroupRows() {
      return this.detail.vlanGroups || []
    },
    /** 固件版本「版本 / 补丁」串：后端随台账视图给 */
    firmwareText() {
      return this.device.firmwareText || '-'
    },
    /** 板卡类别统计文本：后端按型号档案与板卡类别算好（整机设备报型号规格） */
    boardSummary() {
      return this.detail.boardSummary || ''
    },
    /** 有板卡停在 auto-find（插上了没确认）时才显示「确认所有业务板」 */
    boardsNeedConfirm() {
      return !!this.detail.boardsNeedConfirm
    },
    /** 状态中文与标签色：后端随台账视图给（在线 / 离线 / 未探测） */
    statusText() {
      return this.device.statusText || '未探测'
    },
    statusTag() {
      return this.device.statusLevel || 'info'
    },
    /** Web 登录账号串：后端拼好「a、b」，没限制时是「不限制」 */
    webAccountText() {
      return this.device.webAccountText || '不限制'
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
          // 先用库里的快照把页面撑起来（秒开），紧接着自动板卡刷新。
          // 要不要真的上设备由后端决策：板卡数据 5 分钟内同步过，后端直接回库里的详情
          this.applyDetail(res.data)
        })
        .catch(() => {})
        .finally(() => {
          this.loading = false
          this.refreshLive(id)
        })
    },
    /**
     * 实时刷新（scope=boards）：后端统一决策——板卡数据 5 分钟内同步过就直接回库里
     * 的详情（不占设备会话），否则连一次设备采板卡。前端只管调 API。
     * 失败不抹掉已经显示的快照，只在页面上说明「现在是旧的、为什么没取到新的」。
     */
    refreshLive(id) {
      const target = id || this.detailId
      if (!target || this.refreshing) return Promise.resolve()
      this.refreshing = true
      this.liveError = ''
      // scope=boards：首屏只要机框，后台深度补采不再触发——其余数据段由各页签点击时自己采
      return quickSyncOlt(target, 'boards')
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
     * 页签点击：按后端下发的 codeTables.tabSections 映射同步该页签的数据段
     * （boards/ont/vlan/template）。表里没有的页签不触发采集。
     */
    handleTabClick(tab) {
      const tables = this.options && this.options.codeTables ? this.options.codeTables : {}
      const scope = (tables.tabSections || {})[tab && tab.name]
      if (scope) {
        this.syncSection(scope)
      }
    },
    /**
     * 按页签同步：后端只采对应数据段并返回最新详情，页面直接换上。
     * 60 秒节流在后端做（同一设备同一采集段重复请求直接回库里的详情）；
     * force=true 由后端跳过节流，「同步ONT状态」这类明确要最新数据的按钮走它。
     */
    syncSection(scope, force) {
      const target = this.detailId
      if (!target) return Promise.resolve()
      return syncOltSection(target, scope, force)
        .then(res => {
          this.applyDetail(res.data)
          this.liveAt = new Date()
          this.liveError = ''
        })
        .catch(err => {
          const data = (err && err.response && err.response.data) || {}
          this.liveError = data.message || (err && err.message) || '同步失败'
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
     * 确认所有待确认板卡（board confirm 0）：确认成功后重采板卡，
     * 状态变正常了，按钮也就不用再显示。
     */
    handleConfirmBoards() {
      if (!this.detailId || this.confirmingBoards) return
      this.confirmingBoards = true
      confirmOltBoards(this.detailId)
        .then(res => {
          this.applyDetail(res.data)
          this.liveAt = new Date()
          const pending = this.boards.filter(board => /auto[_ ]?find/i.test(board.status || '')).length
          this.$message.success(pending ? '确认命令已下发，但仍有 ' + pending + ' 块板卡待确认，稍后重新采集' : '板卡确认完成，状态已恢复正常')
        })
        .catch(err => {
          const data = (err && err.response && err.response.data) || {}
          this.$message.error(data.message || (err && err.message) || '板卡确认失败')
        })
        .finally(() => {
          this.confirmingBoards = false
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
    /** 线路模板加 T-CONT（下发 tcont <索引> dba-profile-id <DBA>） */
    addTcont(payload) {
      return addLineTcont(this.detailId, payload)
        .then(res => this.applyAfterConfig(res, '新增T-CONT'))
    },
    /** 线路模板加 GEM Port（下发 gem add <索引> eth tcont <T-CONT> encrypt on） */
    addGemport(payload) {
      return addLineGemport(this.detailId, payload)
        .then(res => this.applyAfterConfig(res, '新增GEM Port'))
    },
    /** 给 GEM Port 加映射（下发 gem mapping <GEM> <序号> vlan <VLAN>） */
    addGemMapping(payload) {
      return addLineGemMapping(this.detailId, payload)
        .then(res => this.applyAfterConfig(res, '新增GEM映射'))
    },
    /** 新增 VLAN（下发 vlan standard）：后端不等重采，返回即显示 */
    createVlan(payload) {
      return createOltVlan(this.detailId, payload)
        .then(res => {
          this.applyDetail(res.data)
          this.liveAt = new Date()
          this.liveError = ''
          this.$message.success('新增 VLAN 成功，已立即显示（设备数据后台采集中）')
          return res
        })
    },
    /** 删除勾选的 VLAN（下发 no vlan） */
    deleteVlan(vlanIds) {
      return deleteOltVlans(this.detailId, vlanIds)
        .then(res => this.applyAfterConfig(res, '删除VLAN'))
    },
    /**
     * 上行口放通 VLAN（下发 port vlan 41,45-46 0/7 0），VLAN 透传表的「添加」调用。
     * 后端下发成功后只重读 VLAN，接口返回的就是最新快照，页面换上即可。
     */
    addPortVlan(payload) {
      return addOltPortVlan(this.detailId, payload)
        .then(res => this.applyAfterConfig(res, '放通 VLAN'))
    },
    /** 上行口解除 VLAN 绑定（下发 no port vlan ...） */
    removePortVlan(payload) {
      return removeOltPortVlan(this.detailId, payload)
        .then(res => this.applyAfterConfig(res, '移除 VLAN'))
    },
    /**
     * 删除业务板上的全部 ONT（interface gpon 0/<槽位> → 逐口 ont delete <端口> all）。
     * 槽位/端口由后端按库里采到的实际板卡算，接口返回的就是重采后的最新详情。
     */
    deleteAllOnts(slots) {
      return deleteAllOnts(this.detailId, slots)
        .then(res => this.applyAfterConfig(res, '删除业务板 ONU'))
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
    /**
     * 详情接口与同步接口返回的结构一致，统一在这里落到 this.detail。
     *
     * <p>原来这里手工挑字段重建 detail（白名单），后端每加一个聚合字段都要记得
     * 来这里补一行——ontRows / ontPortOptions / ontCards 就是漏掉的：
     * 接口响应里明明有数据，落到页面却永远是 undefined，「总数 102、列表 0 条」。
     * 现在后端给什么就保留什么，页面只对缺失的结构兜底，不再需要同步维护。</p>
     */
    applyDetail(data) {
      const body = data || {}
      this.detail = {
        boards: [],
        records: [],
        ...body
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
          // 光模块灯（on / off / warn 三态与文案）、链路中文、接口号、放通 VLAN 都在后端
          // enrichPort 里算好了；页面只取值，不再按 opticStatus 正则现推一遍灯色
          const frameNo = port.frameNo == null ? 0 : port.frameNo
          const slotNo = port.slotNo == null ? board.slotNo : port.slotNo
          const iface = port.iface || (slotNo == null ? '' : frameNo + '/' + slotNo + '/' + port.portNo)
          // 没采过端口光模块命令的设备不画灯
          const hasOptic = port.opticalPresent != null
          const vlanText = port.vlanText || ''
          // 每个 ONT 一条：页面点开端口后逐个显示，在线和不在线都列
          const onts = (port.onts || []).map(ont => ({
            id: ont.ontId,
            sn: ont.sn || '-',
            desc: ont.description || '',
            online: ont.online === true,
            // 状态与配置状态的中文由后端给（stateText / configText）
            stateText: ont.stateText || '',
            configText: ont.configText || '',
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
            rxPower: ont.rxPower || '',
            // 同步采集时随单台详情（display ont info 0 <槽> <口> <id>）一起采回的字段，
            // 后端没给就是空，页面显示「-」；老快照缺字段时点一次「同步ONT状态」补齐
            mgmtIp: ont.mgmtIp || '',
            mac: ont.mac || '',
            lineProfileId: ont.lineProfileId == null ? null : ont.lineProfileId,
            lineProfileName: ont.lineProfileName || '',
            srvProfileId: ont.srvProfileId == null ? null : ont.srvProfileId,
            srvProfileName: ont.srvProfileName || ''
          }))
          // 口的 ONT 汇总（总数 / 在线数 / 汇总文本）后端已算好，页面不再按明细兜底
          const onuTotal = port.onuTotal == null ? 0 : port.onuTotal
          const onuOnline = port.onuOnline == null ? 0 : port.onuOnline
          const onuText = port.onuText || ''
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
            // 链路码（online / offline）只用于 class，中文由后端给
            link: (port.linkStatus || '').toLowerCase(),
            linkText: port.linkText || '',
            vlanText,
            // 端口灯三态与光模块灯都由后端判定（lampState / opticLamp / opticLampText）
            lampState: port.lampState,
            lamp: port.opticLamp || 'unknown',
            lampText: port.opticLampText || ''
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
    /**
     * 某个接口放通的 VLAN（上行口才有）：按接口号去 display port vlan 的快照里找。
     * 压缩好的串（{@code 1、41、45-46}）由后端随快照给，页面不再自己压。
     */
    portVlanTextOf(iface) {
      if (!iface) return ''
      const row = (this.portVlans || []).find(item => item && item.iface === iface)
      return (row && row.vlanText) || ''
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
