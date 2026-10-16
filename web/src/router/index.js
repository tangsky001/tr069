import Vue from 'vue'
import Router from 'vue-router'

Vue.use(Router)

/* Layout */
import Layout from '@/layout'

/**
 * 业务路由。
 *
 * meta.title 显示在侧边栏与面包屑，meta.icon 为侧边栏图标。
 * 设备详情不在侧边栏展示（hidden），由列表页跳转进入。
 */
export const constantRoutes = [
  {
    path: '/login',
    component: () => import('@/views/login/index'),
    hidden: true
  },

  {
    path: '/404',
    component: () => import('@/views/404'),
    hidden: true
  },

  // 登录后默认进概览页。没有这条就会命中下面的 * 兜底，登录后直接跳 404
  {
    path: '/',
    component: Layout,
    redirect: '/dashboard',
    hidden: true
  },

  {
    path: '/dashboard',
    component: Layout,
    redirect: '/dashboard/index',
    children: [{
      path: 'index',
      name: 'Dashboard',
      component: () => import('@/views/dashboard/index'),
      meta: { title: '首页概览', icon: 'el-icon-s-home' }
    }]
  },

  {
    path: '/template',
    component: Layout,
    redirect: '/template/list',
    children: [{
      path: 'list',
      name: 'TemplateList',
      component: () => import('@/views/worklist/templates'),
      meta: { title: '配置模板', icon: 'el-icon-files' }
    }]
  },

  {
    path: '/biz',
    component: Layout,
    redirect: '/biz/list',
    children: [{
      path: 'list',
      name: 'BizTemplateList',
      component: () => import('@/views/biz/template'),
      meta: { title: '业务模板', icon: 'el-icon-menu' }
    }]
  },

  {
    path: '/device',
    component: Layout,
    redirect: '/device/list',
    children: [{
      path: 'list',
      name: 'DeviceList',
      component: () => import('@/views/device/list'),
      meta: { title: '设备管理', icon: 'el-icon-monitor' }
    }]
  },

  {
    path: '/device-detail',
    component: Layout,
    hidden: true,
    children: [{
      path: ':sn',
      name: 'DeviceDetail',
      component: () => import('@/views/device/detail'),
      meta: { title: '设备详情' }
    }]
  },

  {
    path: '/worklist',
    component: Layout,
    redirect: '/worklist/instances',
    alwaysShow: true,
    name: 'Worklist',
    meta: { title: '工单管理', icon: 'el-icon-s-order' },
    children: [
      {
        path: 'deliver',
        name: 'WorklistDeliver',
        component: () => import('@/views/worklist/deliver'),
        meta: { title: '下发业务', icon: 'el-icon-s-promotion' }
      },
      {
        path: 'instances',
        name: 'WorklistInstances',
        component: () => import('@/views/worklist/instances'),
        meta: { title: '工单记录', icon: 'el-icon-tickets' }
      },
      {
        path: 'profiles',
        name: 'WorklistProfiles',
        component: () => import('@/views/worklist/profiles'),
        meta: { title: '光猫适配', icon: 'el-icon-cpu' }
      }
    ]
  },

  {
    path: '/olt',
    component: Layout,
    redirect: '/olt/management',
    children: [{
      path: 'management',
      name: 'OltManagement',
      component: () => import('@/views/olt/management'),
      meta: { title: 'OLT管理', icon: 'el-icon-s-platform' }
    }]
  },

  // 404 page must be placed at the end !!!
  { path: '*', redirect: '/404', hidden: true }
]

const createRouter = () => new Router({
  // mode: 'history', // require service support
  scrollBehavior: () => ({ y: 0 }),
  routes: constantRoutes
})

const router = createRouter()

// Detail see: https://github.com/vuejs/vue-router/issues/1234#issuecomment-357941465
export function resetRouter() {
  const newRouter = createRouter()
  router.matcher = newRouter.matcher // reset router
}

export default router
