import { defineStore } from 'pinia'

/**
 * 用户 store：访问令牌仅保存在内存（响应式状态），不落 localStorage/cookie，
 * 符合项目安全约定（数据说明 §2：accessToken 仅内存保存）。
 *
 * setToken() 是预留的登录模块对接入口：
 * 登录分支合入后，由登录流程调用 setToken 注入令牌即可打通所有请求；
 * 本模块自身不实现登录/刷新/登出逻辑（归属登录模块负责人）。
 */
export const useUserStore = defineStore('user', {
  state: () => ({
    /** 用户 accessToken（内存态，刷新页面即失效，需重新登录获取） */
    token: '' as string,
    /** 调试信息：令牌设置时间（仅用于排查，不含敏感值） */
    tokenSetAt: '' as string,
  }),
  getters: {
    hasToken: (state) => state.token.length > 0,
  },
  actions: {
    setToken(token: string) {
      this.token = token
      this.tokenSetAt = new Date().toISOString()
    },
    clearToken() {
      this.token = ''
      this.tokenSetAt = ''
    },
  },
})