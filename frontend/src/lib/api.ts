export type Role = 'ADMIN' | 'TEACHER' | 'STUDENT'
export interface User { id: string; username: string; displayName: string; role: Role; studentId?: string | null; teacherId?: string | null }
export interface Page<T> { items: T[]; page: number; size: number; total: number }
export interface Term { id: string; name: string; state: string; version?: number }
export interface Meeting { weekday: number; startPeriod: number; endPeriod: number; startWeek: number; endWeek: number; room: string }
export interface Section { id: string; offeringId: string; courseId: string; courseTitle: string; termId: string; termName: string; credits: number; department: string; description?: string; teacherId: string; teacherName: string; capacity: number; enrolledCount: number; waitingCount: number; promotionPending: boolean; state: string; gradeState: string; version: number; meetings: Meeting[]; prerequisites?: { courseId: string; courseTitle?: string; minGrade: number }[] }
export interface Enrollment { id: string; studentId: string; studentName: string; sectionId: string; courseId: string; courseTitle: string; termId: string; termName: string; credits: number; teacherName: string; state: string; grade: number | null; gradeVersion: number; gradeState: string; meetings: Meeting[] }
export interface Waitlist { id: string; sectionId: string; courseTitle: string; roundId: string; queueNo: number; position: number; state: string; reason: string | null; createdAt: string }
export interface Notification { id: string; title: string; message: string; createdAt: string; readAt: string | null }
export interface Dashboard { students: number; courses: number; sections: number; enrollments: number; waiting: number; selectedCredits: number; creditLimit: number; averageGrade: number | null; passRate: number | null; gradeDistribution: { label: string; count: number }[]; capacity: { sectionId: string; title: string; enrolledCount: number; capacity: number }[] }
export interface StudentSummary { studentId: string; name: string; department: string; termId: string; selectedCredits: number; creditLimit: number; earnedCredits: number; waitingCount: number }
export interface Round { id: string; termId: string; name: string; state: string; startsAt: string; endsAt: string; withdrawalEndsAt: string; creditLimit: number }
export interface Eligibility { eligible: boolean; canEnroll: boolean; canWaitlist: boolean; reasons: { code: string; message: string; relatedSectionIds?: string[] }[] }
export interface GradeRow { enrollmentId: string; studentId: string; studentName: string; grade: number | null; publishedGrade?: number | null; version: number }
export interface GradeSheet { sectionId: string; gradeState: string; version: number; items: GradeRow[] }
export interface Health { status: string; database: string; serverVersion: string; schemaVersion: number; surrogate: boolean }
export type Row = Record<string, unknown>

export class ApiError extends Error { constructor(public status: number, public code: string, message: string, public fieldErrors: Record<string, string> = {}, public requestId = '') { super(message) } }
let csrfToken = ''
export function query(params: Record<string, unknown>) { const q = new URLSearchParams(); for (const [key, value] of Object.entries(params)) if (value !== '' && value !== undefined && value !== null) q.set(key, String(value)); return q.size ? '?' + q.toString() : '' }
export const encode = encodeURIComponent
export function newKey() {
  if (typeof crypto.randomUUID === 'function') return 'web-' + crypto.randomUUID()
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  return 'web-' + Array.from(bytes, value => value.toString(16).padStart(2, '0')).join('')
}
async function csrf() { const response = await fetch('/api/v2/auth/csrf', { credentials: 'same-origin' }); if (!response.ok) throw new ApiError(response.status, 'CSRF_UNAVAILABLE', '暂时无法建立安全会话，请稍后重试。'); const result = await response.json() as { token: string }; csrfToken = result.token }
export async function refreshCsrf() { csrfToken = ''; await csrf() }
export interface ApiOptions { method?: string; body?: unknown; idempotent?: boolean; key?: string; signal?: AbortSignal; version?: 2 | 3 }
type Transport = (path: string, options: ApiOptions) => Promise<unknown>
let localTransport: Transport | undefined
export function configurePreviewTransport(transport: Transport) { localTransport = transport }
export async function api<T>(path: string, options: ApiOptions = {}): Promise<T> {
  if (localTransport) return await localTransport(path, options) as T
  const method = options.method || 'GET', writing = !['GET', 'HEAD'].includes(method)
  if (writing && !csrfToken) await csrf()
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'
  if (writing) headers['X-CSRF-TOKEN'] = csrfToken
  if (options.idempotent) headers['Idempotency-Key'] = options.key || newKey()
  const controller = new AbortController(), timer = window.setTimeout(() => controller.abort(), 20000)
  const abort = () => controller.abort()
  options.signal?.addEventListener('abort', abort, { once: true })
  try {
    const response = await fetch('/api/v' + (options.version || 2) + path, { method, headers, credentials: 'same-origin', body: options.body === undefined ? undefined : JSON.stringify(options.body), signal: controller.signal })
    if (!response.ok) {
      const result = await response.json().catch(() => ({})) as { code?: string; message?: string; fieldErrors?: Record<string, string>; requestId?: string }
      if (response.status === 401 && !path.startsWith('/auth/')) window.dispatchEvent(new Event('course:session-expired'))
      throw new ApiError(response.status, result.code || 'REQUEST_FAILED', result.message || '服务暂时不可用，请稍后重试。', result.fieldErrors || {}, result.requestId || response.headers.get('X-Request-ID') || '')
    }
    return response.status === 204 ? undefined as T : await response.json() as T
  } catch (error) {
    if (error instanceof ApiError) throw error
    if (error instanceof DOMException && error.name === 'AbortError') throw new ApiError(0, 'TIMEOUT', '请求等待超时。提交结果可能已生效，请先刷新确认，再决定是否重试。')
    throw new ApiError(0, 'NETWORK_ERROR', '暂时无法连接服务。请检查连接后重试。')
  } finally { window.clearTimeout(timer); options.signal?.removeEventListener('abort', abort) }
}
export async function allPages<T>(path: string): Promise<T[]> {
  const separator = path.includes('?') ? '&' : '?', items: T[] = []
  for (let page = 0; page < 100; page++) { const result = await api<Page<T>>(path + separator + 'size=100&page=' + page); items.push(...result.items); if (items.length >= result.total || !result.items.length) return items }
  throw new Error('记录超过当前操作支持的范围，请缩小学期或筛选条件。')
}
export function meetingText(meeting: Meeting) { return `周${'一二三四五六日'[meeting.weekday - 1]} ${meeting.startPeriod}—${meeting.endPeriod}节 · ${meeting.room} · ${meeting.startWeek}—${meeting.endWeek}周` }
export const sectionStatus = (section: Section) => section.promotionPending ? '候补递补中' : section.enrolledCount >= section.capacity ? '名额已满' : '可以选课'
