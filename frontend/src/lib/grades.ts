export function parseGradePaste(text: string, allowedIds: Set<string>): Map<string, number | null> {
  const result = new Map<string, number | null>()
  const lines = text.replace(/^\uFEFF/, '').split(/\r?\n/).filter(line => line.trim())
  if (!lines.length) throw new Error('请先填入学号与成绩。')
  for (const [index, line] of lines.entries()) {
    const parts = line.trim().split(/[,，\t]/).map(part => part.trim())
    if (index === 0 && ['学号', 'studentId'].includes(parts[0] || '')) continue
    if (parts.length !== 2 || !parts[0]) throw new Error(`第 ${index + 1} 行格式不正确，请使用“学号,成绩”。`)
    const id = parts[0]!, value = parts[1]!
    if (!allowedIds.has(id)) throw new Error(`第 ${index + 1} 行的学号 ${id} 不在当前班级中。`)
    if (result.has(id)) throw new Error(`学号 ${id} 重复出现，请检查后重试。`)
    if (value !== '' && (!/^\d{1,3}(\.\d{1,2})?$/.test(value) || Number(value) > 100)) throw new Error(`学号 ${id} 的成绩必须为0—100，最多两位小数。`)
    result.set(id, value === '' ? null : Number(value))
  }
  if (!result.size) throw new Error('未找到可填入的成绩行。')
  return result
}
