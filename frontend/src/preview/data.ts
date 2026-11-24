import { ref } from 'vue'
import { ApiError, type Role, type ApiOptions, type Section, type Enrollment } from '../lib/api'
import type { DraftPlans, GradeReview } from '../lib/v3'

export const previewRole = ref<Role>('STUDENT')
export const names: Record<Role, string> = { STUDENT: '林知远', TEACHER: '江予宁', ADMIN: '周明书' }
const term = 'DEMO-2026',
  round = 'DEMO-ROUND'
const slots = (day: number, room: string) => [
  { weekday: day, room, startPeriod: 1, endPeriod: 2, startWeek: 1, endWeek: 16 },
]
const base = {
  termId: term,
  termName: '2026 秋季学期',
  department: '计算机学院',
  credits: 3,
  teacherId: 'T01',
  teacherName: names.TEACHER,
  capacity: 32,
  enrolledCount: 21,
  waitingCount: 0,
  promotionPending: false,
  state: 'PUBLISHED',
  gradeState: 'DRAFT',
  version: 1,
  description: '使用虚构教室和课程资料展示学期教学流程。',
  prerequisites: [],
}
export const sections: Section[] = [
  {
    ...base,
    id: 'DB-A',
    offeringId: 'O-DB',
    courseId: 'DB',
    courseTitle: '数据库系统原理',
    meetings: slots(1, '知行楼 201'),
  },
  {
    ...base,
    id: 'DS-A',
    offeringId: 'O-DS',
    courseId: 'DS',
    courseTitle: '数据结构',
    meetings: slots(1, '知行楼 302'),
  },
  {
    ...base,
    id: 'DS-B',
    offeringId: 'O-DS',
    courseId: 'DS',
    courseTitle: '数据结构',
    teacherId: 'T02',
    teacherName: '陈亦舟',
    meetings: slots(3, '知行楼 302'),
  },
  {
    ...base,
    id: 'OS-A',
    offeringId: 'O-OS',
    courseId: 'OS',
    courseTitle: '操作系统',
    meetings: slots(5, '知行楼 204'),
  },
]
function sampleEnrollment(s: Section, id: string, grade: number | null): Enrollment {
  return {
    id,
    studentId: 'S01',
    studentName: names.STUDENT,
    sectionId: s.id,
    courseId: s.courseId,
    courseTitle: s.courseTitle,
    termId: term,
    termName: s.termName,
    credits: 3,
    teacherName: s.teacherName,
    state: 'ENROLLED',
    grade,
    gradeVersion: 1,
    gradeState: 'PUBLISHED',
    meetings: s.meetings,
  }
}
const starter: GradeReview = {
  id: 'F-001',
  sectionId: 'DB-A',
  studentId: 'S01',
  studentName: names.STUDENT,
  courseTitle: '数据库系统原理',
  originalGrade: 59.99,
  proposedGrade: null,
  state: 'REQUESTED',
  version: 1,
  studentReason: '请复核综合实验评分的录入结果。',
}
interface LocalData {
  plans: DraftPlans
  enrolled: Enrollment[]
  reviews: GradeReview[]
  ranked: string[]
  preferenceVersion: number
}
function seed(): LocalData {
  return {
    plans: { left: ['DB-A', 'DS-A'], right: ['DB-A', 'DS-B'], version: 1 },
    enrolled: [sampleEnrollment(sections[0]!, 'E01', 59.99), sampleEnrollment(sections[3]!, 'E02', 89.5)],
    reviews: [{ ...starter }],
    ranked: ['DB-A', 'DS-B'],
    preferenceVersion: 1,
  }
}
const key = 'kexu-v3-static-preview-v1'
function read(): LocalData {
  try {
    const v = sessionStorage.getItem(key)
    if (v) return JSON.parse(v) as LocalData
  } catch {
    /* storage is optional */
  }
  return seed()
}
let data = read()
function persist() {
  try {
    sessionStorage.setItem(key, JSON.stringify(data))
  } catch {
    /* transient session */
  }
}
export function resetPreview() {
  data = seed()
  persist()
}
function fail(message: string, status = 409): never {
  throw new ApiError(status, 'PREVIEW_VALIDATION', message)
}
function page<T>(rows: T[], url: URL) {
  const size = Number(url.searchParams.get('size') || 20),
    p = Number(url.searchParams.get('page') || 0)
  return { items: rows.slice(p * size, p * size + size), page: p, size, total: rows.length }
}
const students = [{ id: 'S01', name: names.STUDENT, department: '计算机学院' }]
const courses = sections.filter((s) => s.id !== 'DS-B').map((s) => ({ id: s.courseId, title: s.courseTitle }))
function compare(ids: string[]) {
  const seen: Section[] = []
  const items = ids.map((id) => {
    const s = sections.find((x) => x.id === id)
    if (!s) fail('样例教学班不存在', 404)
    const conflict = seen.find(
      (x) => x.meetings[0]?.weekday === s.meetings[0]?.weekday || x.courseId === s.courseId
    )
    seen.push(s)
    return {
      sectionId: id,
      courseTitle: s.courseTitle,
      eligible: !conflict,
      reasons: conflict ? ['与 ' + conflict.courseTitle + '（' + conflict.id + '）在教学周或课程上重叠'] : [],
      alternatives: conflict
        ? sections
            .filter((x) => x.courseId === s.courseId && x.id !== id)
            .map((x) => ({ sectionId: x.id, teacherName: x.teacherName }))
        : [],
    }
  })
  return { credits: seen.reduce((n, s) => n + s.credits, 0), feasible: items.every((i) => i.eligible), items }
}
export async function previewData(path: string, options: ApiOptions): Promise<unknown> {
  const url = new URL(path, 'https://preview.invalid'),
    p = url.pathname,
    q = (url.searchParams.get('q') || '').toLowerCase(),
    method = options.method || 'GET'
  const body = (options.body || {}) as Record<string, unknown>
  if (p === '/terms') return page([{ id: term, name: '2026 秋季学期', state: 'ACTIVE' }], url)
  if (p === '/rounds')
    return page(
      [
        {
          id: round,
          termId: term,
          state: 'OPEN',
          name: '秋季第一轮',
          startsAt: new Date(Date.now() - 86400000).toISOString(),
          endsAt: new Date(Date.now() + 86400000).toISOString(),
          withdrawalEndsAt: new Date(Date.now() + 172800000).toISOString(),
          creditLimit: 24,
        },
      ],
      url
    )
  if (p === '/catalog' || p === '/admin/sections')
    return page(
      sections.filter((x) => [x.courseTitle, x.id, x.teacherName].join(' ').toLowerCase().includes(q)),
      url
    )
  if (p === '/admin/students' || p === '/admin/courses' || p === '/admin/teachers')
    return page(
      (p.endsWith('students')
        ? students
        : p.endsWith('courses')
          ? courses
          : [{ id: 'T01', name: names.TEACHER }]
      ).filter((x) => JSON.stringify(x).toLowerCase().includes(q)),
      url
    )
  if (p.startsWith('/sections/'))
    return sections.find((x) => x.id === p.split('/').pop()) || fail('教学班不存在', 404)
  if (p.endsWith('/eligibility')) return { eligible: true, canEnroll: true, canWaitlist: false, reasons: [] }
  if (p === '/student/enrollments' && method === 'GET') return page(data.enrolled, url)
  if (p === '/student/enrollments' && method === 'POST') {
    const s = sections.find((x) => x.id === body.sectionId)
    if (!s || data.enrolled.some((x) => x.sectionId === s.id)) fail('这个教学班已经选入或不可选')
    data.enrolled.push(sampleEnrollment(s, 'E' + Date.now(), null))
    persist()
    return { sectionId: s.id }
  }
  if (p === '/student/timetable') return {items:data.enrolled.filter(e=>e.grade==null).flatMap(e=>e.meetings.map(m=>({...m,sectionId:e.sectionId,courseTitle:e.courseTitle})))}
  if (p === '/student/summary')
    return {
      studentId: 'S01',
      name: names.STUDENT,
      department: '计算机学院',
      termId: term,
      creditLimit: 24,
      selectedCredits: data.enrolled.length * 3,
      earnedCredits: data.enrolled.filter((e) => e.grade !== null && e.grade >= 60).length * 3,
      waitingCount: 0,
    }
  if (p === '/student/curriculum') {
    const earned = data.enrolled.filter((e) => e.grade !== null && e.grade >= 60).length * 3
    return {
      curriculum: { id: 'C-26', name: '计算机科学与技术', cohort: '2026', version: 1 },
      earnedCredits: earned,
      requiredCredits: 9,
      remainingCredits: 9 - earned,
      modules: [
        {
          name: '专业核心课程',
          requiredCredits: 6,
          earnedCredits: earned,
          remainingCredits: Math.max(0, 6 - earned),
          courses: [
            {
              courseId: 'DB',
              title: '数据库系统原理',
              passed: (data.enrolled[0]?.grade || 0) >= 60,
              credits: 3,
            },
            { courseId: 'OS', title: '操作系统', passed: true, credits: 3 },
          ],
        },
        {
          name: '算法与数据',
          requiredCredits: 3,
          earnedCredits: 0,
          remainingCredits: 3,
          courses: [{ courseId: 'DS', title: '数据结构', passed: false, credits: 3 }],
        },
      ],
    }
  }
  if (p === '/student/plans') {
    if (method === 'PUT') {
      if (body.version !== data.plans.version) fail('方案已有新版本，请重新读取')
      data.plans = {
        left: [...(body.left as string[])],
        right: [...(body.right as string[])],
        version: data.plans.version + 1,
      }
      persist()
    }
    return structuredClone(data.plans)
  }
  if (p === '/student/plans/compare')
    return { left: compare(body.left as string[]), right: compare(body.right as string[]) }
  if (p.startsWith('/student/preferences/')) {
    if (method === 'PUT') {
      if (body.version !== data.preferenceVersion) fail('志愿版本冲突')
      data.ranked = [...(body.sectionIds as string[])]
      data.preferenceVersion++
      persist()
    }
    return {
      roundId: round,
      sectionIds: [...data.ranked],
      version: data.preferenceVersion,
      phase: 'COLLECTING',
      assignments: [],
      seedCommitment: '本轮开放前已登记 · 静态教学样例',
    }
  }
  if (p === '/grade-reviews') {
    const state = url.searchParams.get('state')
    return page(
      data.reviews.filter((r) => !state || r.state === state),
      url
    )
  }
  if (p.startsWith('/grade-reviews/'))
    return { ...data.reviews.find((x) => x.id === p.split('/').pop()), history: [] }
  if (p === '/student/grade-reviews') {
    const enrollment = data.enrolled.find((e) => e.id === body.enrollmentId)
    if (!enrollment || enrollment.grade === null) fail('请选择已发布成绩')
    if (
      data.reviews.some(
        (r) => r.sectionId === enrollment.sectionId && !['REJECTED', 'PUBLISHED'].includes(r.state)
      )
    )
      fail('该课程正在复核')
    const r = {
      ...starter,
      id: 'F-' + Date.now(),
      sectionId: enrollment.sectionId,
      courseTitle: enrollment.courseTitle,
      originalGrade: enrollment.grade,
      studentReason: String(body.reason),
    }
    data.reviews.unshift(r)
    persist()
    return r
  }
  if (p.includes('/grade-reviews/')) {
    const bits = p.split('/'),
      r = data.reviews.find((x) => x.id === bits[3])
    if (!r) fail('复核不存在', 404)
    if (r.version !== body.version) fail('版本已变化，请重新读取')
    if (bits[4] === 'recommend' && previewRole.value === 'TEACHER' && r.state === 'REQUESTED') {
      r.proposedGrade = Number(body.proposedGrade)
      r.teacherReason = String(body.reason)
      r.state = 'RECOMMENDED'
      r.crossesPassThreshold = r.originalGrade >= 60 !== r.proposedGrade >= 60
    } else if (bits[4] === 'decide' && previewRole.value === 'ADMIN' && r.state === 'RECOMMENDED') {
      r.state = body.approve ? 'APPROVED' : 'REJECTED'
      r.adminReason = String(body.reason)
    } else if (bits[4] === 'publish' && previewRole.value === 'TEACHER' && r.state === 'APPROVED') {
      r.state = 'PUBLISHED'
      const e = data.enrolled.find((e) => e.sectionId === r.sectionId)
      if (e) e.grade = r.proposedGrade ?? r.originalGrade
    } else fail('该角色或当前状态不支持此操作')
    r.version++
    persist()
    return r
  }
  if (p === '/admin/analytics/settlement') {
    const metric = url.searchParams.get('metric') || 'UNASSIGNED',
      sample = metric === 'WAIT_TIME' ? [240, 2] : metric === 'PREFERENCE' ? [2, 4] : [0, 2]
    const [n = 0, d = 0] = sample
    return {
      metric,
      definition: '当前静态样例的单轮分子与分母。',
      unit: metric === 'WAIT_TIME' ? '秒' : '比例',
      numerator: n,
      denominator: d,
      value: d ? n / d : null,
      ...page([{ itemId: round, label: '秋季第一轮', numerator: n, denominator: d }], url),
    }
  }
  if (p === '/admin/analytics/drill') {
    const metric = url.searchParams.get('metric')
    return page(
      sections.map((s, i) => ({
        sectionId: s.id,
        courseTitle: s.courseTitle,
        teacherName: s.teacherName,
        numerator: metric === 'CAPACITY' ? s.enrolledCount : metric === 'WAITLIST' ? i : Math.max(0, 2 - i),
        denominator: metric === 'GRADES' ? (i === 3 ? 0 : 16) : s.capacity,
      })),
      url
    )
  }
  if (p === '/admin/enrollments')
    return page(
      data.enrolled.filter(
        (e) => !url.searchParams.get('sectionId') || e.sectionId === url.searchParams.get('sectionId')
      ),
      url
    )
  if (p === '/admin/planning/runs/SAMPLE-01')
    return {
      id: 'SAMPLE-01',
      kind: 'SCHEDULE',
      state: 'PUBLISHED',
      solverStatus: 'OPTIMAL',
      snapshotHash: '静态快照 · 不代表服务器验收',
      metrics: { scheduled: 2, requested: 2 },
      greedy: { scheduled: 1 },
      assignments: [
        { sectionId: 'DB-A', teacherId: 'T01', slot: slots(1, '知行楼 201')[0] },
        { sectionId: 'DS-B', teacherId: 'T02', slot: slots(3, '知行楼 302')[0] },
      ],
      excluded: [],
    }
  fail('该操作需要运行本地三层部署。公开预览只使用浏览器内的虚构数据。', 422)
}
