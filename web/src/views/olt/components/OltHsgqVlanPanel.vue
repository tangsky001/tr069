<template>
  <div>
    <div class="olt-section-title">
      VLAN 列表
      <span class="vlan-tip-inline">
        采集自 <code>show running-config</code>；成员端口取 vlan 视图里的
        <code>port epon/ge &lt;端口&gt; tagged|untagged</code>
      </span>
    </div>

    <el-table
      v-if="vlans.length"
      ref="table"
      :data="vlans"
      size="small"
      border
      @selection-change="rows => selected = rows"
    >
      <el-table-column type="selection" width="46" align="center" />
      <el-table-column label="VLAN ID" prop="vlanId" width="100" align="center" />
      <el-table-column label="Tagged 端口" min-width="170">
        <template slot-scope="{ row }">
          <span v-if="row.taggedPorts" class="vlan-text">{{ row.taggedPorts }}</span>
          <span v-else class="vlan-tip-inline">无</span>
        </template>
      </el-table-column>
      <el-table-column label="Untagged 端口" min-width="170">
        <template slot-scope="{ row }">
          <span v-if="row.untaggedPorts" class="vlan-text">{{ row.untaggedPorts }}</span>
          <span v-else class="vlan-tip-inline">无</span>
        </template>
      </el-table-column>
      <el-table-column label="缺省 VLAN 端口" min-width="170">
        <template slot-scope="{ row }">
          <span v-if="row.defaultVlanPorts" class="vlan-text">{{ row.defaultVlanPorts }}</span>
          <span v-else class="vlan-tip-inline">无</span>
        </template>
      </el-table-column>
    </el-table>
    <div v-else class="olt-empty">{{ emptyText }}</div>

    <div class="vlan-footer">
      <el-button type="text" icon="el-icon-plus" class="vlan-link" @click="openCreate">新增 VLAN</el-button>
      <span class="vlan-footer-right">
        <el-button type="text" class="vlan-link" @click="toggleAllRows(true)">全选</el-button>
        <el-button type="text" class="vlan-link" @click="toggleAllRows(false)">全不选</el-button>
        <el-button
          type="text"
          icon="el-icon-delete"
          class="vlan-row-danger"
          :disabled="!selected.length"
          @click="removeSelected"
        />
      </span>
    </div>

    <!--
      新建 VLAN：填 VLAN ID + 勾 tagged / untagged 端口。
      下发顺序是 vlan standard 100 → port epon 1,2 tagged → exit，一个 VLAN 一轮。
    -->
    <el-dialog
      title="创建"
      :visible.sync="dialogVisible"
      width="660px"
      :close-on-click-modal="false"
      append-to-body
    >
      <div class="vlan-form">
        <div class="vlan-form-row">
          <label class="vlan-form-label">VLAN ID:</label>
          <div class="vlan-form-body">
            <el-input
              v-model="form.vlanIds"
              size="small"
              class="vlan-id-input"
              placeholder="100 或 100-200,300"
              @keyup.enter.native="save"
            />
            <div class="vlan-form-hint">请输入1到4094之间的数字,可以使用,或-连接</div>
          </div>
        </div>

        <div v-if="!memberPorts.length" class="vlan-form-row">
          <label class="vlan-form-label" />
          <div class="vlan-form-body">
            <div class="vlan-form-hint vlan-form-warn">
              端口清单还没采到（同步一次就有），现在只能建 VLAN，勾不了端口。
            </div>
          </div>
        </div>

        <div v-for="mode in modes" :key="mode.key" class="vlan-form-row">
          <label class="vlan-form-label">{{ mode.key }}:</label>
          <div class="vlan-form-body">
            <div v-if="memberPorts.length" class="vlan-port-all">
              <el-checkbox
                :value="isAllPicked(mode.key)"
                :indeterminate="isPartPicked(mode.key)"
                @change="checked => togglePorts(mode.key, checked)"
              >
                全部
              </el-checkbox>
            </div>
            <el-checkbox-group
              v-model="form[mode.key]"
              class="vlan-port-grid"
              @change="() => exclusive(mode.key)"
            >
              <template v-for="group in portGroups">
                <el-checkbox
                  v-for="port in group.ports"
                  :key="group.key + port.no"
                  :label="port.type + port.no"
                >
                  {{ port.label }}
                </el-checkbox>
              </template>
            </el-checkbox-group>
          </div>
        </div>
      </div>

      <div slot="footer" class="vlan-dialog-foot">
        <el-button size="small" @click="dialogVisible = false">取消</el-button>
        <el-button size="small" type="success" :loading="saving" @click="save">确认</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
/**
 * 鸿升光 VLAN 面板：VLAN 列表（含成员端口）+ 新增 / 删除。
 *
 * <p>这台的 VLAN 概念和华为完全不是一路：一条 {@code vlan standard 100} 建好 VLAN，
 * 再进 vlan 视图 {@code port epon 2 untagged} 把端口加进去，删除是 {@code no vlan 100}。
 * 没有 vlanif 三层接口、静态路由那一套，所以不复用通用的四表面板。</p>
 *
 * <p>创建弹窗按「VLAN ID + 勾 tagged / untagged 端口」填，勾选项来自设备采集的端口清单
 * （{@code show port state all}），勾不到的口不会发出去。GPON 那台 PON 口不做二层成员，
 * 清单里只有上联口。</p>
 */
export default {
  name: 'OltHsgqVlanPanel',
  props: {
    vlans: {
      type: Array,
      default: () => []
    },
    /** 可以做 VLAN 成员的端口（来自设备采集）：[{ type: 'epon', no: 1, label: 'PON01' }] */
    memberPorts: {
      type: Array,
      default: () => []
    },
    /** 新增 VLAN（{ vlanIds, tagged, untagged }），返回 Promise */
    createHandler: {
      type: Function,
      default: null
    },
    /** 删除 VLAN（ID 数组），返回 Promise */
    deleteHandler: {
      type: Function,
      default: null
    },
    emptyText: {
      type: String,
      default: '尚未采集到 VLAN。点上面的「同步」采一次（采集时跑 show running-config），也可以直接点下面「新增 VLAN」下发。'
    }
  },
  data() {
    return {
      selected: [],
      dialogVisible: false,
      saving: false,
      modes: [
        { key: 'tagged' },
        { key: 'untagged' }
      ],
      form: { vlanIds: '', tagged: [], untagged: [] }
    }
  },
  computed: {
    /** 端口按类型分组：PON 一组、GE 一组、XGE 一组，每组每行摆 4 个 */
    portGroups() {
      const order = ['epon', 'gpon', 'ge', 'xge']
      const groups = new Map()
      this.memberPorts.forEach(port => {
        if (!port || !port.type) {
          return
        }
        const key = String(port.type).toLowerCase()
        if (!groups.has(key)) {
          groups.set(key, { key, ports: [] })
        }
        groups.get(key).ports.push(port)
      })
      return [...groups.values()].sort((a, b) => order.indexOf(a.key) - order.indexOf(b.key))
    },
    allPortKeys() {
      return this.memberPorts
        .filter(port => port && port.type && port.no != null)
        .map(port => String(port.type).toLowerCase() + port.no)
    }
  },
  methods: {
    openCreate() {
      this.form = { vlanIds: '', tagged: [], untagged: [] }
      this.dialogVisible = true
    },
    isAllPicked(mode) {
      return this.allPortKeys.length > 0 && this.allPortKeys.every(key => this.form[mode].includes(key))
    },
    isPartPicked(mode) {
      const picked = this.form[mode].length
      return picked > 0 && !this.isAllPicked(mode)
    },
    togglePorts(mode, checked) {
      this.form[mode] = checked ? [...this.allPortKeys] : []
      this.exclusive(mode)
    },
    /** tagged 和 untagged 互斥：同一个口不能两种都占，勾一边就把另一边摘掉 */
    exclusive(mode) {
      const other = mode === 'tagged' ? 'untagged' : 'tagged'
      const picked = new Set(this.form[mode])
      this.form[other] = this.form[other].filter(key => !picked.has(key))
    },
    /**
     * 校验并下发。
     *
     * <p>VLAN ID 在本地先解析一遍：写错了立刻提示，不用等设备回一句报错。</p>
     */
    save() {
      const text = (this.form.vlanIds || '').trim()
      if (!text) {
        this.$message.warning('请填写 VLAN ID，例如 100 或 100-200,300')
        return
      }
      let ids
      try {
        ids = this.parseVlanIds(text)
      } catch (e) {
        this.$message.warning(e.message)
        return
      }
      if (!this.createHandler) {
        return
      }
      const tagged = this.form.tagged
      const untagged = this.form.untagged
      const withPorts = tagged.length > 0 || untagged.length > 0
      const limit = withPorts ? 16 : 64
      if (ids.length > limit) {
        this.$message.warning(
          withPorts
            ? `一次最多给 16 个 VLAN 配端口（每个 VLAN 都要单独进视图），这次填了 ${ids.length} 个`
            : `一次最多建 64 个 VLAN，这次填了 ${ids.length} 个`
        )
        return
      }
      this.saving = true
      this.createHandler({ vlanIds: text, tagged, untagged })
        .then(() => {
          this.dialogVisible = false
        })
        .finally(() => {
          this.saving = false
        })
    },
    /** 与服务端同一套规则：逗号分隔、短横线表示范围、1-4094、范围前小后大 */
    parseVlanIds(text) {
      const ids = []
      const seen = new Set()
      text.split(/[,，]/).forEach(part => {
        const piece = part.trim()
        if (!piece) {
          return
        }
        const dash = piece.indexOf('-')
        const from = dash < 0 ? piece : piece.slice(0, dash).trim()
        const to = dash < 0 ? piece : piece.slice(dash + 1).trim()
        const start = this.toVlanId(from)
        const end = this.toVlanId(to)
        if (end < start) {
          throw new Error(`VLAN 范围 ${piece} 前后写反了，应该是 小-大`)
        }
        for (let id = start; id <= end; id++) {
          if (!seen.has(id)) {
            seen.add(id)
            ids.push(id)
          }
        }
      })
      if (!ids.length) {
        throw new Error('请填写 VLAN ID，例如 100 或 100-200,300')
      }
      return ids
    },
    toVlanId(piece) {
      if (!/^\d+$/.test(piece)) {
        throw new Error('VLAN ID 只能是数字：' + piece)
      }
      const id = Number(piece)
      if (id < 1 || id > 4094) {
        throw new Error('VLAN ID 必须在 1-4094 之间：' + id)
      }
      return id
    },
    removeSelected() {
      const vlanIds = this.selected.map(row => row.vlanId).filter(id => id != null)
      if (!vlanIds.length || !this.deleteHandler) {
        return
      }
      this.$confirm(
        '删除 VLAN ' + vlanIds.join('、') + '？该 VLAN 上的端口成员配置会一起删掉，设备立即生效。',
        '删除 VLAN',
        { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
      )
        .then(() => this.deleteHandler(vlanIds))
        .then(() => {
          this.selected = []
        })
        .catch(() => {})
    },
    toggleAllRows(flag) {
      const table = this.$refs.table
      if (!table) {
        return
      }
      table.clearSelection()
      if (flag) {
        this.vlans.forEach(row => table.toggleRowSelection(row, true))
      }
    }
  }
}
</script>

<style lang="scss" scoped>
@import '~@/styles/olt.scss';

.vlan-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 2px 0;
}
.vlan-footer-right {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.vlan-link {
  padding: 0 4px;
}
.vlan-id-input {
  width: 260px;
}
.vlan-form-row {
  display: flex;
  align-items: flex-start;
  margin-bottom: 10px;
}
.vlan-form-label {
  flex: 0 0 78px;
  padding-top: 7px;
  color: #303133;
  text-align: left;
}
.vlan-form-body {
  flex: 1;
  min-width: 0;
}
.vlan-form-hint {
  margin-top: 4px;
  color: #7f8a99;
  font-size: 12px;
  line-height: 1.5;
}
.vlan-form-warn {
  color: #e6a23c;
}
.vlan-port-all {
  margin-bottom: 2px;
}
/* 每行 4 个口，PON 与 GE 各占一片，8 个 GE 正好两行 */
.vlan-port-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(96px, 1fr));
  gap: 2px 8px;
}
.vlan-port-grid ::v-deep .el-checkbox {
  margin-right: 0;
}
.vlan-dialog-foot {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 24px;
}
</style>
