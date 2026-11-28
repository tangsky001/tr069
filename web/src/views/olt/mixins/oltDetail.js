import {
  fetchOltDetail,
  fetchOltRecords,
  testOltById,
  quickSyncOlt,
  refreshOlt,
  confirmOltBoards,
  saveOltConfig,
  createDbaProfile,
  createLineProfile,
  deleteLineProfiles,
  deleteDbaProfiles,
  addLineTcont,
  addLineGemport,
  addLineGemMapping,
  createOltVlan,
  deleteOltVlans,
  addOltPortVlan,
  removeOltPortVlan,
  deleteAllOnts,
  deleteOltOnt,
  fetchLinkAggregations,
  applyLinkAggregation,
  resetOltOnt,
  fetchOltActions,
  quickSetupOlt,
  execOltAction,
  fetchOltOptions,
  syncOltSection
} from '@/api/olt'

/**
 * OLT 详情页公共逻辑（华为与第三方页面共用）：取台账、板卡、VLAN、采集记录并翻成页面展示数据；依赖宿主页面提供 detailId。
 */
export const oltDetailMixin = {
  data() {
    return {
      loading: false,
      syncing: false,
      testing: false,
      savingConfig: false,
      confirmingBoards: false,
      detail: { device: null, boards: [], records: [], vlan: null, template: null },
      // 页面自动刷新：按间隔拉最新数据，不碰设备
      autoRefresh: true,
      pollSeconds: 10,
      pollTimer: null,
      // 实时数据：直连 OLT 采集优先，库快照兜底
      liveAt: null,
      liveError: '',
      refreshing: false,
      // 后端正在后台补采慢数据
      deepSyncing: false,
      // 展开看 ONT 明细的端口，key 是「槽位:端口」
      expandedOnts: {},
      // 鼠标停在端口行/示意图标注上时，两边一起高亮
      hoverPort: '',
      // 「设备配置」页签的命令动作目录（后端按厂商下发）
      commandActions: [],
      // 链路聚合组：页签打开时实时查询
      linkAggregations: [],
      linkAggLoading: false,
      linkAggApplying: false,
      // 码表与阈值（后端一次下发）
      options: null
    }
  },
  computed: {
    device() {
      return this.detail.device || {}
    },
    /** 鸿升光整机：板卡区画横向整机面板 */
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
        // 展示字段大多由后端按型号档案算好随板卡下发
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
          stripTitle: board.stripTitle || '',
          controlBoard: !!board.controlBoard,
          frontPanel: board.frontPanel || null,
          ports,
          lampState: board.lampState || '',
          onuSummary: board.onuSummary || ''
        }
      })
    },
    /** 机框槽位数，后端按槽位表算好 */
    chassisSlotCount() {
      return this.detail.chassisSlotCount || 0
    },
    /** 「ONT 配置/状态」页只列有端口的板卡（业务板） */
    ontBoards() {
      return this.slots.filter(slot => slot.ports.length)
    },
    /** ONT 汇总的端口数 */
    ontPortTotal() {
      return this.detail.ontPortTotal || 0
    },
    /** ONT 汇总的业务板块数 */
    ontBoardCount() {
      return this.detail.ontBoardCount || 0
    },
    /** ONT 摊平行（板卡 × 端口 × ONT 一条一行），后端已摊好 */
    ontRows() {
      return this.detail.ontRows || []
    },
    /** ONT 列表的「板卡端口」筛选下拉 */
    ontPortOptions() {
      return this.detail.ontPortOptions || []
    },
    /** ONT 按板卡分表的视图（板卡 → 端口 → ONT） */
    ontCards() {
      return this.detail.ontCards || []
    },
    /** 全局 ONT 汇总：total / online / offline */
    ontSummary() {
      return this.detail.ontSummary || { total: 0, online: 0, offline: 0 }
    },
    /** 有 ONT 可删的板卡清单（「删除业务板 ONU」用） */
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
    /** 「创建 VLAN」弹窗可勾选的成员端口 */
    memberPorts() {
      return (this.vlanInfo && this.vlanInfo.memberPorts) || []
    },
    vlanIpInterfaces() {
      return (this.vlanInfo && this.vlanInfo.ipInterfaces) || []
    },
    staticRoutes() {
      return (this.vlanInfo && this.vlanInfo.staticRoutes) || []
    },
    /** DBA 模板 + 线路模板快照 */
    templateInfo() {
      return this.detail.template || null
    },
    dbaProfiles() {
      return (this.templateInfo && this.templateInfo.dbaProfiles) || []
    },
    lineProfiles() {
      return (this.templateInfo && this.templateInfo.lineProfiles) || []
    },
    /** ONT 能力模板（含 TR069 管理参数） */
    srvProfiles() {
      return (this.templateInfo && this.templateInfo.srvProfiles) || []
    },
    /** TR069 服务器模板（ACS 地址与账号） */
    tr069Profiles() {
      return (this.templateInfo && this.templateInfo.tr069Profiles) || []
    },
    /** 流量限速模板（CIR / PIR） */
    trafficTables() {
      return (this.templateInfo && this.templateInfo.trafficTables) || []
    },
    /** VLAN 类型分组（后端算好） */
    vlanGroupRows() {
      return this.detail.vlanGroups || []
    },
    /** 固件版本「版本 / 补丁」串 */
    firmwareText() {
      return this.device.firmwareText || '-'
    },
    /** 板卡类别统计文本 */
    boardSummary() {
      return this.detail.boardSummary || ''
    },
    /** 有板卡停在 auto-find（插上了没确认）时才显示「确认所有业务板」 */
    boardsNeedConfirm() {
      return !!this.detail.boardsNeedConfirm
    },
    /** 状态中文（在线 / 离线 / 未探测） */
    statusText() {
      return this.device.statusText || '未探测'
    },
    statusTag() {
      return this.device.statusLevel || 'info'
    },
    /** Web 登录账号串，没限制时是「不限制」 */
    webAccountText() {
      return this.device.webAccountText || '不限制'
    },
    /** 数据来源提示：实时采集优先，失败时退回库快照并标注时间 */
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
    // 间隔或开关变化时重启定时器
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
    // 补采中拉密轮询，补完恢复
    deepSyncing() {
      this.startPolling()
    },
    // 换设备重拉该厂商的命令动作目录
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
    /** 加载后端一次下发的码表与阈值 */
    loadOptions() {
      return fetchOltOptions()
        .then(res => {
          this.options = res.data
        })
        .catch(() => {
          this.options = null
        })
    },
    /** 自动刷新：页面空闲时按间隔静默拉详情 */
    startPolling() {
      this.stopPolling()
      if (!this.autoRefresh) return
      // 补采期间拉密一点（3 秒）
      const seconds = this.deepSyncing ? 3 : this.pollSeconds
      this.pollTimer = setInterval(() => {
        // 实时采集中不打断
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
          // 先用库快照秒开，是否上设备由后端决策
          this.applyDetail(res.data)
        })
        .catch(() => {})
        .finally(() => {
          this.loading = false
          this.refreshLive(id)
        })
    },
    /** 实时刷新（scope=boards）：是否连设备由后端决策，失败保留旧快照并提示 */
    refreshLive(id) {
      const target = id || this.detailId
      if (!target || this.refreshing) return Promise.resolve()
      this.refreshing = true
      this.liveError = ''
      // 首屏只采板卡，其余数据段由各页签按需采
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
    /** 页签点击：按后端下发的 tabSections 映射同步对应数据段 */
    handleTabClick(tab) {
      const tables = this.options && this.options.codeTables ? this.options.codeTables : {}
      const scope = (tables.tabSections || {})[tab && tab.name]
      if (scope) {
        this.syncSection(scope)
      }
      // 链路聚合页签首次打开时实时查一次
      if (tab && tab.name === 'ola' && !this.linkAggregations.length) {
        this.loadLinkAggregations()
      }
    },
    /** 按页签同步：后端 60 秒节流，force=true 跳过节流取最新 */
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
    /** 完整实时刷新：连设备把端口/ONT/VLAN/模板全采一遍 */
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
    /** 确认所有待确认板卡（board confirm 0），成功后重采 */
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
    /** 对 OLT 做过操作后重采最新数据 */
    afterOltOperation() {
      return this.refreshLive(this.detailId)
    },
    /** 下发配置的公共收尾：换上返回的最新数据并提示 */
    applyAfterConfig(res, label) {
      this.applyDetail(res.data)
      this.liveAt = new Date()
      this.liveError = ''
      this.$message.success(label + '成功，已重新采集最新数据')
      return res
    },
    /** 新增 DBA 模板 */
    createDba(payload) {
      return createDbaProfile(this.detailId, payload)
        .then(res => this.applyAfterConfig(res, '新增DBA模板'))
    },
    /** 删除勾选的 DBA 模板 */
    deleteDba(profileIds) {
      return deleteDbaProfiles(this.detailId, profileIds)
        .then(res => this.applyAfterConfig(res, '删除DBA模板'))
    },
    /** 新增 ONT 线路模板 */
    createLineProfile(payload) {
      return createLineProfile(this.detailId, payload)
        .then(res => this.applyAfterConfig(res, '新增线路模板'))
    },
    /** 删除勾选的线路模板 */
    deleteLine(profileIds) {
      return deleteLineProfiles(this.detailId, profileIds)
        .then(res => this.applyAfterConfig(res, '删除线路模板'))
    },
    /** 线路模板加 T-CONT */
    addTcont(payload) {
      return addLineTcont(this.detailId, payload)
        .then(res => this.applyAfterConfig(res, '新增T-CONT'))
    },
    /** 线路模板加 GEM Port */
    addGemport(payload) {
      return addLineGemport(this.detailId, payload)
        .then(res => this.applyAfterConfig(res, '新增GEM Port'))
    },
    /** 给 GEM Port 加映射 */
    addGemMapping(payload) {
      return addLineGemMapping(this.detailId, payload)
        .then(res => this.applyAfterConfig(res, '新增GEM映射'))
    },
    /** 新增 VLAN */
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
    /** 删除勾选的 VLAN */
    deleteVlan(vlanIds) {
      return deleteOltVlans(this.detailId, vlanIds)
        .then(res => this.applyAfterConfig(res, '删除VLAN'))
    },
    /** 上行口放通 VLAN */
    addPortVlan(payload) {
      return addOltPortVlan(this.detailId, payload)
        .then(res => this.applyAfterConfig(res, '放通 VLAN'))
    },
    /** 上行口解除 VLAN 绑定 */
    removePortVlan(payload) {
      return removeOltPortVlan(this.detailId, payload)
        .then(res => this.applyAfterConfig(res, '移除 VLAN'))
    },
    /** 删除业务板上的全部 ONT */
    deleteAllOnts(slots) {
      return deleteAllOnts(this.detailId, slots)
        .then(res => this.applyAfterConfig(res, '删除业务板 ONU'))
    },
    /** 删除单个 ONT */
    deleteOnt(payload) {
      return deleteOltOnt(this.detailId, payload)
        .then(res => this.applyAfterConfig(res, '删除注册'))
    },
    /** 重启 ONU：缺 ontId 复位整条 PON 口、缺 port 复位整块板卡 */
    resetOltOntOnDevice(payload) {
      return resetOltOnt(this.detailId, payload).then(res => {
        this.$message.success((res && res.data) || '重启命令已下发')
        return res
      })
    },
    /** 链路聚合组实时查询，silent 时不动 loading */
    loadLinkAggregations(silent) {
      if (!this.detailId) return Promise.resolve()
      if (!silent) this.linkAggLoading = true
      return fetchLinkAggregations(this.detailId)
        .then(res => {
          this.linkAggregations = (res && res.data) || []
        })
        .catch(() => {})
        .finally(() => {
          this.linkAggLoading = false
        })
    },
    /** 创建链路聚合组 */
    createLinkAggregation(form) {
      this.linkAggApplying = true
      return applyLinkAggregation(this.detailId, { op: 'create', ...form })
        .then(res => {
          this.linkAggregations = (res && res.data) || []
          this.$message.success('聚合组已创建，成员口业务已按聚合口转发')
        })
        .finally(() => {
          this.linkAggApplying = false
        })
    },
    /** 删除链路聚合组 */
    deleteLinkAggregation(masterFs) {
      this.linkAggApplying = true
      return applyLinkAggregation(this.detailId, { op: 'delete', masterFs })
        .then(res => {
          this.linkAggregations = (res && res.data) || []
          this.$message.success('聚合组已删除，成员口恢复独立口转发')
        })
        .finally(() => {
          this.linkAggApplying = false
        })
    },
    /** 拉命令动作目录，没接入的厂商是空表 */
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
          this.commandActions = []
          return []
        })
    },
    /** 一键开局：一次性下发弹窗选好的命令 */
    handleQuickSetup(payload) {
      return quickSetupOlt(this.detailId, payload)
        .then(res => this.applyAfterConfig(res, '一键开局'))
    },
    /** 执行一条命令动作：配置类刷新页面，查询类交给组件显示 */
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
    /** 保存配置：下发 save 写 flash，掉电不丢 */
    handleSaveConfig() {
      if (!this.detailId) return
      this.$confirm(
        '把当前运行配置写入 OLT 的 flash，掉电后配置不丢。写盘要几十秒且期间设备不接其它操作，是否继续？',
        '保存配置',
        { confirmButtonText: '保存', cancelButtonText: '取消', type: 'warning' }
      )
        .then(() => {
          this.savingConfig = true
          return saveOltConfig(this.detailId)
        })
        .then(res => {
          if (res && res.data) {
            this.applyDetail(res.data)
          }
          this.$message.success('已保存到设备 flash，可在「采集记录」查看回显')
        })
        .catch(e => {
          if (e === 'cancel' || (e && e.message && e.message.includes('cancel'))) return
          this.$message.error((e && e.message) || '保存配置失败')
        })
        .finally(() => {
          this.savingConfig = false
        })
    },
    handleSync(silent) {
      if (!this.detailId) return
      this.syncing = true
      // 分两段：板卡先出来，慢数据后台补
      return quickSyncOlt(this.detailId)
        .then(res => {
          this.applyDetail(res.data)
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
    /** 详情与同步接口结构一致，统一落到 this.detail */
    applyDetail(data) {
      const body = data || {}
      this.detail = {
        boards: [],
        records: [],
        ...body
      }
      // 补采中保持提示与密轮询，结束后自动恢复
      this.deepSyncing = !!body.deepSyncing
    },
    /** 端口明细行组装（含光模块灯三态） */
    buildPorts(board) {
      // 回显只列有 ONT 的端口，钉行模式要求每口一行，缺的按端口号补占位
      const ports = this.completePorts(board, board.ports || [])
      return ports
        .map(port => {
          const frameNo = port.frameNo == null ? 0 : port.frameNo
          const slotNo = port.slotNo == null ? board.slotNo : port.slotNo
          const iface = port.iface || (slotNo == null ? '' : frameNo + '/' + slotNo + '/' + port.portNo)
          // 没采过端口光模块命令的设备不画灯
          const hasOptic = port.opticalPresent != null
          const vlanText = port.vlanText || ''
          const onts = (port.onts || []).map(ont => ({
            id: ont.ontId,
            sn: ont.sn || '-',
            desc: ont.description || '',
            online: ont.online === true,
            stateText: ont.stateText || '',
            configText: ont.configText || '',
            matchText: ont.matchState || '',
            // 部分厂商才回显的字段，采不到显示「-」
            name: ont.name || '',
            authorized: ont.authorized,
            registerTime: ont.registerTime || '',
            lastOfflineTime: ont.lastOfflineTime || '',
            offlineReason: ont.offlineReason || '',
            deviceType: ont.deviceType || '',
            onuType: ont.onuType || '',
            distance: ont.distance,
            rxPower: ont.rxPower || '',
            // 随单台详情采回，老快照缺字段时点「同步ONT状态」补齐
            mgmtIp: ont.mgmtIp || '',
            mac: ont.mac || '',
            lineProfileId: ont.lineProfileId == null ? null : ont.lineProfileId,
            lineProfileName: ont.lineProfileName || '',
            srvProfileId: ont.srvProfileId == null ? null : ont.srvProfileId,
            srvProfileName: ont.srvProfileName || ''
          }))
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
            rxPower: port.rxPower,
            txPower: port.txPower,
            opticTemperature: port.opticTemperature,
            biasCurrent: port.biasCurrent,
            supplyVoltage: port.supplyVoltage,
            moduleStatus: port.moduleStatus,
            hasOptic,
            onuText,
            link: (port.linkStatus || '').toLowerCase(),
            linkText: port.linkText || '',
            vlanText,
            lampState: port.lampState,
            lamp: port.opticLamp || 'unknown',
            lampText: port.opticLampText || ''
          }
        })
        .sort((a, b) => (a.no || 0) - (b.no || 0))
    },
    /** 端口数补齐到板卡的 portCount，缺的补占位口 */
    completePorts(board, ports) {
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
    /** 某个接口放通的 VLAN 压缩串（上行口） */
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
    /** 状态等级 → 图标 */
    statusIconOf(level) {
      if (level === 'success') return 'el-icon-success'
      if (level === 'danger') return 'el-icon-error'
      if (level === 'warning') return 'el-icon-warning-outline'
      return 'el-icon-info'
    }
  }
}
