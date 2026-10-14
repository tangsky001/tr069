<template>
  <div class="login-container">
    <!-- 左侧品牌区：科技感光晕 + 网格背景，窄屏时让位给登录卡片 -->
    <div class="login-brand">
      <div class="brand-inner">
        <div class="brand-badge">TR069 · CWMP</div>
        <h1 class="brand-title">TR069 ACS 管理平台</h1>
        <p class="brand-sub">光猫远程管理与配置下发</p>
        <ul class="brand-points">
          <li><i class="el-icon-monitor" />设备接入 · 在线状态实时监控</li>
          <li><i class="el-icon-s-operation" />工单下发 · 执行结果全程跟踪</li>
          <li><i class="el-icon-s-management" />型号适配 · 配置模板统一管理</li>
        </ul>
      </div>
    </div>

    <!-- 右侧登录卡片：深色玻璃拟态，青蓝描边发光 -->
    <div class="login-panel">
      <div class="login-card">
        <el-form ref="loginForm" :model="loginForm" :rules="loginRules" class="login-form" auto-complete="on" label-position="left">

          <div class="title-container">
            <h3 class="title">账号登录</h3>
            <p class="subtitle">请使用管理员账号登录系统</p>
          </div>

          <el-form-item prop="username">
            <span class="svg-container">
              <svg-icon icon-class="user" />
            </span>
            <el-input
              ref="username"
              v-model="loginForm.username"
              placeholder="请输入用户名"
              name="username"
              type="text"
              tabindex="1"
              auto-complete="on"
            />
          </el-form-item>

          <el-form-item prop="password">
            <span class="svg-container">
              <svg-icon icon-class="password" />
            </span>
            <el-input
              :key="passwordType"
              ref="password"
              v-model="loginForm.password"
              :type="passwordType"
              placeholder="请输入密码"
              name="password"
              tabindex="2"
              auto-complete="on"
              @keyup.enter.native="handleLogin"
            />
            <span class="show-pwd" @click="showPwd">
              <svg-icon :icon-class="passwordType === 'password' ? 'eye' : 'eye-open'" />
            </span>
          </el-form-item>

          <el-button :loading="loading" class="login-btn" @click.native.prevent="handleLogin">登 录</el-button>

        </el-form>
      </div>
    </div>
  </div>
</template>

<script>
import { validUsername } from '@/utils/validate'

export default {
  name: 'Login',
  data() {
    const validateUsername = (rule, value, callback) => {
      if (!validUsername(value)) {
        callback(new Error('请输入正确的用户名'))
      } else {
        callback()
      }
    }
    // 不做长度限制：现场默认口令可能短于 6 位，正确性交给后端校验
    const validatePassword = (rule, value, callback) => {
      if (!value) {
        callback(new Error('请输入密码'))
      } else {
        callback()
      }
    }
    return {
      loginForm: {
        username: 'admin',
        password: 'admin'
      },
      loginRules: {
        username: [{ required: true, trigger: 'blur', validator: validateUsername }],
        password: [{ required: true, trigger: 'blur', validator: validatePassword }]
      },
      loading: false,
      passwordType: 'password',
      redirect: undefined
    }
  },
  watch: {
    $route: {
      handler: function(route) {
        this.redirect = route.query && route.query.redirect
      },
      immediate: true
    }
  },
  methods: {
    showPwd() {
      if (this.passwordType === 'password') {
        this.passwordType = ''
      } else {
        this.passwordType = 'password'
      }
      this.$nextTick(() => {
        this.$refs.password.focus()
      })
    },
    handleLogin() {
      this.$refs.loginForm.validate(valid => {
        if (valid) {
          this.loading = true
          this.$store.dispatch('user/login', this.loginForm).then(() => {
            this.$router.push({ path: this.redirect || '/' })
            this.loading = false
          }).catch(() => {
            this.loading = false
          })
        } else {
          console.log('error submit!!')
          return false
        }
      })
    }
  }
}
</script>

<style lang="scss">
/* 重置 element-ui 输入框：卡片是深色玻璃底，文字与光标用浅色 */
.login-container {
  .el-input {
    display: inline-block;
    height: 44px;
    width: calc(100% - 52px);

    input {
      background: transparent;
      border: 0px;
      -webkit-appearance: none;
      border-radius: 0px;
      padding: 12px 5px 12px 8px;
      color: #dbe7f5;
      height: 44px;
      caret-color: #00e5ff;

      &::placeholder {
        color: rgba(219, 231, 245, .35);
      }

      &:-webkit-autofill {
        box-shadow: 0 0 0px 1000px #0c1c34 inset !important;
        -webkit-text-fill-color: #dbe7f5 !important;
      }
    }
  }

  .el-form-item {
    border: 1px solid rgba(0, 229, 255, .22);
    background: rgba(255, 255, 255, .04);
    border-radius: 6px;
    color: #dbe7f5;
    transition: border-color .25s, box-shadow .25s;

    &:hover {
      border-color: rgba(0, 229, 255, .5);
      box-shadow: 0 0 14px rgba(0, 229, 255, .15);
    }
  }
}
</style>

<style lang="scss" scoped>
$cyan: #00e5ff;
$blue: #0090ff;
$text-main: #dbe7f5;
$text-sub: #7c93b4;

.login-container {
  position: relative;
  display: flex;
  min-height: 100%;
  width: 100%;
  overflow: hidden;
  background: radial-gradient(1200px 700px at 75% 40%, #123055 0%, #081226 45%, #050a16 100%);

  // 网格底纹
  &::before {
    content: '';
    position: absolute;
    inset: 0;
    background-image:
      linear-gradient(rgba(0, 229, 255, .06) 1px, transparent 1px),
      linear-gradient(90deg, rgba(0, 229, 255, .06) 1px, transparent 1px);
    background-size: 44px 44px;
    -webkit-mask-image: radial-gradient(circle at 40% 50%, #000 0%, transparent 72%);
    mask-image: radial-gradient(circle at 40% 50%, #000 0%, transparent 72%);
    pointer-events: none;
  }

  // 青蓝光斑
  &::after {
    content: '';
    position: absolute;
    width: 620px;
    height: 620px;
    right: -160px;
    top: -220px;
    border-radius: 50%;
    background: radial-gradient(circle, rgba(0, 160, 255, .30) 0%, transparent 62%);
    filter: blur(10px);
    pointer-events: none;
  }

  // 左侧品牌区
  .login-brand {
    position: relative;
    z-index: 1;
    flex: 1;
    display: flex;
    align-items: center;
    padding: 0 6% 0 8%;

    .brand-inner {
      position: relative;
      color: #fff;

      .brand-badge {
        display: inline-block;
        margin-bottom: 22px;
        padding: 5px 14px;
        font-size: 12px;
        letter-spacing: 3px;
        color: $cyan;
        border: 1px solid rgba(0, 229, 255, .35);
        border-radius: 20px;
        background: rgba(0, 229, 255, .08);
        box-shadow: 0 0 18px rgba(0, 229, 255, .18);
      }

      .brand-title {
        margin: 0;
        font-size: 38px;
        font-weight: 600;
        letter-spacing: 3px;
        background: linear-gradient(90deg, #ffffff 0%, #8fd8ff 100%);
        -webkit-background-clip: text;
        background-clip: text;
        -webkit-text-fill-color: transparent;
        text-shadow: 0 0 30px rgba(0, 190, 255, .35);
      }

      .brand-sub {
        margin: 16px 0 44px;
        font-size: 16px;
        letter-spacing: 1px;
        color: rgba(219, 231, 245, .7);
      }

      .brand-points {
        list-style: none;
        margin: 0;
        padding: 0;

        li {
          margin-bottom: 18px;
          font-size: 14px;
          color: rgba(219, 231, 245, .82);

          i {
            margin-right: 10px;
            font-size: 16px;
            color: $cyan;
            text-shadow: 0 0 12px rgba(0, 229, 255, .6);
          }
        }
      }
    }
  }

  // 右侧登录区
  .login-panel {
    position: relative;
    z-index: 1;
    flex: 0 0 520px;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 0 40px;

    .login-card {
      position: relative;
      width: 360px;
      max-width: 100%;
      padding: 42px 34px 36px;
      overflow: hidden;
      border: 1px solid rgba(0, 229, 255, .22);
      border-radius: 12px;
      background: linear-gradient(160deg, rgba(18, 40, 72, .72) 0%, rgba(8, 18, 36, .68) 100%);
      backdrop-filter: blur(16px);
      -webkit-backdrop-filter: blur(16px);
      box-shadow:
        0 24px 60px rgba(0, 0, 0, .55),
        0 0 32px rgba(0, 130, 255, .18),
        inset 0 1px 0 rgba(255, 255, 255, .06);

      // 顶部发光横条
      &::before {
        content: '';
        position: absolute;
        top: 0;
        left: 0;
        right: 0;
        height: 2px;
        background: linear-gradient(90deg, transparent, $cyan, transparent);
      }

      // 缓慢下移的扫描光带
      &::after {
        content: '';
        position: absolute;
        left: 0;
        right: 0;
        top: 0;
        height: 140px;
        background: linear-gradient(180deg, rgba(0, 229, 255, .10) 0%, transparent 100%);
        animation: scan 6s linear infinite;
        pointer-events: none;
      }
    }

    .login-form {
      position: relative;
      z-index: 1;

      .title-container {
        margin-bottom: 30px;

        .title {
          margin: 0 0 8px;
          font-size: 22px;
          font-weight: 600;
          letter-spacing: 2px;
          color: $text-main;
          text-shadow: 0 0 20px rgba(0, 190, 255, .3);
        }

        .subtitle {
          margin: 0;
          font-size: 13px;
          color: $text-sub;
        }
      }

      .login-btn {
        width: 100%;
        height: 44px;
        margin-top: 10px;
        font-size: 15px;
        font-weight: 600;
        letter-spacing: 6px;
        color: #04121f;
        border: none;
        border-radius: 6px;
        background: linear-gradient(90deg, $blue 0%, $cyan 100%);
        box-shadow: 0 6px 22px rgba(0, 170, 255, .38);
        transition: box-shadow .25s, filter .25s;

        &:hover,
        &:focus {
          color: #04121f;
          filter: brightness(1.08);
          box-shadow: 0 8px 28px rgba(0, 210, 255, .55);
        }
      }
    }
  }

  .svg-container {
    padding: 6px 5px 6px 14px;
    color: rgba(0, 229, 255, .75);
    vertical-align: middle;
    width: 30px;
    display: inline-block;
  }

  .show-pwd {
    position: absolute;
    right: 12px;
    top: 1px;
    font-size: 16px;
    color: rgba(0, 229, 255, .7);
    cursor: pointer;
    user-select: none;
  }
}

@keyframes scan {
  0% { transform: translateY(-140px); }
  100% { transform: translateY(420px); }
}

// 窄屏：品牌区让位，卡片居中
@media screen and (max-width: 900px) {
  .login-container {
    .login-brand {
      display: none;
    }
    .login-panel {
      flex: 1;
      padding: 0 20px;
    }
  }
}
</style>
