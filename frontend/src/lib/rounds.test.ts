import { describe, expect, it } from 'vitest'
import { isRoundOpen } from './rounds'
import type { Round } from './api'
const round: Round = { id: 'R1', termId: 'T1', name: '选课轮次', state: 'OPEN', startsAt: '2026-11-01T10:00:00Z', endsAt: '2026-11-01T11:00:00Z', withdrawalEndsAt: '2026-11-01T12:00:00Z', creditLimit: 24 }
describe('首页开放轮次实际时间边界', () => {
  it('状态OPEN但尚未到开始时间时不宣称开放', () => { expect(isRoundOpen(round, Date.parse('2026-11-01T09:59:59Z'))).toBe(false) })
  it('开始时刻包含，截止时刻不包含', () => { expect(isRoundOpen(round, Date.parse(round.startsAt))).toBe(true); expect(isRoundOpen(round, Date.parse(round.endsAt))).toBe(false) })
  it('已经关闭的轮次和无效时间都不能显示开放', () => { expect(isRoundOpen({ ...round, state: 'CLOSED' }, Date.parse('2026-11-01T10:30:00Z'))).toBe(false); expect(isRoundOpen({ ...round, endsAt: 'invalid' })).toBe(false) })
})
