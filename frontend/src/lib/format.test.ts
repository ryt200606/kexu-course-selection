import { describe, expect, it } from 'vitest'
import { csvText, avatarText } from './format'
import { query, meetingText } from './api'
describe('真实数据导出与筛选契约', () => {
  it('演示后缀不会成为头像文字，空身份使用品牌占位', () => { expect(avatarText('林知远（演示）')).toBe('林'); expect(avatarText('杨老师 (演示)')).toBe('杨'); expect(avatarText('教务管理员（演示）')).toBe('教'); expect(avatarText(null)).toBe('课') })
  it('CSV保留逗号、引号、中文和空成绩，含Excel可识别BOM', () => { expect(csvText([['课程', '分数'], ['数据库,"原理"', null], ['网络', 0]])).toBe('\uFEFF"课程","分数"\r\n"数据库,""原理""",""\r\n"网络","0"') })
  it('导出师生输入内容时阻断表格公式，不改变数值', () => { expect(csvText([['=HYPERLINK("x")', ' @SUM(A1)', -1, 0]])).toContain('"\'=HYPERLINK'); expect(csvText([[' @SUM(A1)']])).toContain("' @SUM(A1)"); expect(csvText([[-1, 0]])).toBe('\uFEFF"-1","0"') })
  it('分页0和false必须保留，空筛选省略，特殊关键词被编码', () => { expect(query({ page: 0, availableOnly: false, empty: '', absent: undefined, q: '数据库 & SQL' })).toBe('?page=0&availableOnly=false&q=%E6%95%B0%E6%8D%AE%E5%BA%93+%26+SQL') })
  it('完整展示教学周、节次与教室，避免课表隐藏冲突条件', () => { expect(meetingText({ weekday: 7, startPeriod: 10, endPeriod: 12, startWeek: 2, endWeek: 16, room: '教101' })).toBe('周日 10—12节 · 教101 · 2—16周') })
})
