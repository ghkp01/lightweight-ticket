import { http } from '../utils/request'
import { OFFLINE_ACTIONS } from '../utils/config'

export const defectApi = {
  list: (params) => http.get('/defects', params),
  summary: () => http.get('/defects/summary'),
  dispose: (id, data) =>
    http.post(`/defects/${id}/dispose`, data, { offline: true, label: OFFLINE_ACTIONS.defect })
}
