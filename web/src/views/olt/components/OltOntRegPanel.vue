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
        <el-input-number v-model="autoConfig.scanSeconds" :min="5" :max="3600" :step="5" controls-position="right" size="small" />
        <span class="reg-unit">秒</span>
      </el-form-item>

      <el-form-item>
        <olt-help-label
          slot="label"
          text="新光猫注册后自动保存时间"
          tip="授权成功后隔多久把配置存盘。华为只写运行配置，不 save 掉电就回到注册前。"
        />
        <el-input-number v-model="autoConfig.saveSeconds" :min="60" :max="86400" :step="60" controls-position="right" size="small" />
        <span class="reg-unit">秒</span>
      </el-form-item>

      <el-form-item>
        <olt-help-label
          slot="label"
          text="并发注册光猫数量"
          tip="一轮里最多同时授权几个。并发大了会把 OLT 的 CLI 会话占满，反而拖慢注册。"
        />
        <el-input-number v-model="autoConfig.concurrency" :min="1" :max="32" controls-position="right" size="small" />
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

    <!-- 一键注册确认：命令由后端编，这里先把要做什么摊开确认 -->
    <el-dialog :visible.sync="registerVisible" title="一键注册" width="620px" append-to-body>
      <div class="reg-preview-tip">
        命令序列由后端模板编排器生成，接口还没接，本次不会下发到设备。
      </div>

      <el-table :data="previewRows" size="mini" border class="reg-table">
        <el-table-column prop="port" label="端口" width="90" />
        <el-table-column prop="sn" label="ONT SN" min-width="170" />
        <el-table-column prop="model" label="型号" width="110" />
        <el-table-column prop="authLabel" label="注册方式" width="90" />
      </el-table>

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
        <el-button size="small" type="primary" @click="registerVisible = false">确认</el-button>
        <el-button size="small" @click="registerVisible = false">取消</el-button>
      </div>
    </el-dialog>

    <!-- 新建/编辑模板 -->
    <olt-ont-reg-template-dialog
      :visible.sync="templateDialogVisible"
      :template="editingTemplate"
      :olt-ip="oltIp"
      @submit="submitTemplate"
    />
  </div>
</template>

<script>
import OltHelpLabel from './OltHelpLabel'
import OltOntRegTemplateDialog from './OltOntRegTemplateDialog'
import { fetchOntAutofind } from '@/api/olt'

/**
 * ONT 注册 / 自动注册模板。
 *
 * <p>三块：自动注册参数、注册模板（按优先级匹配）、自动发现（设备上待授权的光猫）。</p>
 *
 * <p><b>当前状态：模板和参数存在本机（按 OLT 记），刷新页面还在，但没有写库。</b>
 * 后端接口接上后把 {@link #persist} / {@link #discover} / {@link #registerChecked}
 * 三个方法换成真实调用即可，页面结构不用动。</p>
 */

const STORE_PREFIX = 'olt-ont-reg:'

function defaultAutoConfig() {
  return {
    enabled: false,
    scanSeconds: 30,
    saveSeconds: 28800,
    concurrency: 1,
    autoMove: false,
    autoDeleteOffline: false
  }
}

function loadState(oltId) {
  if (!oltId) {
    return null
  }
  try {
    const raw = window.localStorage.getItem(STORE_PREFIX + oltId)
    return raw ? JSON.parse(raw) : null
  } catch (e) {
    return null
  }
}

const AUTH_LABELS = {
  SN: 'SN',
  MAC: 'MAC',
  LOID: 'LOID',
  PASSWORD: 'Password'
}

/** 光猫工作模式的显示名，和模板弹窗里的选项一一对应 */
const WORK_MODE_LABELS = {
  HGU: 'HGU/网关型',
  SFU: 'SFU/桥接型（不带路由功能）',
  MIX: 'HGU + SFU 兼容/混合型',
  MDU: 'ONU/外置型光猫（MDU）'
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
      autoConfig: defaultAutoConfig(),
      templates: [],
      discovered: [],
      onlyCurrentOlt: false,
      templateDialogVisible: false,
      editingTemplate: null,
      registerTemplateId: '',
      registerVisible: false,
      previewRows: [],
      previewTemplate: null,
      discovering: false,
      seq: 1
    }
  },
  computed: {
    visibleTemplates() {
      if (!this.onlyCurrentOlt) {
        return this.templates
      }
      return this.templates.filter(item => !item.oltIp || item.oltIp === this.oltIp)
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
      return WORK_MODE_LABELS[this.previewTemplate.workMode] || '-'
    }
  },
  watch: {
    oltId: {
      immediate: true,
      handler() {
        this.reload()
      }
    }
  },
  methods: {
    authLabel(mode) {
      return AUTH_LABELS[mode] || '-'
    },
    reload() {
      const state = loadState(this.oltId)
      this.autoConfig = state && state.autoConfig
        ? { ...defaultAutoConfig(), ...state.autoConfig }
        : defaultAutoConfig()
      this.templates = state && Array.isArray(state.templates)
        ? state.templates.map(item => ({ ...item, checked: false }))
        : []
      // 自动发现结果不落草稿：那是设备上实时的东西，缓存下来反而会显示已经注册掉的光猫
      this.discovered = []
      this.seq = this.templates.length + 1
    },
    persist() {
      if (!this.oltId) {
        return
      }
      try {
        const payload = {
          autoConfig: this.autoConfig,
          templates: this.templates.map(({ checked, ...rest }) => rest)
        }
        window.localStorage.setItem(STORE_PREFIX + this.oltId, JSON.stringify(payload))
      } catch (e) {
        // 隐私模式下写不进去，不影响页面操作
      }
    },
    saveAutoConfig() {
      this.persist()
      this.$message.success('自动注册参数已保存')
    },
    openCreate() {
      this.editingTemplate = null
      this.templateDialogVisible = true
    },
    openEdit(row) {
      this.editingTemplate = row
      this.templateDialogVisible = true
    },
    submitTemplate(form) {
      if (form.id) {
        const index = this.templates.findIndex(item => item.id === form.id)
        if (index >= 0) {
          this.templates.splice(index, 1, { ...form, checked: this.templates[index].checked })
        }
      } else {
        this.templates.push({ ...form, id: 't' + this.seq++, enabled: true, checked: false })
      }
      // 优先级小的排前面，和匹配顺序一致
      this.templates.sort((a, b) => Number(a.priority) - Number(b.priority))
      this.persist()
      this.$message.success('注册模板已保存')
    },
    onToggleEnabled(row) {
      this.persist()
      this.$message.info(row.name + (row.enabled ? ' 已启用' : ' 已停用'))
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
      this.$confirm('删除选中的 ' + ids.length + ' 条注册模板？', '删除', {
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        type: 'warning'
      })
        .then(() => {
          this.templates = this.templates.filter(item => !ids.includes(item.id))
          this.persist()
          this.$message.success('已删除')
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
     * 待接后端：真下发时是把勾中的行连模板一起交给后端，
     * 由那边编 ont confirm / ont add 的命令序列。这里先把内容摊开确认。
     */
    registerChecked() {
      const template = this.templates.find(item => item.id === this.registerTemplateId)
      if (!template) {
        this.$message.warning('先选一条注册模版')
        return
      }
      if (!this.checkedDiscovered.length) {
        this.$message.warning('先勾选要注册的光猫')
        return
      }
      this.previewTemplate = template
      this.previewRows = this.checkedDiscovered.map(item => ({
        port: item.port,
        sn: item.sn,
        model: item.model,
        authLabel: this.authLabel(template.authMode)
      }))
      this.registerVisible = true
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
