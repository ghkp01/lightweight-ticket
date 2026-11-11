import { http } from '../utils/request'
import { OFFLINE_ACTIONS } from '../utils/config'

export const workOrderApi = {
  list: (params) => http.get('/work-orders', params),
  detail: (id) => http.get(`/work-orders/${id}`),
  detailByNo: (workNo) => http.get(`/work-orders/by-no/${encodeURIComponent(workNo)}`),
  release: (id) => http.post(`/work-orders/${id}/release`),
  issue: (id) => http.post(`/work-orders/${id}/issue`),
  // 报工是车间最高频动作，断网时进离线队列，联网自动重传（靠幂等键防重复）
  report: (id, data) =>
    http.post(`/work-orders/${id}/report`, data, { offline: true, label: OFFLINE_ACTIONS.report }),
  documents: (id) => http.get(`/work-orders/${id}/documents`),
  close: (id) => http.post(`/work-orders/${id}/close`)
}
