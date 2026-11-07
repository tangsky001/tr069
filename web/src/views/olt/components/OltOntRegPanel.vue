<template>
  <div class="ont-reg">
    <!-- 一、自动注册参数：光猫上线怎么被发现、怎么被自动授权 -->
    <div class="reg-block-title">自动注册</div>
    <el-form :model="autoConfig" label-width="210px" size="small" class="reg-auto-form">
      <el-form-item>
        <olt-help-label
          slot="label"
          text="启用 ONT 设备上线自动注册"
          tip="开启后新光猫上送 SN/MAC 就自动按模板授权，不用人工点。首次开局建议先关，确认模板正确再打开。"
        />
        <el-switch v-model="autoConfig.enabled" active-text="是" inactive-text="否" />
      </el-form-item>

      <el-form-item>
        <olt-help-label
          slot="label"
          text="自动发现扫描时间间隔"
          tip="多久扫一次待注册光猫（display ont autofind all）。太小会频繁登录设备，建议 30 秒以上。"
        />
        <!-- 上下限不写在前端：范围由后端定，填小了后端会夹回并在保存后回显实际值 -->
        <el-input-number v-model="autoConfig.scanSeconds" :step="5" controls-position="right" size="small" />
        <span class="reg-unit">秒</span>
      </el-form-item>

      <el-form-item>
        <olt-help-label
          slot="label"
          text="新光猫注册后自动保存时间"
          tip="授权成功后隔多久把配置存盘。华为只写运行配置，不 save 掉电就回到注册前。"
        />
        <el-input-number v-model="autoConfig.saveSeconds" :step="60" controls-position="right" size="small" />
        <span class="reg-unit">秒</span>
      </el-form-item>

      <el-form-item>
        <olt-help-label
          slot="label"
          text="并发注册光猫数量"
          tip="一轮最多注册几台，一台一台对 OLT 下发（CLI 会话有限，不并行）。没轮到的下一轮扫描时接着注册。"
        />
        <el-input-number v-model="autoConfig.concurrency" :step="1" controls-position="right" size="small" />
        <span class="reg-unit">台/轮</span>
      </el-form-item>

      <el-form-item>
        <olt-help-label
          slot="label"
          text="启用 ONT 自动移机"
          tip="光猫换到别的 PON 口上线时，自动把它的注册位置迁过去，而不是当成新光猫重复注册。"
        />
        <el-switch v-model="autoConfig.autoMove" active-text="是" inactive-text="否" />
      </el-form-item>

      <el-form-item>
        <olt-help-label
          slot="label"
          text="注册记录满时自动删除离线光猫"
          tip="设备上 ONT 注册表有上限，满了新光猫就注册不上。开启后自动清掉离线的旧记录。"
        />
        <el-switch v-model="autoConfig.autoDeleteOffline" active-text="是" inactive-text="否" />
      </el-form-item>

      <el-form-item label-width="210px">
        <!-- 后台任务是静默跑的：不显示这几个时间，没法区分「没在跑」和「跑了没注册上」 -->
        <div class="reg-auto-status">
          <span>上次扫描 <b>{{ autoConfig.lastScanText || '—' }}</b></span>
          <span>上次注册 <b>{{ autoConfig.lastRegisterText || '—' }}</b></span>
          <span>上次存盘 <b>{{ autoConfig.lastSaveText || '—' }}</b></span>
        </div>
        <el-button size="small" icon="el-icon-search" :loading="scanning" @click="runScanOnce">立即扫描一次</el-button>
        <el-button size="small" type="primary" icon="el-icon-check" @click="saveAutoConfig">保存设置</el-button>
      </el-form-item>
    </el-form>

    <!-- 二、注册模板：光猫上线时按优先级匹配，命中哪条就套哪套模板 -->
    <div class="reg-block-title">
      ONT 注册模板
      <el-checkbox v-model="onlyCurrentOlt" class="reg-block-extra">只显示当前 OLT 模板</el-checkbox>
    </div>

    <el-table :data="visibleTemplates" size="mini" border class="reg-table" empty-text="还没有注册模板，点下面「新建注册模版」加一条">
      <el-table-column label="优先级" width="60" align="center">
        <template slot-scope="{ row }">{{ row.priority }}</template>
      </el-table-column>

      <el-table-column label="名称" min-width="150">
        <template slot-scope="{ row }">
          <div class="cell-main">{{ row.name }}</div>
          <div class="cell-sub">{{ row.oltIp || '-' }}</div>
          <div class="cell-sub">{{ row.remark || '-' }}</div>
        </template>
      </el-table-column>

      <el-table-column label="PON / 设备" min-width="150">
        <template slot-scope="{ row }">
          <div class="cell-main">{{ row.ponType === 'EPON' ? 'epon' : 'gpon' }}</div>
          <div class="cell-sub">{{ row.ontModels || '不限型号' }}</div>
          <div class="cell-sub">{{ row.bindPorts || '不限端口' }}</div>
        </template>
      </el-table-column>

      <el-table-column label="模板 / 注册方式" min-width="150">
        <template slot-scope="{ row }">
          <div class="cell-main">{{ row.lineProfileId || '-' }} / {{ row.srvProfileId || '-' }}</div>
          <div class="cell-sub">{{ authLabel(row.authMode) }}</div>
        </template>
      </el-table-column>

      <el-table-column label="业务 VLAN" min-width="150">
        <template slot-scope="{ row }">
          <div class="cell-main">{{ row.serviceVlanMap || '-' }}</div>
          <div class="cell-sub">TR069：{{ row.tr069Vlan || '-' }}</div>
          <div class="cell-sub">组播：{{ row.iptvVlan || '-' }}</div>
        </template>
      </el-table-column>

      <el-table-column label="状态" width="60" align="center">
        <template slot-scope="{ row }">
          <el-switch v-model="row.enabled" @change="onToggleEnabled(row)" />
        </template>
      </el-table-column>

      <el-table-column label="编辑" width="60" align="center">
        <template slot-scope="{ row }">
          <el-button type="text" icon="el-icon-edit" @click="openEdit(row)" />
        </template>
      </el-table-column>

      <el-table-column width="50" align="center">
        <template slot="header">
          <el-checkbox :value="allChecked" @change="toggleAll" />
        </template>
        <template slot-scope="{ row }">
          <el-checkbox v-model="row.checked" />
        </template>
      </el-table-column>
    </el-table>

    <div class="reg-toolbar">
      <el-button type="text" icon="el-icon-plus" @click="openCreate">新建注册模版</el-button>
      <div class="reg-toolbar-right">
        <el-button type="text" :disabled="!checkedTemplates.length" @click="toggleChecked">{{ allChecked ? '全不选' : '全选' }}</el-button>
        <el-button type="text" icon="el-icon-delete" :disabled="!checkedTemplates.length" @click="removeChecked">删除</el-button>
      </div>
    </div>

    <!-- 三、自动发现：设备上等着授权的光猫 -->
    <div class="reg-block-title">
      ONT 自动发现
      <span class="reg-block-extra">当前 {{ discovered.length }} 条待注册</span>
    </div>

    <el-table :data="discovered" size="mini" border class="reg-table" empty-text="设备上当前没有待注册光猫。新接的光猫要等 OLT 发现，PON 口需要先开 port 0 ont-auto-find enable。">
      <el-table-column prop="id" label="ID" width="60" align="center" />
      <el-table-column label="端口 / PON 类型" min-width="120">
        <template slot-scope="{ row }">
          <div class="cell-main">{{ row.port }}</div>
          <div class="cell-sub">{{ row.ponType || 'gpon' }}</div>
        </template>
      </el-table-column>
      <el-table-column label="ONT SN" min-width="150">
        <template slot-scope="{ row }">
          <div class="cell-main">{{ row.sn }}</div>
          <div class="cell-sub">{{ row.equipmentId || '-' }}</div>
        </template>
      </el-table-column>
      <el-table-column label="逻辑 ID / 密码" min-width="120">
        <template slot-scope="{ row }">
          <div class="cell-main">{{ row.loid || '-' }}</div>
          <div class="cell-sub">{{ row.password || '-' }}</div>
        </template>
      </el-table-column>
      <el-table-column prop="checkCode" label="认证码" width="90" align="center" />
      <el-table-column label="厂商 / 型号" min-width="130">
        <template slot-scope="{ row }">
          <div class="cell-main">{{ row.vendorId || '-' }}</div>
          <div class="cell-sub">{{ row.model || '-' }}</div>
        </template>
      </el-table-column>
      <el-table-column label="硬件 / 软件版本" min-width="130">
        <template slot-scope="{ row }">
          <div class="cell-main">{{ row.hardwareVersion || '-' }}</div>
          <div class="cell-sub">{{ row.softwareVersion || '-' }}</div>
        </template>
      </el-table-column>
      <el-table-column prop="foundAt" label="发现时间" width="140" />
      <el-table-column width="50" align="center">
        <template slot="header">
          <el-checkbox :value="allDiscoveredChecked" @change="toggleAllDiscovered" />
        </template>
        <template slot-scope="{ row }">
          <el-checkbox v-model="row.checked" />
        </template>
      </el-table-column>
    </el-table>

    <div class="reg-toolbar">
      <div class="reg-toolbar-right">
        <el-select v-model="registerTemplateId" size="mini" placeholder="请选择注册模版" class="reg-template-select">
          <el-option v-for="item in templates" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
        <el-button size="mini" type="primary" icon="el-icon-upload2" :disabled="!checkedDiscovered.length" @click="registerChecked">
          一键注册
        </el-button>
        <el-button size="mini" icon="el-icon-search" :loading="discovering" @click="discover">查找新的 ONT 设备</el-button>
      </div>
    </div>

    <!-- 四、注册记录：后台自动注册跑了什么，只能在这儿看出来 -->
    <div class="reg-block-title">
      ONT 注册记录
      <span class="reg-block-extra">最近 {{ records.length }} 条</span>
    </div>

    <el-table :data="records" size="mini" border class="reg-table" empty-text="还没有注册记录。开自动注册后，后台扫到的光猫会按模板注册并记在这里。">
      <el-table-column label="时间" width="140">
        <template slot-scope="{ row }">{{ row.createdAt || '-' }}</template>
      </el-table-column>
      <el-table-column label="端口" width="90">
        <template slot-scope="{ row }">{{ row.fsp || '-' }}</template>
      </el-table-column>
      <el-table-column label="ONT SN" min-width="150">
        <template slot-scope="{ row }">
          <div class="cell-main">{{ row.ontSn || '-' }}</div>
          <div class="cell-sub">{{ row.model || '-' }}</div>
        </template>
      </el-table-column>
      <el-table-column label="模板 / 方式" min-width="130">
        <template slot-scope="{ row }">
          <div class="cell-main">{{ row.templateName || '-' }}</div>
          <div class="cell-sub">{{ authLabel(row.authMode) }}</div>
        </template>
      </el-table-column>
      <el-table-column label="来源" width="80" align="center">
        <template slot-scope="{ row }">{{ row.source === 'AUTO' ? '自动' : '手动' }}</template>
      </el-table-column>
      <el-table-column label="结果" min-width="220">
        <template slot-scope="{ row }">
          <el-tag :type="row.success === 1 ? 'success' : 'danger'" size="mini">
            {{ row.success === 1 ? '成功' : '失败' }}
          </el-tag>
          <span class="reg-record-message">{{ row.message || '-' }}</span>
        </template>
      </el-table-column>
    </el-table>

    <!-- 一键注册确认：命令由后端编，这里先把要做什么摊开确认 -->
    <el-dialog :visible.sync="registerVisible" title="一键注册" width="620px" append-to-body>
      <div class="reg-preview-tip">
        命令序列由后端按模板编排，确认后真下发到设备；一台失败不影响其它台。
      </div>

      <el-table :data="previewRows" size="mini" border class="reg-table">
        <el-table-column prop="port" label="端口" width="90" />
        <el-table-column prop="sn" label="ONT SN" min-width="170" />
        <el-table-column prop="model" label="型号" width="110" />
        <el-table-column prop="authLabel" label="注册方式" width="90" />
      </el-table>

      <div v-if="previewCommands.length" class="reg-preview-block">
        <div class="reg-preview-title">将要下发</div>
        <pre class="reg-command-preview">{{ previewCommands.join('\n') }}</pre>
      </div>

      <div class="reg-preview-block">
        <div>模板：<b>{{ previewName }}</b>（优先级 {{ previewTemplate ? previewTemplate.priority : '-' }}，{{ previewTemplate ? previewTemplate.ponType : '-' }}）</div>
        <div>线路 / 业务模板：{{ previewTemplate ? previewTemplate.lineProfileId + ' / ' + previewTemplate.srvProfileId : '-' }}</div>
        <div>光猫工作模式：{{ previewWorkMode }}</div>
        <div>绑定端口：{{ previewBindPorts }}；设备型号：{{ previewModels }}</div>
        <div>OLT 侧业务 VLAN 映射：{{ previewTemplate && previewTemplate.serviceVlanMap ? previewTemplate.serviceVlanMap : '沿用线路模板' }}</div>
        <div>下发 TR069 VLAN 及 ACS 地址：{{ previewTemplate && previewTemplate.tr069Vlan ? previewTemplate.tr069Vlan : '不下发' }}</div>
        <div v-if="previewTemplate && previewTemplate.iptvVlan">IPTV 组播 VLAN：{{ previewTemplate.iptvVlan }}</div>
        <div v-if="previewTemplate && previewTemplate.multicastVlan">透传未知多播 VLAN：{{ previewTemplate.multicastVlan }}</div>
      </div>

      <div slot="footer">
        <el-button size="small" type="primary" :loading="registering" @click="confirmRegister">确认下发</el-button>
        <el-button size="small" :disabled="registering" @click="registerVisible = false">取消</el-button>
      </div>
    </el-dialog>

    <!-- 新建/编辑模板 -->
    <olt-ont-reg-template-dialog
      :visible.sync="templateDialogVisible"
      :template="editingTemplate"
      :olt-id="oltId"
      :olt-ip="oltIp"
      @submit="submitTemplate"
    />
  </div>
</template>

<script>
import OltHelpLabel from './OltHelpLabel'
import OltOntRegTemplateDialog from './OltOntRegTemplateDialog'
import {
  fetchOntAutofind,
  fetchOntRegConfig,
  saveOntRegConfig,
  fetchOntRegRecords,
  fetchOntRegTemplates,
  runOntRegScan,
  saveOntRegTemplate,
  deleteOntRegTemplates,
  previewOntRegister,
  registerOnt,
  fetchOltOptions
} from '@/api/olt'

/**
 * ONT 注册 / 自动注册。
 *
 * <p>三块：自动注册参数、注册模板（按优先级匹配）、自动发现（设备上待授权的光猫）。
 * 模板和参数都存库，换台电脑打开、后台定时任务跑的都是同一份。</p>
 *
 * <p>注册记录单独一块：自动注册是后台跑的，页面上只能从记录里看出
 * 哪台注册了、套了哪条模板、没成的因为什么。</p>
 */

/**
 * 接口返回的参数翻成页面上的：后端存的是 0/1，页面上是开关。
 *
 * <p>这里不准备任何默认值——扫描间隔、存盘时间、并发这些默认值只有后端一份
 * （见后端的 {@code config(oltId)} 与建表脚本的 DEFAULT）。前端再抄一份，
 * 后端改了默认值页面还是老样子，看不出来。</p>
 *
 * @return 后端没给数据（接口异常）时返回 null，调用方保留原值、不自己编数字
 */
function toViewConfig(config) {
  if (!config) {
    return null
  }
  return {
    enabled: config.enabled === 1,
    scanSeconds: config.scanSeconds,
    saveSeconds: config.saveSeconds,
    concurrency: config.concurrency,
    autoMove: config.autoMove === 1,
    autoDeleteOffline: config.autoDeleteOffline === 1,
    // 后台任务的痕迹：页面上显示出来，一眼看出任务活着没有
    lastScanAt: config.lastScanAt || null,
    lastRegisterAt: config.lastRegisterAt || null,
    lastSaveAt: config.lastSaveAt || null
  }
}

/** 页面上的开关翻回后端要的 0/1 */
function toServerConfig(config) {
  return {
    enabled: config.enabled ? 1 : 0,
    scanSeconds: config.scanSeconds,
    saveSeconds: config.saveSeconds,
    concurrency: config.concurrency,
    autoMove: config.autoMove ? 1 : 0,
    autoDeleteOffline: config.autoDeleteOffline ? 1 : 0
  }
}

export default {
  name: 'OltOntRegPanel',
  components: { OltHelpLabel, OltOntRegTemplateDialog },
  props: {
    oltId: {
      type: [Number, String],
      default: null
    },
    /** 当前 OLT 的管理地址，新建模板时预填 */
    oltIp: {
      type: String,
      default: ''
    }
  },
  data() {
    return {
      // 打开后由后端填；接口还没回来时是空壳，不在这里放任何默认值
      autoConfig: toViewConfig({}),
      scanning: false,
      templates: [],
      discovered: [],
      records: [],
      onlyCurrentOlt: false,
      templateDialogVisible: false,
      editingTemplate: null,
      registerTemplateId: '',
      registerVisible: false,
      previewRows: [],
      previewCommands: [],
      previewTemplate: null,
      discovering: false,
      registering: false,
      /** 预览弹窗里等确认的那批参数，确认后才拿去下发 */
      pendingPayload: null,
      seq: 1,
      /** 注册方式 / 工作模式的显示名，来自后端 /olt/options */
      optionTables: null
    }
  },
  computed: {
    /** 码表：注册方式短名、光猫工作模式中文名 */
    codeTables() {
      return (this.optionTables && this.optionTables.codeTables) || {}
    },
    visibleTemplates() {
      return this.templates
    },
    checkedTemplates() {
      return this.templates.filter(item => item.checked)
    },
    checkedDiscovered() {
      return this.discovered.filter(item => item.checked)
    },
    allChecked() {
      return this.templates.length > 0 && this.templates.every(item => item.checked)
    },
    allDiscoveredChecked() {
      return this.discovered.length > 0 && this.discovered.every(item => item.checked)
    },
    previewName() {
      return this.previewTemplate ? this.previewTemplate.name : '-'
    },
    previewBindPorts() {
      return this.previewTemplate && this.previewTemplate.bindPorts ? this.previewTemplate.bindPorts : '不限'
    },
    previewModels() {
      return this.previewTemplate && this.previewTemplate.ontModels ? this.previewTemplate.ontModels : '不限'
    },
    previewWorkMode() {
      if (!this.previewTemplate) {
        return '-'
      }
      const mode = (this.codeTables.workModes || [])
        .find(item => item.code === this.previewTemplate.workMode)
      return mode ? mode.label : '-'
    }
  },
  watch: {
    oltId: {
      immediate: true,
      handler() {
        this.reload()
      }
    },
    // 勾上「只显示当前 OLT 模板」重新拉一次：让库来挑，不在前端比对
    onlyCurrentOlt() {
      this.reload()
    }
  },
  created() {
    fetchOltOptions()
      .then(res => {
        this.optionTables = res.data || null
      })
      .catch(() => {
        this.optionTables = null
      })
  },
  methods: {
    authLabel(mode) {
      const labels = this.codeTables.authModeLabels || {}
      return labels[mode] || '-'
    },
    /**
     * 从库里读模板、自动注册参数、注册记录。
     *
     * <p>模板原来存在浏览器本地，换台电脑打开就空了，后台定时任务也看不到——
     * 现在都走接口，页面和定时任务用的是同一份。</p>
     */
    async reload() {
      if (!this.oltId) {
        return
      }
      // 自动发现结果不落草稿：那是设备上实时的东西，缓存下来反而会显示已经注册掉的光猫
      this.discovered = []
      this.registerTemplateId = ''
      const [configResult, templateResult, recordResult] = await Promise.all([
        fetchOntRegConfig(this.oltId).catch(() => ({ data: null })),
        // 过滤交给后端：库里就有 olt_id，后端按「不限设备 + 这台设备」挑比前端比对 IP 准
        fetchOntRegTemplates(this.oltId, this.onlyCurrentOlt).catch(() => ({ data: [] })),
        fetchOntRegRecords(this.oltId, 50).catch(() => ({ data: [] }))
      ])
      // 后端没给数据时保留原值，不自己编一份默认值出来
      const view = toViewConfig(configResult.data)
      if (view) {
        this.autoConfig = view
      }
      this.templates = (templateResult.data || []).map(item => ({
        ...item,
        priority: item.priority == null ? 0 : Number(item.priority),
        enabled: item.enabled === 1,
        checked: false
      }))
      this.records = recordResult.data || []
      this.seq = this.templates.length + 1
    },
    // 「上次扫描 / 注册 / 存盘」的时间文本由后端随参数给（lastScanText 等），页面不再判今天
    /** 不等下一轮扫描，立刻按当前参数跑一轮（和后台任务同一条路径） */
    async runScanOnce() {
      if (!this.oltId || this.scanning) {
        return
      }
      this.scanning = true
      try {
        const result = await runOntRegScan(this.oltId)
        const count = result.data || 0
        this.$message.success(count > 0 ? '本轮注册成功 ' + count + ' 台' : '本轮没有注册成功的（看下面注册记录的原因）')
        await this.reload()
      } catch (e) {
        this.$message.error(e && e.message ? e.message : '扫描失败')
      } finally {
        this.scanning = false
      }
    },
    async saveAutoConfig() {
      if (!this.oltId) {
        return
      }
      try {
        const result = await saveOntRegConfig(this.oltId, toServerConfig(this.autoConfig))
        // 用后端存下的那份回填：填了越界的值后端会夹回，页面要显示真正生效的那个
        const saved = toViewConfig(result.data)
        if (saved) {
          this.autoConfig = saved
        }
        this.$message.success(this.autoConfig.enabled ? '自动注册参数已保存，后台开始按间隔扫描注册' : '自动注册参数已保存')
      } catch (e) {
        this.$message.error(e && e.message ? e.message : '保存失败')
      }
    },
    openCreate() {
      this.editingTemplate = null
      this.templateDialogVisible = true
    },
    openEdit(row) {
      this.editingTemplate = row
      this.templateDialogVisible = true
    },
    /**
     * 保存一条模板：交给后端入库，成功后按后端返回的列表重排。
     *
     * <p>不在本地拼 id：模板要被后台定时任务拿去匹配，本地造的 id 出了这个页面就没意义。</p>
     */
    async submitTemplate(form) {
      if (!this.oltId) {
        return
      }
      try {
        const result = await saveOntRegTemplate(this.oltId, {
          ...form,
          id: form.id || null,
          oltIp: form.oltIp || this.oltIp || null,
          priority: Number(form.priority || 0),
          enabled: form.enabled === false ? 0 : 1
        })
        this.$message.success('注册模板已保存')
        // 用后端返回的这条回填，id 是真的库里 id，后面编辑/删除才找得着
        const saved = result.data
        if (saved && saved.id) {
          const index = this.templates.findIndex(item => item.id === saved.id)
          const view = { ...saved, enabled: saved.enabled === 1, checked: false }
          if (index >= 0) {
            this.templates.splice(index, 1, view)
          } else {
            this.templates.push(view)
          }
        } else {
          await this.reload()
        }
        // 优先级小的排前面，和匹配顺序一致
        this.templates.sort((a, b) => Number(a.priority) - Number(b.priority))
      } catch (e) {
        this.$message.error(e && e.message ? e.message : '保存模板失败')
      }
    },
    /** 启用/停用：这条模板还留在库里，只是不再参与匹配 */
    async onToggleEnabled(row) {
      // 先在 await 前把目标值定下来：await 之后 row.enabled 可能已经被别的改动覆盖，
      // 回滚时不能用「取反」这种依赖当前值的写法
      const target = row.enabled
      try {
        await saveOntRegTemplate(this.oltId, {
          ...row,
          enabled: target ? 1 : 0,
          priority: Number(row.priority || 0)
        })
        this.$message.info(row.name + (target ? ' 已启用' : ' 已停用'))
      } catch (e) {
        // 保存失败就把开关拨回去：整行换掉而不是改 row.enabled，
        // 免得 await 期间这一行已经被别处改动过
        const index = this.templates.findIndex(item => item.id === row.id)
        if (index >= 0) {
          this.$set(this.templates, index, { ...row, enabled: !target })
        }
        this.$message.error(e && e.message ? e.message : '保存失败')
      }
    },
    toggleAll(value) {
      this.templates.forEach(item => { item.checked = value })
    },
    toggleChecked() {
      const value = !this.allChecked
      this.templates.forEach(item => { item.checked = value })
    },
    toggleAllDiscovered(value) {
      this.discovered.forEach(item => { item.checked = value })
    },
    removeChecked() {
      const ids = this.checkedTemplates.map(item => item.id)
      if (!ids.length) {
        return
      }
      this.$confirm('删除选中的 ' + ids.length + ' 条注册模板？删除后这些光猫上线就匹配不到模板了。', '删除', {
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        type: 'warning'
      })
        .then(async() => {
          try {
            await deleteOntRegTemplates(this.oltId, ids)
            this.templates = this.templates.filter(item => !ids.includes(item.id))
            this.$message.success('已删除')
          } catch (e) {
            this.$message.error(e && e.message ? e.message : '删除失败')
          }
        })
        .catch(() => {})
    },
    /**
     * 采设备上的待注册光猫：实时登 OLT 跑 display ont autofind all。
     *
     * <p>这是设备上这一瞬间的状态，一次次查的结果可能不一样（光猫注册完就从表里消失），
     * 所以不做缓存、也不写草稿，每次点都真去采。</p>
     */
    async discover() {
      if (!this.oltId) {
        return
      }
      this.discovering = true
      try {
        const result = await fetchOntAutofind(this.oltId)
        const list = result.data || []
        this.discovered = list.map(item => ({
          id: item.id,
          port: item.fsp || '-',
          ponType: item.ponType || '',
          sn: item.sn || '-',
          equipmentId: item.equipmentId || '-',
          loid: item.loid || '-',
          password: item.password || '-',
          checkCode: item.checkCode || '-',
          vendorId: item.vendorId || '-',
          model: item.model || '-',
          hardwareVersion: item.hardwareVersion || '-',
          softwareVersion: item.softwareVersion || '-',
          foundAt: item.lastTime || '-',
          checked: false
        }))
        if (!this.discovered.length) {
          this.$message.info('设备上当前没有待注册光猫')
        } else {
          this.$message.success('采到 ' + this.discovered.length + ' 个待注册光猫')
        }
      } catch (e) {
        // 报错多半是设备不认命令或者解析失败，把原因直接摆出来比空列表好排查
        this.$message.error(e && e.message ? e.message : '采集失败')
      } finally {
        this.discovering = false
      }
    },
    /**
     * 一键注册：先让后端把命令编出来给人看，确认了才真下发。
     *
     * <p>命令由后端按模板编排（{@code ont confirm}），页面不自己拼命令——
     * 命令写法是设备知识，写在前端迟早跟设备版本对不上。</p>
     *
     * <p>下发逐台独立：一台失败（SN 重复、模板套不上）不影响其它台，
     * 结果按台摊开显示。</p>
     */
    async registerChecked() {
      const template = this.templates.find(item => item.id === this.registerTemplateId)
      if (!template) {
        this.$message.warning('先选一条注册模版')
        return
      }
      if (!this.checkedDiscovered.length) {
        this.$message.warning('先勾选要注册的光猫')
        return
      }
      const rows = this.checkedDiscovered.map(item => ({
        id: item.id,
        fsp: item.port === '-' ? null : item.port,
        sn: item.sn === '-' ? null : item.sn,
        loid: item.loid === '-' ? null : item.loid,
        password: item.password === '-' ? null : item.password,
        model: item.model === '-' ? null : item.model,
        vendorId: item.vendorId === '-' ? null : item.vendorId,
        ponType: item.ponType || 'gpon'
      }))
      this.previewTemplate = template
      this.previewRows = this.checkedDiscovered.map(item => ({
        port: item.port,
        sn: item.sn,
        model: item.model,
        authLabel: this.authLabel(template.authMode)
      }))
      const payload = { templateId: template.id, rows }
      try {
        const result = await previewOntRegister(this.oltId, payload)
        this.previewCommands = result.data || []
      } catch (e) {
        this.previewCommands = []
        this.$message.error(e && e.message ? e.message : '生成命令失败')
      }
      this.pendingPayload = payload
      this.registerVisible = true
    },
    /** 确认下发：把勾中的行连模板一起交给后端，逐台注册 */
    async confirmRegister() {
      if (!this.pendingPayload) {
        this.registerVisible = false
        return
      }
      this.registering = true
      try {
        const result = await registerOnt(this.oltId, this.pendingPayload)
        const list = result.data || []
        const ok = list.filter(item => item.success).length
        const failed = list.length - ok
        if (failed === 0) {
          this.$message.success('已注册 ' + ok + ' 台')
        } else {
          // 逐台结果摊开：哪台没成、因为什么，比一句「部分失败」有用
          const reasons = list.filter(item => !item.success)
            .map(item => (item.sn || item.fsp) + '：' + (item.message || '失败'))
          this.$message({
            type: 'warning',
            message: '成功 ' + ok + ' 台，失败 ' + failed + ' 台：' + reasons.join('；'),
            duration: 8000
          })
        }
        this.registerVisible = false
        this.pendingPayload = null
        await this.reload()
        await this.discover()
      } catch (e) {
        this.$message.error(e && e.message ? e.message : '注册失败')
      } finally {
        this.registering = false
      }
    }
  }
}
</script>

<style lang="scss" scoped>
@import '~@/styles/olt.scss';

.ont-reg {
  padding-top: 4px;
}

.reg-block-title {
  margin: 14px 0 6px;
  color: #4a5b6c;
  font-size: 13px;
  font-weight: 600;
}

.reg-block-extra {
  margin-left: 12px;
  color: #9aa7b4;
  font-size: 12px;
  font-weight: 400;
}

/* 注册确认：把「还没接下发」和「这次要做什么」都说清楚 */
.reg-preview-tip {
  padding: 6px 10px;
  margin-bottom: 8px;
  color: #8a6d3b;
  font-size: 12px;
  line-height: 1.6;
  background: #fcf8e3;
  border-left: 3px solid #f0c36d;
}

.reg-preview-block {
  margin-top: 10px;
  color: #5a6b7d;
  font-size: 12px;
  line-height: 1.8;
}

/* 参数区沿用截图那种紧凑下划线表单 */
.reg-auto-status {
  display: flex;
  gap: 18px;
  margin-bottom: 8px;
  color: #909399;
  font-size: 12px;

  b {
    color: #4a5b6c;
    font-weight: 600;
  }
}

.reg-auto-form {
  max-width: 560px;

  ::v-deep .el-form-item {
    margin-bottom: 0;
    padding: 2px 0;
    border-bottom: 1px dashed #ebeef5;
  }

  ::v-deep .el-form-item__label {
    color: #909399;
    font-weight: 400;
    line-height: 26px;
    white-space: nowrap;
  }

  ::v-deep .el-form-item__label .help-label {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    line-height: 26px;
  }

  ::v-deep .el-input__inner {
    border: none;
    border-bottom: 1px solid #dcdfe6;
    border-radius: 0;
    padding-left: 0;
  }
}

.reg-unit {
  margin-left: 6px;
  color: #9aa7b4;
  font-size: 12px;
}

/* 下发前把命令原样摆出来：命令是后端按模板编的，现场要能一眼核对 */
.reg-preview-title {
  margin-bottom: 4px;
  color: #4a5b6c;
  font-weight: 600;
}

.reg-command-preview {
  max-height: 180px;
  margin: 0;
  padding: 8px 10px;
  overflow: auto;
  color: #476582;
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  line-height: 1.7;
  white-space: pre;
  background: #f6f8fa;
  border: 1px solid #ebeef5;
  border-radius: 3px;
}

.reg-record-message {
  margin-left: 6px;
  color: #6b7a8c;
  font-size: 12px;
}

.reg-table {
  ::v-deep td {
    padding: 4px 0;
  }
}

.cell-main {
  color: #2c3e50;
  font-size: 12px;
  line-height: 1.5;
}

.cell-sub {
  color: #9aa7b4;
  font-size: 12px;
  line-height: 1.5;
}

.reg-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 6px 0 4px;
}

.reg-toolbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.reg-template-select {
  width: 160px;
}
</style>
