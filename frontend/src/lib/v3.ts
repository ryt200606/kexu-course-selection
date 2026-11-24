import { api } from './api'
export function v3<T>(path: string, options: Parameters<typeof api>[1] = {}) {
  return api<T>(path, { ...options, version: 3 })
}
export interface Curriculum {
  curriculum: { id: string; name: string; cohort: string; version: number } | null
  earnedCredits: number
  requiredCredits: number
  remainingCredits: number
  modules: {
    name: string
    requiredCredits: number
    earnedCredits: number
    remainingCredits: number
    courses: { courseId: string; title: string; passed: boolean; credits: number }[]
  }[]
}
export interface DraftPlans {
  left: string[]
  right: string[]
  version: number
}
export interface PlanResult {
  credits: number
  feasible: boolean
  items: {
    sectionId: string
    courseTitle: string
    eligible: boolean
    reasons: string[]
    alternatives: { sectionId: string; teacherName: string }[]
  }[]
}
export interface GradeReview {
  id: string
  sectionId: string
  studentId: string
  studentName: string
  courseTitle: string
  originalGrade: number
  proposedGrade?: number | null
  studentReason: string
  teacherReason?: string
  adminReason?: string
  state: string
  version: number
  crossesPassThreshold?: boolean
  history?: { action: string; actorName: string; message: string; createdAt: string }[]
}
export const reviewStates: Record<string, string> = {
  SUBMITTED: '待教师核查',
  REQUESTED: '待教师核查',
  OPEN: '待教师核查',
  PENDING: '待教师核查',
  RECOMMENDED: '待教务审核',
  APPROVED: '待教师发布',
  REJECTED: '已驳回',
  PUBLISHED: '已更正发布',
  STALE: '已失效',
}
export function statusName(state: string) {
  return reviewStates[state] || state
}
