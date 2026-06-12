import axios from 'axios'
import { ElLoading } from 'element-plus'
import router from '@/router'

export default (config: any) => {
  let instance = axios.create({
    baseURL: import.meta.env.VITE_API_URL,
    timeout: 600000,
    timeoutErrorMessage: '请求超时，请稍后再试'
  })

  let loadingInstance: any

  instance.interceptors.request.use(function(config: any) {
    if (config.url && !config.url.includes('/chat/send') && !config.url.includes('/chat/upload')) {
      loadingInstance = ElLoading.service({
        lock: true,
        text: '加载中...',
        fullscreen: true,
        background: 'rgba(0, 0, 0, 0.7)'
      })
    }
    return config
  }, function(error: any) {
    return Promise.reject(error)
  })

  instance.interceptors.response.use(function(response: any) {
    if (loadingInstance) loadingInstance.close()
    return response
  }, function(error: any) {
    if (loadingInstance) loadingInstance.close()
    return Promise.reject(error)
  })

  return instance(config)
}
