<script setup lang="ts">
import {computed,ref,watch} from 'vue'
import {api,encode,query,type Section,type Meeting} from '../lib/api'
import {weekAgenda,type CalendarSlot} from '../lib/timeline'
import {useAsync} from '../lib/useAsync'
interface Lesson extends Meeting {sectionId:string;courseTitle:string}
const props=defineProps<{ ids:string[];termId:string }>()
const week=ref(1),slots=ref<CalendarSlot[]>([]),days=['周一','周二','周三','周四','周五','周六','周日'],{run,loading,error}=useAsync()
const agenda=computed(()=>weekAgenda(slots.value,week.value))
function load(){
  const ids=[...props.ids]
  if(!ids.length){slots.value=[];return}
  return run(()=>Promise.all([Promise.all(ids.map(id=>api<Section>('/sections/'+encode(id)))), api<{items:Lesson[]}>('/student/timetable'+query({termId:props.termId}))]),([details,enrolled])=>{
    slots.value=[...details.flatMap(s=>s.meetings.map(m=>({...m,sectionId:s.id,courseTitle:s.courseTitle,selected:true}))),...enrolled.items.filter(e=>!ids.includes(e.sectionId)).map(m=>({...m,selected:false}))]
  })
}
watch([()=>props.ids,()=>props.termId],load,{immediate:true,deep:true})
</script>
<template>
  <details class="workflow-details" open><summary>按教学周定位课时</summary>
    <label class="field">教学周<select v-model.number="week"><option v-for="n in 30" :key="n" :value="n">第 {{ n }} 周</option></select></label>
    <p v-if="loading">正在获取课表…</p><p v-else-if="error" class="error-inline">{{ error }} <button class="text-button" @click="load">重试</button></p>
    <ul v-else-if="agenda.length" class="record-list"><li v-for="(m,i) in agenda" :key="i"><span><strong>{{ days[m.weekday-1] }} · 第 {{ m.startPeriod }}—{{ m.endPeriod }} 节</strong><small>{{ m.courseTitle }} · {{ m.room }}</small><small v-if="m.conflicts.length" class="text-red">同时段冲突：{{ m.conflicts.join('、') }}</small></span></li></ul>
    <p v-else>第 {{ week }} 周没有该方案的上课安排。</p>
  </details>
</template>