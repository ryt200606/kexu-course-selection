import {describe,expect,it} from 'vitest'
import {weekAgenda,type CalendarSlot} from './timeline'
const a:CalendarSlot={sectionId:'A',courseTitle:'数据库',weekday:1,startWeek:1,endWeek:5,startPeriod:1,endPeriod:2,room:'101',selected:true}
describe('试排教学周时段',()=>{
  it('已有课在共同教学周且节次边界相交时标出冲突',()=>{
    const b={...a,sectionId:'B',courseTitle:'数据结构',startPeriod:2,endPeriod:3,startWeek:5,endWeek:8,selected:false}
    expect(weekAgenda([a,b],5)[0]?.conflicts).toEqual(['数据结构 · B'])
    expect(weekAgenda([a,b],4)[0]?.conflicts).toEqual([])
    expect(weekAgenda([a,b],6)).toEqual([])
  })
  it('紧邻节次、不同星期及同一班重复读到的数据不会误报',()=>{
    expect(weekAgenda([a,{...a,sectionId:'B',startPeriod:3,endPeriod:4},{...a,sectionId:'C',weekday:2},{...a,selected:false}],2)[0]?.conflicts).toEqual([])
  })
})
