<template>
  <div class="app-container">
    <div class="ops-grid">
      <div v-for="section in sections" :key="section.key" class="ops-block">
        <div class="ops-title">{{ section.title }}</div>
        <div v-for="row in section.rows" :key="row.label" class="ops-row">
          <span class="ops-label">{{ row.label }}</span>
          <a
            v-if="isLink(row)"
            :href="row.value"
            target="_blank"
            rel="noopener"
            class="ops-value ops-link"
          >{{ row.value }}</a>
          <span v-else class="ops-value">{{ row.value }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { fetchEnvironment } from '@/api/ops'

const SECTION_TITLES = {
  system: '系统信息',
  server: '服务器配置',
  database: '数据库',
  redis: 'Redis'
}

/** 运维管理。分组与展示行都由后端拼好，页面只渲染。 */
export default {
  name: 'SystemOps',
  data() {
    return {
      sections: []
    }
  },
  created() {
    this.load()
  },
  methods: {
    /** URL 型的值渲染成可点击链接，目前只有 Git 地址 */
    isLink(row) {
      return row.label === 'Git 地址' && /^https?:\/\//.test(row.value)
    },
    load() {
      fetchEnvironment()
        .then(res => {
          const data = res.data || {}
          const sections = Object.keys(SECTION_TITLES)
            .filter(key => data[key])
            .map(key => ({ key, title: SECTION_TITLES[key], rows: [...data[key]] }))
          // 后端单独返回 git 组时并进系统信息，跟系统版本放一起
          const system = sections.find(s => s.key === 'system')
          if (system && Array.isArray(data.git)) {
            system.rows = system.rows.concat(data.git)
          }
          this.sections = sections
        })
        .catch(() => {})
    }
  }
}
</script>

<style scoped>
.ops-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}
.ops-block {
  border: 1px solid #ebeef5;
  border-radius: 4px;
  padding: 10px 12px;
  background: #fff;
}
.ops-title {
  font-weight: 600;
  font-size: 13px;
  margin-bottom: 6px;
  padding-bottom: 6px;
  border-bottom: 1px solid #ebeef5;
}
.ops-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  font-size: 13px;
  line-height: 24px;
}
.ops-label {
  color: #909399;
  white-space: nowrap;
}
.ops-value {
  color: #303133;
  text-align: right;
  word-break: break-all;
}
.ops-link {
  color: #409eff;
  text-decoration: none;
  cursor: pointer;
}
.ops-link:hover {
  text-decoration: underline;
}
</style>
