import request from './request'

const base = '/quant/v2'
const data = promise => promise.then(response => response.data)
export const quant = {
  remove: (resource, id) => data(request.delete(`${base}/${resource}/${encodeURIComponent(id)}`)),
  deleteStrategy: id => data(request.delete(`${base}/strategies/${encodeURIComponent(id)}`)),
  list: (resource, params) => data(request.get(`${base}/${resource}`, { params })),
  get: (resource, id) => data(request.get(`${base}/${resource}/${encodeURIComponent(id)}`)),
  create: (resource, body) => data(request.post(`${base}/${resource}`, body)),
  update: (resource, id, body) => data(request.put(`${base}/${resource}/${encodeURIComponent(id)}`, body)),
  action: (resource, id, action, body = {}) => data(request.post(`${base}/${resource}/${encodeURIComponent(id)}/${action}`, body)),
  versions: (resource, id) => data(request.get(`${base}/${resource}/${encodeURIComponent(id)}/versions`)),
  experiments: {
    list: params => data(request.get(`${base}/experiments`, { params })),
    get: (id, options = {}) => data(request.get(`${base}/experiments/${encodeURIComponent(id)}`, {
      silentFeedback: options.silent === true, signal: options.signal,
    })),
    eligibility: sourceBacktestId => data(request.get(`${base}/experiments/eligibility`, { params: { sourceBacktestId } })),
    create: (body, idempotencyKey) => data(request.post(`${base}/experiments`, body, { headers: { 'Idempotency-Key': idempotencyKey } })),
  },
}
