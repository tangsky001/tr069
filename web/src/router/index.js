import Vue from 'vue'
import Router from 'vue-router'

Vue.use(Router)

/* Layout */
import Layout from '@/layout'

/**
 * 业务路由。
 *
 * meta.title 显示在侧边栏与面包屑，meta.icon 为侧边栏图标。
 * meta.permission 是打开这个页面要有的权限点（如 device:view），
 * 侧边栏与路由守卫按它决定这条路露不露出来；不写就是登录即可进。
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
      meta: { title: '首页概览', icon: 'el-icon-s-home', permission: 'dashboard:view' }
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
      meta: { title: '配置模板', icon: 'el-icon-files', permission: 'template:view' }
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
      meta: { title: '业务模板', icon: 'el-icon-menu', permission: 'biz:view' }
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
      meta: { title: '设备管理', icon: 'el-icon-monitor', permission: 'device:view' }
    }]
  },

  // 文件台账：固件与配置，设备从上报端口的 /files/{id} 取
  {
    path: '/files',
    component: Layout,
    redirect: '/files/list',
    children: [{
      path: 'list',
      name: 'FileList',
      component: () => import('@/views/device/files'),
      meta: { title: '文件与固件', icon: 'el-icon-folder-opened', permission: 'file:view' }
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
      meta: { title: '设备详情', permission: 'device:view' }
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
        meta: { title: '下发业务', icon: 'el-icon-s-promotion', permission: 'worklist:view' }
      },
      {
        path: 'instances',
        name: 'WorklistInstances',
        component: () => import('@/views/worklist/instances'),
        meta: { title: '工单记录', icon: 'el-icon-tickets', permission: 'worklist:view' }
      }
    ]
  },

  {
    path: '/olt',
    component: Layout,
    redirect: '/olt/management',
    meta: { title: 'OLT管理', icon: 'el-icon-s-platform' },
    children: [{
      // 台账入口：增删改、诊断、同步，再按厂商把人送到下面的厂商详情页
      path: 'management',
      name: 'OltManagement',
      component: () => import('@/views/olt/management'),
      meta: { title: 'OLT管理', icon: 'el-icon-s-platform', permission: 'olt:view' }
    },
    // 厂商详情页：各厂商命令不同，页面按厂商分开写，由 vendors.js 的映射分发过来
    {
      path: 'huawei/:id',
      name: 'OltHuawei',
      component: () => import('@/views/olt/huawei'),
      meta: { title: '华为面板', activeMenu: '/olt/management', permission: 'olt:view' },
      hidden: true
    },
    {
      path: 'hsgq/:id',
      name: 'OltHsgq',
      component: () => import('@/views/olt/hsgq'),
      meta: { title: '鸿升光面板', activeMenu: '/olt/management', permission: 'olt:view' },
      hidden: true
    },
    {
      path: 'generic/:id',
      name: 'OltGeneric',
      component: () => import('@/views/olt/generic'),
      meta: { title: 'OLT面板', activeMenu: '/olt/management', permission: 'olt:view' },
      hidden: true
    }]
  },

  // 系统管理：用户与角色权限，没配权限的人看不到这条
  {
    path: '/system',
    component: Layout,
    redirect: '/system/users',
    alwaysShow: true,
    name: 'System',
    meta: { title: '系统管理', icon: 'el-icon-setting' },
    children: [
      {
        path: 'users',
        name: 'SystemUsers',
        component: () => import('@/views/system/users'),
        meta: { title: '用户管理', icon: 'el-icon-user', permission: 'system:view' }
      },
      {
        path: 'roles',
        name: 'SystemRoles',
        component: () => import('@/views/system/roles'),
        meta: { title: '角色权限', icon: 'el-icon-s-custom', permission: 'system:view' }
      },
      {
        path: 'login-logs',
        name: 'SystemLoginLogs',
        component: () => import('@/views/system/login-logs'),
        meta: { title: '登录日志', icon: 'el-icon-time', permission: 'system:view' }
      }
    ]
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
