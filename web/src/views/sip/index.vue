<template>
  <div class="app-container">
    <el-tabs v-model="activeTab" type="card">
      <el-tab-pane label="分机管理" name="extensions">
        <extensions v-if="activeTab === 'extensions'" />
      </el-tab-pane>
      <el-tab-pane label="呼叫队列" name="queues">
        <queues v-if="activeTab === 'queues'" />
      </el-tab-pane>
      <el-tab-pane label="通话记录" name="records">
        <records v-if="activeTab === 'records'" />
      </el-tab-pane>
      <el-tab-pane label="实时监控" name="monitor">
        <monitor v-if="activeTab === 'monitor'" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script>
import extensions from './extensions'
import queues from './queues'
import records from './records'
import monitor from './monitor'

/** SIP 软交换：单页签页组织分机/队列/记录/监控，激活才加载，避免整页拥挤 */
export default {
  name: 'SipPanel',
  components: { extensions, queues, records, monitor },
  data() {
    return {
      activeTab: 'extensions'
    }
  }
}
</script>
