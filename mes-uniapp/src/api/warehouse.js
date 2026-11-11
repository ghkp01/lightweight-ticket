import { http } from '../utils/request'
import { OFFLINE_ACTIONS } from '../utils/config'

export const inboundApi = {
  list: (params) => http.get('/inbound-orders', params),
  detail: (id) => http.get(`/inbound-orders/${id}`),
  create: (data) => http.post('/inbound-orders', data, { offline: true, label: OFFLINE_ACTIONS.inbound }),
  update: (id, data) => http.put(`/inbound-orders/${id}`, data),
  submit: (id) => http.post(`/inbound-orders/${id}/submit`),
  cancel: (id) => http.post(`/inbound-orders/${id}/cancel`),
  remove: (id) => http.delete(`/inbound-orders/${id}`)
}

export const outboundApi = {
  list: (params) => http.get('/outbound-orders', params),
  detail: (id) => http.get(`/outbound-orders/${id}`),
  create: (data) => http.post('/outbound-orders', data, { offline: true, label: OFFLINE_ACTIONS.outbound }),
  update: (id, data) => http.put(`/outbound-orders/${id}`, data),
  complete: (id) => http.post(`/outbound-orders/${id}/complete`),
  cancel: (id) => http.post(`/outbound-orders/${id}/cancel`),
  remove: (id) => http.delete(`/outbound-orders/${id}`)
}
