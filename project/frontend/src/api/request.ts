// Axios 实例预留（本期不安装 axios，后续对接接口时启用）
//
// import axios from 'axios'
//
// const request = axios.create({
//   baseURL: '/api',
//   timeout: 15000,
// })
//
// // 请求拦截器：注入 Authorization 头
// request.interceptors.request.use((config) => {
//   const token = localStorage.getItem('token')
//   if (token) {
//     config.headers.Authorization = `Bearer ${token}`
//   }
//   return config
// })
//
// // 响应拦截器：解包 CommonResult 结构
// request.interceptors.response.use((response) => {
//   const { code, data, message } = response.data
//   if (code === 200) {
//     return data
//   }
//   return Promise.reject(new Error(message))
// })
//
// export default request

export {}