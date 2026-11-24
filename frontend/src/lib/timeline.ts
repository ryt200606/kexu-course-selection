import type { Meeting } from './api'

export interface CalendarSlot extends Meeting {sectionId:string;courseTitle:string;selected:boolean}
export function weekAgenda(rows: CalendarSlot[], week:number) {
  const active = rows.filter(r=>r.startWeek<=week && week<=r.endWeek)
  return active.filter(r=>r.selected).map(row=>({
    ...row,
    conflicts:[...new Set(active.filter(other=>other.sectionId!==row.sectionId && other.weekday===row.weekday && other.startPeriod<=row.endPeriod && row.startPeriod<=other.endPeriod).map(other=>other.courseTitle+' · '+other.sectionId))]
  })).sort((a,b)=>a.weekday-b.weekday||a.startPeriod-b.startPeriod||a.sectionId.localeCompare(b.sectionId))
}
