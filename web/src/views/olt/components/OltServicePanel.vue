<template>
  <div>
    <div v-if="!srvProfiles.length && !trafficTables.length && !servicePorts.length" class="olt-empty">
      {{ emptyText }}
    </div>

    <template v-else>
      <!-- 1. ONT 业务模板（服务模板） -->
      <div class="svc-title">
        ONT 业务模板（服务模板）
        <el-tooltip content="display ont-srvprofile gpon all 采集；网口/电话口数量是 ONT 能力自动协商的，页面给的是模板里声明的上限" placement="top">
          <i class="el-icon-info svc-info" />
        </el-tooltip>
      </div>
      <el-table
        v-if="srvProfiles.length"
        :data="srvProfiles"
        size="small"
        border
        @selection-change="rows => svcSelected = rows"
      >
        <el-table-column type="selection" width="46" align="center" />
        <el-table-column label="模板ID" prop="profileId" width="100" align="center" />
        <el-table-column label="模板名" prop="name" min-width="150" />
        <el-table-column label="PON 类型" width="100" align="center">
          <template slot-scope="{ row }">{{ row.ponType || 'gpon' }}</template>
        </el-table-column>
        <el-table-column label="网口数量" min-width="130">
          <template slot-scope="{ row }">{{ portCount(row, 'eth') }}</template>
        </el-table-column>
        <el-table-column label="电话口数量" min-width="130">
          <template slot-scope="{ row }">{{ portCount(row, 'pots') }}</template>
        </el-table-column>
        <el-table-column label="VLAN" width="90" align="center">
          <template>-</template>
        </el-table-column>
      </el-table>
      <div v-else class="vlan-empty-row">没有 ONT 业务模板</div>
      <div v-if="srvProfiles.length" class="svc-footer">
        <el-button type="text" icon="el-icon-plus" class="svc-link" @click="notSupported('新增业务模板')">新增业务模板</el-button>
        <span class="svc-footer-right">
          <el-button type="text" class="svc-link" @click="toggleAll('svcTable', true)">全选</el-button>
          <el-button type="text" class="svc-link" @click="toggleAll('svcTable', false)">全不选</el-button>
          <el-button
            type="text"
            icon="el-icon-delete"
            class="svc-danger"
            :disabled="!svcSelected.length"
            @click="notSupported('删除业务模板')"
          />
        </span>
      </div>

      <!-- 2. TR069 管理模板：OLT 上是独立的一类模板（ont tr069-server-profile） -->
      <div class="svc-title svc-title-line">
        TR069 管理模板
        <el-tooltip content="display ont tr069-server-profile all 采集；ACS 地址与登录账号决定 ONT 上哪台 ACS 注册" placement="top">
          <i class="el-icon-info svc-info" />
        </el-tooltip>
      </div>
      <el-table
        v-if="tr069Rows.length"
        :data="tr069Rows"
        size="small"
        border
        @selection-change="rows => tr069Selected = rows"
      >
        <el-table-column type="selection" width="46" align="center" />
        <el-table-column label="模板ID" prop="profileId" width="100" align="center" />
        <el-table-column label="模板名" min-width="150">
          <template slot-scope="{ row }">{{ row.name || '-' }}</template>
        </el-table-column>
        <el-table-column label="ACS 地址" min-width="220">
          <template slot-scope="{ row }">{{ row.url || '-' }}</template>
        </el-table-column>
        <el-table-column label="ACS 登录账号" min-width="160">
          <template slot-scope="{ row }">{{ row.user || '-' }}</template>
        </el-table-column>
      </el-table>
      <div v-else class="vlan-empty-row">
        没有 TR069 服务器模板（display ont tr069-server-profile all 无回显或版本不支持）
      </div>
      <div v-if="tr069Profiles.length" class="svc-footer">
        <el-button type="text" icon="el-icon-plus" class="svc-link" @click="notSupported('新增TR069模板')">新增TR069模板</el-button>
        <span class="svc-footer-right">
          <el-button type="text" class="svc-link" @click="toggleAll('tr069Table', true)">全选</el-button>
          <el-button type="text" class="svc-link" @click="toggleAll('tr069Table', false)">全不选</el-button>
          <el-button
            type="text"
            icon="el-icon-delete"
            class="svc-danger"
            :disabled="!tr069Selected.length"
            @click="notSupported('删除TR069模板')"
          />
        </span>
      </div>

      <!-- 3. 流量限速模板 -->
      <div class="svc-title svc-title-line">
        流量限速模板
        <el-tooltip content="display traffic table ip all 采集；CIR 承诺速率 / PIR 峰值速率，业务流引用它限速" placement="top">
          <i class="el-icon-info svc-info" />
        </el-tooltip>
      </div>
      <el-table
        v-if="trafficTables.length"
        :data="trafficTables"
        size="small"
        border
        @selection-change="rows => trafficSelected = rows"
      >
        <el-table-column type="selection" width="46" align="center" />
        <el-table-column label="模板ID" prop="index" width="100" align="center" />
        <el-table-column label="模板名" prop="name" min-width="180" />
        <el-table-column label="承诺速率 kbps" width="140" align="center">
          <template slot-scope="{ row }">{{ row.cir == null ? '-' : row.cir }}</template>
        </el-table-column>
        <el-table-column label="峰值速率 kbps" width="140" align="center">
          <template slot-scope="{ row }">{{ row.pir == null ? '-' : row.pir }}</template>
        </el-table-column>
      </el-table>
      <div v-else class="vlan-empty-row">没有流量限速模板（display traffic table ip all 无回显或版本不支持）</div>
      <div v-if="trafficTables.length" class="svc-footer">
        <span />
        <span class="svc-footer-right">
          <el-button type="text" class="svc-link" @click="toggleAll('trafficTable', true)">全选</el-button>
          <el-button type="text" class="svc-link" @click="toggleAll('trafficTable', false)">全不选</el-button>
          <el-button
            type="text"
            icon="el-icon-delete"
            class="svc-danger"
            :disabled="!trafficSelected.length"
            @click="notSupported('删除流量模板')"
          />
        </span>
      </div>

      <!-- 业务流明细：服务模板没有直接给，这里按 service-port 单独列出 -->
      <template v-if="servicePorts.length">
        <div class="svc-title svc-title-line">
          业务流明细（display service-port all）
          <el-tooltip content="一条流就是「某个 PON 口的某台 ONT，VLAN 走哪个 GEM port 上行」" placement="top">
            <i class="el-icon-info svc-info" />
          </el-tooltip>
        </div>
        <el-table :data="servicePorts" size="small" border>
          <el-table-column label="#索引" prop="index" width="80" align="center" />
          <el-table-column label="ONT ID" width="90" align="center">
            <template slot-scope="{ row }">{{ row.ontId == null ? '-' : row.ontId }}</template>
          </el-table-column>
          <el-table-column label="用户侧 VLAN" width="110" align="center">
            <template slot-scope="{ row }">{{ row.vlanId == null ? '-' : row.vlanId }}</template>
          </el-table-column>
          <el-table-column label="VLAN 属性" prop="vlanAttr" width="100" align="center" />
          <el-table-column label="GEM Port" width="100" align="center">
            <template slot-scope="{ row }">{{ row.gemport == null ? '-' : row.gemport }}</template>
          </el-table-column>
          <el-table-column label="端口 (F/S/P)" width="120" align="center">
            <template slot-scope="{ row }">{{ row.iface || '-' }}</template>
          </el-table-column>
        </el-table>
      </template>
    </template>
  </div>
</template>

<script>
/**
 * 业务 / TR069 / 流量模板面板（对齐网管的三张表）。
 *
 * ONT 业务模板来自 display ont-srvprofile gpon all（模板 ID、名称、网口/电话口上限）；
 * TR069 管理模板是里面带 TR069/ACS 参数的那几条，ACS 地址与账号从参数里挑出来；
 * 流量限速模板来自 display traffic table ip all（CIR 承诺 / PIR 峰值）。
 * 「新增 / 删除」是下发动作的占位，下发能力稍后接入。
 */
export default {
  name: 'OltServicePanel',
  props: {
    /** display ont-srvprofile gpon all 解析结果 */
    srvProfiles: {
      type: Array,
      default: () => []
    },
    /** display ont tr069-server-profile all 解析结果 */
    tr069Profiles: {
      type: Array,
      default: () => []
    },
    /** display traffic table ip all 解析结果 */
    trafficTables: {
      type: Array,
      default: () => []
    },
    /** display service-port all 解析结果，业务流明细 */
    servicePorts: {
      type: Array,
      default: () => []
    },
    emptyText: {
      type: String,
      default: '尚未采集到模板。同步时会跑 display ont-srvprofile gpon all、display traffic table ip all 与 display service-port all。'
    }
  },
  data() {
    return {
      svcSelected: [],
      tr069Selected: [],
      trafficSelected: []
    }
  },
  computed: {
    /**
     * TR069 管理模板以 ont tr069-server-profile 为准；
     * 老版本没有这条命令时，退回能力模板里带 TR069/ACS 参数的那几条（字段尽力填）。
     */
    tr069Rows() {
      if (this.tr069Profiles.length) {
        return this.tr069Profiles
      }
      return this.srvProfiles
        .filter(profile => Object.keys(profile.tr069 || {}).length > 0)
        .map(profile => ({
          profileId: profile.profileId,
          name: profile.name,
          url: this.acsUrl(profile),
          user: this.acsAccount(profile)
        }))
    }
  },
  methods: {
    /**
     * 网口 / 电话口数量：模板里 ont-port pots/eth adaptive N 给的就是上限，
     * 直接显示「自适应,最大 N」；没解析到（能力协商、回显没给）显示「自适应」。
     */
    portCount(profile, keyword) {
      if (keyword === 'pots' && profile.potsPorts != null) {
        return '自适应,最大 ' + profile.potsPorts
      }
      if (keyword === 'eth' && profile.ethPorts != null) {
        return '自适应,最大 ' + profile.ethPorts
      }
      let found = null
      const pattern = new RegExp(keyword, 'i')
      Object.keys(profile.items || {}).some(key => {
        if (!pattern.test(key)) {
          return false
        }
        const match = String(profile.items[key]).match(/\d+/)
        if (match) {
          found = Number(match[0])
          return true
        }
        return false
      })
      return found == null ? '自适应' : '自适应,最大 ' + found
    },
    acsUrl(profile) {
      return this.findTr069Value(profile, /url|地址/i)
    },
    acsAccount(profile) {
      return this.findTr069Value(profile, /账号|user/i)
    },
    findTr069Value(profile, keyword) {
      const tr069 = profile.tr069 || {}
      const key = Object.keys(tr069).find(name => keyword.test(name))
      return key ? tr069[key] : ''
    },
    toggleAll(refName, all) {
      const table = this.$refs[refName]
      if (!table) return
      if (all) {
        table.toggleAllSelection()
      } else {
        table.clearSelection()
      }
    },
    notSupported(label) {
      this.$message.info(label + '需要向 OLT 下发配置，下发能力稍后接入，当前仅展示')
    }
  }
}
</script>

<style lang="scss" scoped>
@import '~@/styles/olt.scss';

.svc-title {
  color: #606266;
  font-size: 13px;
  margin: 6px 0 8px;
}
.svc-title-line {
  margin-top: 18px;
}
.svc-info {
  color: #909399;
  margin-left: 4px;
}
.svc-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 4px 0 2px;
}
.svc-footer-right {
  display: flex;
  align-items: center;
}
.svc-link {
  padding: 0;
  font-size: 12px;
}
.svc-danger {
  padding: 0;
  color: #f56c6c;
}
</style>
