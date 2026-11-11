import { http } from '../utils/request'

export const authApi = {
  login: (username, password) => http.post('/auth/login', { username, password }),
  bindPhone: (phone) => http.post('/auth/bind-phone', { phone }),
  changePassword: (oldPassword, newPassword) =>
    http.put('/auth/password', { oldPassword, newPassword }),
  me: () => http.get('/auth/me', null, { silent: true })
}
