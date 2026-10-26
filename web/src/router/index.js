import Vue from 'vue'
import Router from 'vue-router'

Vue.use(Router)

/* Layout */
import Layout from '@/layout'

/**
 * hidden: true                   侧边栏不显示
 * alwaysShow: true               始终显示根菜单
 * meta: { title, icon }
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
  {
    path: '/',
    component: Layout,
    redirect: '/dashboard',
    children: [{
      path: 'dashboard',
      name: 'Dashboard',
      component: () => import('@/views/dashboard/index'),
      meta: { title: '首页概览', icon: 'dashboard' }
    }]
  },
  {
    path: '/device',
    component: Layout,
    redirect: '/device/index',
    children: [{
      path: 'index',
      name: 'Device',
      component: () => import('@/views/device/index'),
      meta: { title: '设备台账', icon: 'el-icon-s-platform' }
    }]
  },
  {
    path: '/dispatch',
    component: Layout,
    redirect: '/dispatch/index',
    children: [{
      path: 'index',
      name: 'Dispatch',
      component: () => import('@/views/dispatch/index'),
      meta: { title: '配置下发', icon: 'el-icon-s-promotion' }
    }]
  },
  {
    path: '/adapt',
    component: Layout,
    redirect: '/adapt/index',
    children: [{
      path: 'index',
      name: 'Adapt',
      component: () => import('@/views/adapt/index'),
      meta: { title: '光猫适配', icon: 'el-icon-s-cooperation' }
    }]
  },
  {
    path: '/log',
    component: Layout,
    redirect: '/log/cwmp',
    name: 'Log',
    meta: { title: '日志审计', icon: 'el-icon-document' },
    children: [
      {
        path: 'cwmp',
        name: 'CwmpLog',
        component: () => import('@/views/log/cwmp'),
        meta: { title: '协议交互' }
      },
      {
        path: 'audit',
        name: 'AuditLog',
        component: () => import('@/views/log/audit'),
        meta: { title: '操作审计' }
      },
      {
        path: 'syslog',
        name: 'Syslog',
        component: () => import('@/views/log/syslog'),
        meta: { title: '设备日志' }
      }
    ]
  },
  {
    path: '/file',
    component: Layout,
    redirect: '/file/index',
    children: [{
      path: 'index',
      name: 'File',
      component: () => import('@/views/file/index'),
      meta: { title: '固件文件', icon: 'el-icon-folder' }
    }]
  },
  {
    path: '/system',
    component: Layout,
    redirect: '/system/user',
    name: 'System',
    meta: { title: '系统管理', icon: 'el-icon-setting' },
    children: [{
      path: 'user',
      name: 'User',
      component: () => import('@/views/system/user'),
      meta: { title: '账号管理' }
    }]
  },
  // 404 page must be placed at the end !!!
  { path: '*', redirect: '/404', hidden: true }
]

const createRouter = () => new Router({
  scrollBehavior: () => ({ y: 0 }),
  routes: constantRoutes
})

const router = createRouter()

export function resetRouter() {
  const newRouter = createRouter()
  router.matcher = newRouter.matcher
}

export default router
