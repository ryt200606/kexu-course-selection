import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { webcrypto } from 'node:crypto'
beforeEach(() => { vi.resetModules(); vi.stubGlobal('window', { setTimeout, clearTimeout, dispatchEvent: vi.fn() }) })
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })
describe('会话与写入请求契约', () => {
  it('Host-only普通HTTP没有randomUUID时仍使用安全随机幂等键', async () => {
    vi.stubGlobal('crypto', { getRandomValues: webcrypto.getRandomValues.bind(webcrypto) })
    const { newKey } = await import('./api')
    const first = newKey(), second = newKey()
    expect(first).toMatch(/^web-[a-f0-9]{32}$/); expect(second).not.toBe(first)
  })
  it('先获取CSRF再写入，并保持同源Cookie与同一次幂等键', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ token: 'csrf-test-only' }), { status: 200 })).mockResolvedValueOnce(new Response(JSON.stringify({ status: 'OK' }), { status: 200 }))
    vi.stubGlobal('fetch', fetch)
    const { api } = await import('./api')
    await api('/student/enrollments', { method: 'POST', body: { sectionId: 'SEC1' }, idempotent: true, key: 'one-operation-test-key' })
    expect(fetch).toHaveBeenNthCalledWith(1, '/api/v2/auth/csrf', { credentials: 'same-origin' })
    const [url, options] = fetch.mock.calls[1]!
    expect(url).toBe('/api/v2/student/enrollments'); expect(options.credentials).toBe('same-origin'); expect(options.headers['X-CSRF-TOKEN']).toBe('csrf-test-only'); expect(options.headers['Idempotency-Key']).toBe('one-operation-test-key'); expect(JSON.parse(options.body)).toEqual({ sectionId: 'SEC1' })
  })
  it('保留业务冲突码与追踪编号，不自动重放写入', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ code: 'TIMETABLE_CONFLICT', message: '与已选课程时段冲突', requestId: 'request-only-test' }), { status: 409 }))
    vi.stubGlobal('fetch', fetch)
    const { api } = await import('./api')
    await expect(api('/student/sections/SEC1/eligibility')).rejects.toMatchObject({ status: 409, code: 'TIMETABLE_CONFLICT', message: '与已选课程时段冲突', requestId: 'request-only-test' }); expect(fetch).toHaveBeenCalledTimes(1)
  })
  it('会话失效发出统一事件，失败响应不被当作空列表', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: 'UNAUTHENTICATED', message: '请重新登录' }), { status: 401 })))
    const { api } = await import('./api')
    await expect(api('/student/enrollments')).rejects.toMatchObject({ status: 401 }); expect(window.dispatchEvent).toHaveBeenCalledWith(expect.objectContaining({ type: 'course:session-expired' }))
  })
})
