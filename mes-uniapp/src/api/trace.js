import { http } from '../utils/request'

export const traceApi = {
  /** 批次 / 工单 / 发货单统一追溯入口 */
  query: (code) => http.get('/trace/query', { code })
}
