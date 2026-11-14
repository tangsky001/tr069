<template>
  <div class="sidebar-logo-container" :class="{'collapse':collapse}">
    <router-link class="sidebar-logo-link" to="/">
      <svg class="sidebar-logo-mark" viewBox="0 0 32 32" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="PilotACS">
        <defs>
          <linearGradient id="pilotAcsMark" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0%" stop-color="#2f7cf6" />
            <stop offset="100%" stop-color="#14b8a6" />
          </linearGradient>
        </defs>
        <rect width="32" height="32" rx="9" fill="url(#pilotAcsMark)" />
        <path d="M11 27.5V8.5h4.6a5.4 5.4 0 0 1 0 10.8H11" fill="none" stroke="#fff" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
      <span v-if="!collapse" class="sidebar-logo-text">Pilot<span class="accent">ACS</span></span>
    </router-link>
  </div>
</template>

<script>
export default {
  name: 'SidebarLogo',
  props: {
    collapse: {
      type: Boolean,
      required: true
    }
  }
}
</script>

<style lang="scss" scoped>
.sidebar-logo-container {
  width: 100%;
  height: 50px;
  padding-left: 20px;
  box-sizing: border-box;
  display: flex;
  align-items: center;
  background: #304156;
  overflow: hidden;

  & .sidebar-logo-link {
    display: flex;
    align-items: center;
    text-decoration: none;
    white-space: nowrap;
    line-height: 26px;

    & .sidebar-logo-mark {
      display: inline-block;
      vertical-align: middle;
      width: 26px;
      height: 26px;
      margin-right: 9px;
      flex-shrink: 0;
    }

    & .sidebar-logo-text {
      display: inline-block;
      vertical-align: middle;
      color: #fff;
      font-size: 15px;
      font-weight: 700;
      letter-spacing: 0.3px;
      white-space: nowrap;
      font-family: Avenir, 'Helvetica Neue', Arial, Helvetica, sans-serif;

      & .accent {
        color: #4dd0c4;
      }
    }
  }

  &.collapse {
    padding-left: 0;

    & .sidebar-logo-link {
      justify-content: center;
    }
  }
}
</style>
