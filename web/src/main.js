import Vue from 'vue'

import 'normalize.css/normalize.css' // A modern alternative to CSS resets

import ElementUI from 'element-ui'
import 'element-ui/lib/theme-chalk/index.css'
import locale from 'element-ui/lib/locale/lang/zh-CN' // 页面上的“暂无数据 / 共 0 条 / 20 条/页”这些内置文案都由它出

import '@/styles/index.scss' // global css

import App from './App'
import store from './store'
import router from './router'
import { hasPerm } from '@/utils/permission'

import '@/icons' // icon
import '@/permission' // permission control

/**
 * If you don't want to use mock-server
 * you want to use MockJs for mock api
 * you can execute: mockXHR()
 *
 * Currently MockJs will be used in the production environment,
 * please remove it before going online ! ! !
 */
if (process.env.NODE_ENV === 'production') {
  const { mockXHR } = require('../mock')
  mockXHR()
}

Vue.use(ElementUI, { locale })
// 模板里直接写 v-if="$has('device:manage')"：按钮按权限摆出来
Vue.prototype.$has = hasPerm

Vue.config.productionTip = false

new Vue({
  el: '#app',
  router,
  store,
  render: h => h(App)
})
