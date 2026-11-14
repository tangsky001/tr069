<template>
  <div class="navbar">
    <hamburger :is-active="sidebar.opened" class="hamburger-container" @toggleClick="toggleSideBar" />

    <breadcrumb class="breadcrumb-container" />

    <!-- 账号与登出入口固定在导航栏最右侧：面包屑 flex 占满剩余宽度，
         这里再用 margin-left:auto 兜底，避免窄屏时浮动布局回流到左边 -->
    <div class="right-menu">
      <el-dropdown class="avatar-container" trigger="click" placement="bottom-end">
        <div class="avatar-wrapper">
          <span class="user-name">{{ name || '未登录' }}</span>
          <img :src="avatar+'?imageView2/1/w/80/h/80'" class="user-avatar">
          <i class="el-icon-caret-bottom" />
        </div>
        <el-dropdown-menu slot="dropdown" class="user-dropdown">
          <router-link to="/">
            <el-dropdown-item>
              <i class="el-icon-s-home" />首页
            </el-dropdown-item>
          </router-link>
          <el-dropdown-item divided @click.native="logout">
            <i class="el-icon-switch-button" />退出登录
          </el-dropdown-item>
        </el-dropdown-menu>
      </el-dropdown>
    </div>
  </div>
</template>

<script>
import { mapGetters } from 'vuex'
import Breadcrumb from '@/components/Breadcrumb'
import Hamburger from '@/components/Hamburger'

export default {
  components: {
    Breadcrumb,
    Hamburger
  },
  computed: {
    ...mapGetters([
      'sidebar',
      'avatar',
      'name'
    ])
  },
  methods: {
    toggleSideBar() {
      this.$store.dispatch('app/toggleSideBar')
    },
    async logout() {
      await this.$store.dispatch('user/logout')
      this.$router.push(`/login?redirect=${this.$route.fullPath}`)
    }
  }
}
</script>

<style lang="scss" scoped>
.navbar {
  height: 50px;
  overflow: hidden;
  position: relative;
  display: flex;
  align-items: center;
  background: #fff;
  box-shadow: 0 1px 4px rgba(0,21,41,.08);

  .hamburger-container {
    flex: none;
    line-height: 46px;
    height: 100%;
    cursor: pointer;
    transition: background .3s;
    -webkit-tap-highlight-color:transparent;

    &:hover {
      background: rgba(0, 0, 0, .025)
    }
  }

  .breadcrumb-container {
    flex: 1;
    min-width: 0;
    overflow: hidden;
  }

  .right-menu {
    flex: none;
    margin-left: auto;
    height: 100%;
    line-height: 50px;

    &:focus {
      outline: none;
    }

    .avatar-container {
      margin-right: 20px;

      .avatar-wrapper {
        display: flex;
        align-items: center;
        height: 50px;
        cursor: pointer;

        .user-name {
          margin-right: 8px;
          font-size: 14px;
          color: #5a5e66;
          max-width: 120px;
          overflow: hidden;
          white-space: nowrap;
          text-overflow: ellipsis;
        }

        .user-avatar {
          width: 32px;
          height: 32px;
          border-radius: 50%;
        }

        .el-icon-caret-bottom {
          margin-left: 4px;
          font-size: 12px;
          color: #5a5e66;
        }
      }
    }
  }
}
</style>

<style lang="scss">
// 下拉面板不随导航栏裁切，且菜单项右对齐展开
.user-dropdown {
  .el-dropdown-menu__item {
    i {
      margin-right: 6px;
    }
  }
}
</style>
