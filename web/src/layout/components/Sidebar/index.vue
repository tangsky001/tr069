<template>
  <div :class="{'has-logo':showLogo}">
    <logo v-if="showLogo" :collapse="isCollapse" />
    <el-scrollbar wrap-class="scrollbar-wrapper">
      <el-menu
        :default-active="activeMenu"
        :collapse="isCollapse"
        :background-color="variables.menuBg"
        :text-color="variables.menuText"
        :unique-opened="false"
        :active-text-color="variables.menuActiveText"
        :collapse-transition="false"
        mode="vertical"
      >
        <sidebar-item v-for="route in routes" :key="route.path" :item="route" :base-path="route.path" />
      </el-menu>
    </el-scrollbar>
  </div>
</template>

<script>
import { mapGetters } from 'vuex'
import Logo from './Logo'
import SidebarItem from './SidebarItem'
import variables from '@/styles/variables.scss'

export default {
  components: { SidebarItem, Logo },
  computed: {
    ...mapGetters([
      'sidebar'
    ]),
    routes() {
      // 系统管理员看全部，其余按各自角色勾的权限决定菜单里剩什么
      if (this.$store.getters.isSuperAdmin) {
        return this.$router.options.routes
      }
      return this.$router.options.routes
        .map(r => this.prune(r, this.$store.getters.permissions || []))
        .filter(Boolean)
    },
    activeMenu() {
      const route = this.$route
      const { meta, path } = route
      // if set path, the sidebar will highlight the path you set
      if (meta.activeMenu) {
        return meta.activeMenu
      }
      return path
    },
    showLogo() {
      return this.$store.state.settings.sidebarLogo
    },
    variables() {
      return variables
    },
    isCollapse() {
      return !this.sidebar.opened
    }
  },
  methods: {
    /** 砍掉没权限的路：子菜单全没了，父菜单也没必要留着。 */
    prune(route, permissions) {
      const ok = r => !r.meta || !r.meta.permission ||
        permissions.indexOf(r.meta.permission) >= 0
      if (route.children && route.children.length) {
        const kids = route.children.filter(ok)
        return kids.length ? { ...route, children: kids } : null
      }
      return ok(route) ? route : null
    }
  }
}
</script>
