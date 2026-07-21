import request from './request'

export const getWealthOverviewAPI = () => request.get('/wealth/overview')

export const updateWealthBaselineAPI = cashBalance =>
  request.put('/wealth/baseline', { cashBalance })
