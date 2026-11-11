import { http } from '../utils/request'

export const shippingApi = {
  list: (params) => http.get('/shipments', params),
  detail: (id) => http.get(`/shipments/${id}`)
}
