import { describe, expect, it } from 'vitest'
import { parseGradePaste } from './grades'
const ids = new Set(['S1001', 'S1002', 'S1003'])
describe('教师批量成绩输入', () => {
  it('接受带BOM标题与表格制表符，并保持空成绩语义', () => { const result = parseGradePaste('\uFEFF学号,成绩\r\nS1001\t0\r\nS1002\t100.00\r\nS1003,', ids); expect([...result]).toEqual([['S1001', 0], ['S1002', 100], ['S1003', null]]) })
  it('整个批次出现不属于当前班级的学生时拒绝', () => { expect(() => parseGradePaste('S1001,90\nS9999,80', ids)).toThrow('不在当前班级') })
  it.each(['S1001,100.01', 'S1001,-1', 'S1001,1.234', 'S1001,NaN', 'S1001,1e2', 'S1001,=100', 'S1001,89,90'])('拒绝越界、精度或不明确输入：%s', text => { expect(() => parseGradePaste(text, ids)).toThrow() })
  it('拒绝重复学号，避免后面的行悄悄覆盖前面的行', () => { expect(() => parseGradePaste('S1001,70\nS1001,90', ids)).toThrow('重复') })
  it('拒绝只有标题或空白的无成绩批次', () => { expect(() => parseGradePaste('学号,成绩', ids)).toThrow('未找到'); expect(() => parseGradePaste('   \n', ids)).toThrow('先填入') })
})
