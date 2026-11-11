import { http } from '../utils/request'
import { OFFLINE_ACTIONS } from '../utils/config'

export const qualityApi = {
  list: (params) => http.get('/inspections', params),
  // 检验单录入常在检验台，断网时先排队
  create: (data) => http.post('/inspections', data, { offline: true, label: OFFLINE_ACTIONS.inspection }),
  summary: () => http.get('/inspections/summary'),
  detail: (id) => http.get(`/inspections/${id}`),
  items: (params) => http.get('/inspections/items', params),
  samplePlan: (params) => http.get('/inspections/sample-plan', params)
}
