import { describe, it, expect } from 'vitest'
import { resolveApiUrl } from '../src/api.js'

// CodeQL js/request-forgery #1 的收口：jsonFetch 会自动带 Authorization: Bearer，
// 所以任何把 url 带出同源 /api/v1/ 的写法都等于把令牌发给别的宿主。
describe('resolveApiUrl：只放行同源 /api/v1/ 路径', () => {
  it('放行普通 API 路径', () => {
    expect(resolveApiUrl('/api/v1/drones')).toBe('/api/v1/drones')
  })

  it('保留查询串', () => {
    expect(resolveApiUrl('/api/v1/flightlog?from=1&to=2')).toBe('/api/v1/flightlog?from=1&to=2')
  })

  it('拒绝绝对 URL（会把 Bearer 令牌发往外部宿主）', () => {
    expect(() => resolveApiUrl('http://evil.example/api/v1/drones')).toThrow(/跨源/)
    expect(() => resolveApiUrl('https://evil.example/api/v1/drones')).toThrow(/跨源/)
  })

  it('拒绝协议相对 URL（//host）', () => {
    expect(() => resolveApiUrl('//evil.example/api/v1/drones')).toThrow(/跨源/)
  })

  it('拒绝用 .. 爬出 /api/v1/ 的路径', () => {
    expect(() => resolveApiUrl('/api/v1/../actuator/env')).toThrow(/非 API 路径/)
  })

  it('拒绝非 API 前缀的同源路径', () => {
    expect(() => resolveApiUrl('/login')).toThrow(/非 API 路径/)
    expect(() => resolveApiUrl('/api/v1')).toThrow(/非 API 路径/)
  })

  it('拒绝空值与非字符串', () => {
    expect(() => resolveApiUrl('')).toThrow(/非法请求 URL/)
    expect(() => resolveApiUrl(null)).toThrow(/非法请求 URL/)
    expect(() => resolveApiUrl(undefined)).toThrow(/非法请求 URL/)
  })
})
