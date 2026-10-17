import request from './request'

export const listInvestmentPlansAPI = () => request.get('/investment/plans')
export const createInvestmentPlanAPI = data => request.post('/investment/plans', data)
export const updateInvestmentPlanAPI = (id, data) => request.put(`/investment/plans/${id}`, data)
export const setInvestmentPlanEnabledAPI = (id, enabled) => request.post(`/investment/plans/${id}/enabled`, { enabled })
export const deleteInvestmentPlanAPI = id => request.delete(`/investment/plans/${id}`)
export const resolveInvestmentAssetAPI = data => request.post('/investment/assets/resolve', data, { silentFeedback: true })
export const searchInvestmentAssetProductsAPI = ({ keyword, productType, page }) => request.get('/quant/v2/market-data/products', {
  params: {
    search: keyword,
    assetType: productType ? (productType === 'STOCK' ? 'STOCK' : 'FUND') : 'STOCK,FUND',
    status: 'ACTIVE',
    page
  },
  silentFeedback: true
})
export const createInvestmentAssetAPI = (data, options) => request.post('/investment/assets', data, options)
export const listInvestmentAssetsAPI = () => request.get('/investment/assets')
export const refreshInvestmentAssetsAPI = force => request.post('/investment/assets/refresh', null, { params: { force } })
export const updateInvestmentAssetAPI = (id, data) => request.put(`/investment/assets/${id}`, data)
export const deleteInvestmentAssetAPI = id => request.delete(`/investment/assets/${id}`)
export const listInvestmentIndexesAPI = options => request.get('/investment/indexes/watchlist', options)
export const searchInvestmentIndexesAPI = keyword => request.get('/investment/indexes/search', { params: { keyword } })
export const addInvestmentIndexAPI = data => request.post('/investment/indexes/watchlist', data)
export const reorderInvestmentIndexesAPI = indexCodes => request.put('/investment/indexes/watchlist/order', { indexCodes })
export const deleteInvestmentIndexAPI = id => request.delete(`/investment/indexes/watchlist/${id}`)
export const syncInvestmentAssetAPI = (id, force = true) => request.post(`/investment/assets/${id}/sync`, null, { params: { force }, silentFeedback: true })
export const getInvestmentAssetDetailAPI = id => request.get(`/investment/assets/${id}/detail`, { timeout: 60000, silentFeedback: true })
export const getInvestmentHorizonProfileAPI = () => request.get('/investment/horizon-profile')
export const updateInvestmentHorizonProfileAPI = data => request.put('/investment/horizon-profile', data)
export const updateInvestmentAssetHorizonOverrideAPI = (id, data) => request.put(`/investment/assets/${id}/analysis-preference`, data, { timeout: 60000, silentFeedback: true })
export const clearInvestmentAssetHorizonOverrideAPI = id => request.delete(`/investment/assets/${id}/analysis-preference`, { timeout: 60000, silentFeedback: true })
