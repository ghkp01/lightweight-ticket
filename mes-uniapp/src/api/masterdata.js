import { http } from '../utils/request'

export const masterDataApi = {
  products: (keyword) => http.get('/products', keyword ? { keyword } : null),
  warehouses: () => http.get('/warehouses'),
  inventory: (params) => http.get('/inventory', params),
  bom: (productId) => http.get(`/products/${productId}/bom`)
}
