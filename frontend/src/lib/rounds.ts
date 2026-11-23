import type { Round } from './api'
export function isRoundOpen(round: Round, now = Date.now()) {
  const start = Date.parse(round.startsAt), end = Date.parse(round.endsAt)
  return round.state === 'OPEN' && Number.isFinite(start) && Number.isFinite(end) && now >= start && now < end
}
