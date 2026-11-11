import { http } from '../utils/request'

export const dashboardApi = {
  summary: () => http.get('/dashboard/summary')
}
