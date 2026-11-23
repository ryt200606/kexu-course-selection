export const roleLabel = (role: string) => ({ STUDENT: '学生', TEACHER: '教师', ADMIN: '教务管理员' }[role.toUpperCase()] || role)
export function avatarText(name?: string | null) { return name?.replace(/\s*[（(]演示[）)]\s*$/, '').trim().slice(0, 1) || '课' }
export function formatTime(value: unknown) { if (!value) return '—'; const date = new Date(String(value)); return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) }
export function formatDate(value: unknown) { if (!value) return '—'; const date = new Date(String(value)); return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleDateString('zh-CN') }
export function display(value: unknown) { return value === null || value === undefined || value === '' ? '—' : String(value) }
export function gradeLabel(value: unknown) { return value == null ? '尚未发布' : Number(value).toFixed(1) }
export function csvText(rows: unknown[][]) {
  return '\uFEFF' + rows.map(row => row.map(value => {
    const text = String(value ?? '')
    const safe = typeof value === 'string' && /^\s*[=+\-@]/.test(text) ? "'" + text : text
    return '"' + safe.replaceAll('"', '""') + '"'
  }).join(',')).join('\r\n')
}
export function csvDownload(name: string, rows: unknown[][]) {
  const csv = csvText(rows)
  const link = document.createElement('a'), url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }))
  link.href = url; link.download = name; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
}
