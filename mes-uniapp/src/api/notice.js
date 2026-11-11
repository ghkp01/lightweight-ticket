import { http } from '../utils/request'

export const noticeApi = {
  list: (params) => http.get('/notices', params),
  summary: () => http.get('/notices/summary'),
  read: (id) => http.put(`/notices/${id}/read`),
  readAll: () => http.put('/notices/read-all')
}
