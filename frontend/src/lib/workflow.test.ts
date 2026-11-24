import { describe, expect, it, vi } from 'vitest'
import { formatScore } from './grades'
import { useAsync } from './useAsync'
import { findRecords } from './lookup'

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: Error) => void
  const promise = new Promise<T>((ok, fail) => {
    resolve = ok
    reject = fail
  })
  return { promise, resolve, reject }
}

describe('V3 基础交互', () => {
  it('如实呈现及格线附近和零分的两位小数', () => {
    expect([59.99, 60, 0, 99.95, null].map(formatScore)).toEqual(['59.99', '60.00', '0.00', '99.95', '—'])
  })
  it('较慢的旧响应不能覆盖已切换学期的结果', async () => {
    const a = deferred<string>(),
      b = deferred<string>(),
      changes: string[] = []
    const { run } = useAsync()
    const first = run(
      () => a.promise,
      (text) => changes.push(text)
    )
    const second = run(
      () => b.promise,
      (text) => changes.push(text)
    )
    b.resolve('春季')
    await second
    a.resolve('秋季')
    await first
    expect(changes).toEqual(['春季'])
  })
  it('旧查询失败不能结束较新的加载或弹出陈旧错误', async () => {
    const a = deferred<string>(),
      b = deferred<string>()
    const { run, loading, error } = useAsync()
    const first = run(() => a.promise),
      second = run(() => b.promise)
    a.reject(new Error('旧请求'))
    await first
    expect(loading.value).toBe(true)
    expect(error.value).toBe('')
    b.resolve('done')
    await second
    expect(loading.value).toBe(false)
  })
  it('超过一万条档案的精确查找只取一页', async () => {
    const data = { items: [{ id: 'S10001' }], page: 0, size: 20, total: 1 }
    const request = vi.fn().mockResolvedValue(data)
    await expect(findRecords('/admin/students', ' S10001 ', 0, request)).resolves.toEqual(data)
    expect(request).toHaveBeenCalledTimes(1)
    const [path, search] = request.mock.calls[0]![0].split('?')
    expect(path).toBe('/admin/students')
    expect(Object.fromEntries(new URLSearchParams(search))).toEqual({ q: 'S10001', size: '20', page: '0' })
  })
})
