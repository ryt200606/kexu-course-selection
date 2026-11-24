<script setup lang="ts">
import { computed, ref } from 'vue'
import PageHeading from '../components/PageHeading.vue'
import StatePanel from '../components/StatePanel.vue'
import AnalyticsChart from '../components/AnalyticsChart.vue'
interface Measurement {
  medianMs: number
  samplesMs: number[]
}
interface Sql {
  phases: { base: Record<string, Measurement>; v3: Record<string, Measurement> }
  extraIndexBytes?: Record<string,number>
  environment?:string
  dataset?:{students:number;sections:number;enrollments:number}
}
interface SolverStage { name:string;status:string;objective:number;bestBound:number;elapsedMs:number }
interface Solver { os:string;java:string;ortools:string;threads:number;seed:number;
  allocation:{students:number;sections:number;choices:number;solverStatus:string;elapsedMs:number;metrics:{covered:number;rankHits:number[];assigned:number};greedy:{covered:number;rankHits:number[];assigned:number};stages:SolverStage[]}[];
  scheduling:{sections:number;rooms:number;scheduled:number;greedyScheduled:number;teacherGapPeriods:number;greedyTeacherGapPeriods:number;solverStatus:string;elapsedMs:number;stages:SolverStage[]}[] }
interface Ceiling {
  solverBudgetSeconds: number
  workerThreads: number
  machine: string
  stages: {students:number;choices:number;elapsedMs:number;solverStatus:string;lexicographicStages:SolverStage[]}[]
}
interface Cycle {
  requestsPerSecond: number
  read: { p95Ms: number }
  write: { p95Ms: number }
  technicalErrorRate: number
  businessRefusals:number
  passed:boolean
  mismatches: Record<string, number>
}
interface Condition { requestsPerSecond:number;readP95Ms:number;writeP95Ms:number;technicalErrorRate:number }
interface Contention {status:string;clients:number;mode:string;mix:string;secondsPerRound:number;medianByCondition:{alone:Condition;solver:Condition};finalReconciliation:Record<string,number>;rounds:{pair:number;condition:'solver'|'alone';solverOverlapSeconds?:number;overlapFraction?:number}[]}
interface SampledLevel {
  clients: number
  resourceSampleCount: number
  peakObservedWaitingLocks: number
  samplesWithWaitingLocks: number
  databasePeakCpuPct: number
  databasePeakMiB: number
  apiPeakCpuPct: number
  apiPeakMiB: number
}
interface Resources { machine: string; profiles: Record<string, { stages: SampledLevel[] }> }
interface Load {
  profile:string;mode:string;mix:string;seed:number;minimumStageSeconds:number
  goals:{readP95Ms:number;writeP95Ms:number;technicalErrorRateExclusive:number}
  status: string
  highestPassing: number | null
  firstNonPassing: number | null
  stages: { clients: number; passed: boolean; rounds: Cycle[] }[]
}
const base = import.meta.env.BASE_URL + 'measurements/',
  source = 'https://github.com/ryt200606/kexu-course-selection/tree/main/performance'
const modes = [
  ['sql-matrix', 'SQL 实测'],
  ['uniform', '均匀负载'],
  ['hot', '热点负载'],
  ['solver','求解器对照'],
  ['solver-contention','同机争用'],
] as const
const mode = ref<string>('sql-matrix'),
  records = ref<Record<string, Sql | Load | Solver | Contention | Resources | Ceiling>>({}),
  busy = ref(false),
  error = ref('')
const sql = computed(() => records.value['sql-matrix'] as Sql | undefined),
  load = computed(() => records.value[mode.value] as Load | undefined)
const pressure=computed(()=>records.value['solver-contention'] as Contention|undefined)
const ceiling=computed(()=>records.value['allocation-ceiling'] as Ceiling|undefined)
const conditionKeys=['alone','solver'] as const
const solverChoice=ref('planning-linux')
const availableSolvers=computed(()=>['planning-linux','planning-windows-20261124'].filter(k=>records.value[k]))
const chosenSolver=computed(()=>availableSolvers.value.includes(solverChoice.value)?solverChoice.value:availableSolvers.value[0])
const solver=computed(()=>chosenSolver.value?records.value[chosenSolver.value] as Solver:undefined)
const present=computed(()=>mode.value==='solver'?!!solver.value:!!records.value[mode.value])
const solverChart=computed(()=>({
 tooltip:{trigger:'axis'},legend:{data:['求解分配','贪心分配']},grid:{left:55,right:25,top:40,bottom:48},
 xAxis:{type:'category',name:'学生数',nameLocation:'center',nameGap:27,data:solver.value?.allocation.map(r=>String(r.students))||[]},
 yAxis:{type:'value',name:'分配条数'},
 series:[{name:'求解分配',type:'bar',itemStyle:{color:'#3c6f54'},data:solver.value?.allocation.map(r=>r.metrics.assigned)||[]},{name:'贪心分配',type:'bar',itemStyle:{color:'#c0aa78'},data:solver.value?.allocation.map(r=>r.greedy.assigned)||[]}]
}))
const names: Record<string, string> = {
  catalog: '课程目录',
  eligibility: '选课资格',
  roster: '教学班名单',
  admin_offerings: '教务开设查询',
  term_stats: '学期统计',
  write_1000: '写入一千行',
}
async function refresh() {
  busy.value = true
  error.value = ''
  try {
    const index = await fetch(base + 'index.json')
    if (!index.ok) throw new Error('读取测量索引失败')
    const available = (await index.json()) as string[],
      result: Record<string, Sql | Load | Solver | Contention | Resources | Ceiling> = {}
    for (const key of [...modes.map(([k])=>k),'resource-summary','allocation-ceiling','v2-uniform','v2-hot','planning-linux','planning-windows-20261124']) {
      if (!available.includes(key)) continue
      const r = await fetch(base + key + '.json')
      if (!r.ok) throw new Error('读取实测记录失败')
      result[key] = (await r.json()) as Sql | Load | Solver | Contention | Resources | Ceiling
    }
    records.value = result
  } catch (e) {
    error.value = e instanceof Error ? e.message : '实测记录读取失败'
  } finally {
    busy.value = false
  }
}
void refresh()
const queries = computed(() =>
  Object.keys(sql.value?.phases?.base || {}).filter((k) => sql.value?.phases.v3?.[k])
)
const series = computed(() =>
  (load.value?.stages || []).flatMap((s) =>
    (s.rounds || []).map((r, i) => ({ clients: s.clients, stagePassed: s.passed, index: i + 1, ...r }))
  )
)
const baseline=computed(()=>{
  const a=load.value,b=records.value['v2-'+mode.value] as Load|undefined
  if(!a||!b)return
  if(a.profile===b.profile && a.mode===b.mode && a.mix===b.mix && a.seed===b.seed && a.minimumStageSeconds===b.minimumStageSeconds && a.goals.readP95Ms===b.goals.readP95Ms && a.goals.writeP95Ms===b.goals.writeP95Ms && a.goals.technicalErrorRateExclusive===b.goals.technicalErrorRateExclusive)return b
})
const chartMetric = ref<'throughput' | 'read' | 'write'>('throughput')
const chartNames = { throughput: '请求/秒', read: '读 P95（ms）', write: '写 P95（ms）' }
const resources = computed(() => records.value['resource-summary'] as Resources | undefined)
const levels = computed(() => resources.value?.profiles[mode.value]?.stages || [])
const plotted = (r?: Cycle) => !r ? null : chartMetric.value === 'throughput' ? r.requestsPerSecond : r[chartMetric.value].p95Ms
const curve = computed(() => {
  const points=series.value.map(plotted)
  const v2=series.value.map(r=>plotted(baseline.value?.stages.find(s=>s.clients===r.clients)?.rounds[r.index-1]))
  return {tooltip:{trigger:'axis'},legend:{data:baseline.value?['V3','V2']:['V3']},grid:{left:60,right:20,bottom:50,top:44},
    xAxis:{type:'category',name:'并发 / 轮次',nameLocation:'center',nameGap:29,data:series.value.map(r=>r.clients+' / '+r.index)},
    yAxis:{type:'value',name:chartNames[chartMetric.value]},
    series:[{name:'V3',type:'line',data:points,showSymbol:true,lineStyle:{color:'#477754'}},...(baseline.value?[{name:'V2',type:'line',showSymbol:true,connectNulls:false,data:v2,lineStyle:{type:'dashed',color:'#9e7539'}}]:[])]
  }
})
const metric = (n: number | null | undefined, unit = '') =>
  typeof n === 'number' && Number.isFinite(n)
    ? n.toLocaleString('zh-CN', { maximumFractionDigits: 3 }) + unit
    : '—'
</script>
<template>
  <PageHeading
    eyebrow="MEASUREMENT RECORDS"
    title="实测证据"
    description="从公开仓库的运行记录读取查询时间和并发结果。"
    ><a :href="source" class="button button-secondary" rel="noopener noreferrer"
      >查看原始记录 ↗</a
    ></PageHeading
  >
  <nav class="workflow-tabs" aria-label="测量类型">
    <button v-for="[key, title] in modes" :key="key" :aria-pressed="mode === key" @click="mode = key">
      {{ title }}
    </button>
  </nav>
  <StatePanel v-if="busy || error" :loading="busy" :error="error" @retry="refresh" />
  <StatePanel
    v-else-if="!present"
    title="此版本尚未收录该项实测结果"
    description="新的数据库测量记录完成并随源码发布后，此处会显示对应数据。"
  />
  <article v-else-if="mode === 'sql-matrix' && sql" class="card workflow-card">
    <h2>同一查询的多轮耗时</h2>
    <p>
      基线为原索引方案，对照组额外建立两个候选索引。候选索引在评估后未采用。以下中位耗时均以毫秒计；候选索引额外占用
      {{ metric(Object.values(sql.extraIndexBytes || {}).reduce((sum,n)=>sum+n,0), ' 字节') }}。
    </p>
    <p v-if="sql.dataset">数据集 {{ metric(sql.dataset.students) }} 名学生、{{ metric(sql.dataset.sections) }} 个教学班、{{ metric(sql.dataset.enrollments) }} 条选课。{{ sql.environment }}</p>
    <div class="table-wrap">
      <table class="mobile-records">
        <caption class="sr-only">
          SQL 对照测量明细
        </caption>
        <thead>
          <tr>
            <th>查询</th>
            <th>基线中位数</th>
            <th>候选索引中位数</th>
            <th>样本数（基线 / 候选）</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="q in queries" :key="q">
            <td data-label="查询">{{ names[q] || q }}</td>
            <td data-label="基线">{{ metric(sql.phases.base[q]?.medianMs, ' ms') }}</td>
            <td data-label="候选索引">{{ metric(sql.phases.v3[q]?.medianMs, ' ms') }}</td>
            <td data-label="样本数">
              {{ sql.phases.base[q]?.samplesMs?.length }} / {{ sql.phases.v3[q]?.samplesMs?.length }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <details class="workflow-details">
      <summary>展开逐次测量</summary>
      <ul class="record-list">
        <li v-for="q in queries" :key="q">
          <span
            ><strong>{{ names[q] || q }}</strong
            ><small>基线 {{ sql.phases.base[q]?.samplesMs?.join('、') }}</small
            ><small>候选 {{ sql.phases.v3[q]?.samplesMs?.join('、') }}</small></span
          >
        </li>
      </ul>
    </details>
  </article>
  <article v-else-if="mode==='solver' && solver" class="card workflow-card"><h2>求解器与贪心对照</h2>
    <nav v-if="availableSolvers.length>1" class="workflow-tabs" aria-label="求解器实测环境"><button v-for="k in availableSolvers" :key="k" :aria-pressed="k===chosenSolver" @click="solverChoice=k">{{k==='planning-linux'?'课程 Linux 虚拟机':'Windows 本机'}}</button></nav>
    <p>{{ solver.os }} · Java {{ solver.java }} · OR-Tools {{ solver.ortools }} · {{ solver.threads }} 线程 · 固定种子 {{ solver.seed }}。这里测量本地优化器的计算阶段，数据库或 HTTP 响应时间见对应测量。</p>
    <h3>志愿分配</h3><div class="chart-body"><AnalyticsChart :option="solverChart" label="求解器与贪心各规模分配条数，原始数值见下表。"/></div><div class="table-wrap"><table class="mobile-records"><thead><tr><th>学生 / 志愿数</th><th>覆盖学生（求解 / 贪心）</th><th>已分配（求解 / 贪心）</th><th>计算时间</th><th>状态</th></tr></thead>
     <tbody><tr v-for="r in solver.allocation" :key="r.students"><td data-label="学生 / 志愿">{{r.students}} / {{r.choices}}</td><td data-label="覆盖学生">{{r.metrics.covered}} / {{r.greedy.covered}}</td><td data-label="已分配">{{r.metrics.assigned}} / {{r.greedy.assigned}}</td><td data-label="计算时间">{{metric(r.elapsedMs,' ms')}}</td><td data-label="状态">{{r.solverStatus}}</td></tr></tbody></table></div>
    <h3>教学班排课</h3><div class="table-wrap"><table class="mobile-records"><thead><tr><th>教学班 / 教室</th><th>已安排（求解 / 贪心）</th><th>教师空节数（求解 / 贪心）</th><th>计算时间</th><th>状态</th></tr></thead>
     <tbody><tr v-for="r in solver.scheduling" :key="r.sections"><td data-label="教学班 / 教室">{{r.sections}} / {{r.rooms}}</td><td data-label="已安排">{{r.scheduled}} / {{r.greedyScheduled}}</td><td data-label="教师空节">{{r.teacherGapPeriods}} / {{r.greedyTeacherGapPeriods}}</td><td data-label="计算时间">{{metric(r.elapsedMs,' ms')}}</td><td data-label="状态">{{r.solverStatus}}</td></tr></tbody></table></div>
    <section v-if="chosenSolver==='planning-linux' && ceiling" class="workflow-details">
      <h3>固定时限下的最优证明</h3><p>{{ceiling.workerThreads}} 个求解线程，每组计算预算 {{ceiling.solverBudgetSeconds}} 秒。OPTIMAL 表示各级目标均取得最优证明。FEASIBLE 表示已得到可行解，尚有目标未完成最优证明。</p>
      <div class="table-wrap"><table class="mobile-records"><thead><tr><th>学生 / 志愿</th><th>计算时间</th><th>已证最优目标</th><th>末级目标 / 界</th><th>整体状态</th></tr></thead>
      <tbody><tr v-for="r in ceiling.stages" :key="r.students"><td data-label="学生 / 志愿">{{r.students}} / {{r.choices}}</td><td data-label="计算时间">{{metric(r.elapsedMs,' ms')}}</td><td data-label="已证最优目标">{{r.lexicographicStages.filter(s=>s.status==='OPTIMAL').length}} / {{r.lexicographicStages.length}}</td><td data-label="末级目标 / 界">{{metric(r.lexicographicStages.at(-1)?.objective)}} / {{metric(r.lexicographicStages.at(-1)?.bestBound)}}</td><td data-label="整体状态">{{r.solverStatus}}</td></tr></tbody></table></div>
    </section>
    <details class="workflow-details"><summary>查看求解阶段的目标值和界</summary><ul class="record-list"><li v-for="r in solver.allocation" :key="r.students"><span><strong>{{r.students}} 名学生 · {{r.choices}} 个志愿</strong><small v-for="s in r.stages" :key="s.name">{{s.name}} · 目标 {{s.objective}} / 界 {{s.bestBound}} · {{s.status}}</small></span></li></ul></details>
  </article>
  <article v-else-if="mode==='solver-contention' && pressure" class="card workflow-card"><h2>同机求解对在线选课的影响</h2>
    <p>{{pressure.clients}} 个并发客户端，每轮 {{pressure.secondsPerRound}} 秒。按相同读写配比，将独立 HTTP 压测与求解器同时运行进行配对对照。{{pressure.mode==='api-container-second-jvm'?'求解任务与在线 API 运行在同一容器的两个 JVM 内。':'求解任务在同一虚拟机的有界独立容器内运行。'}}</p>
    <div class="table-wrap"><table class="mobile-records"><thead><tr><th>各轮中位数</th><th>请求/秒</th><th>读 P95</th><th>写 P95</th><th>技术错误率</th></tr></thead>
    <tbody><tr v-for="k in conditionKeys" :key="k"><td data-label="条件">{{k==='alone'?'单独运行 HTTP':'同时运行求解器'}}</td><td data-label="请求/秒">{{metric(pressure.medianByCondition[k].requestsPerSecond)}}</td><td data-label="读 P95">{{metric(pressure.medianByCondition[k].readP95Ms,' ms')}}</td><td data-label="写 P95">{{metric(pressure.medianByCondition[k].writeP95Ms,' ms')}}</td><td data-label="技术错误率">{{metric(pressure.medianByCondition[k].technicalErrorRate*100,'%')}}</td></tr></tbody></table></div>
    <p>结尾独立 SQL 对账差异 {{Object.values(pressure.finalReconciliation).reduce((sum,n)=>sum+n,0)}}。</p>
    <details class="workflow-details"><summary>查看各对照轮次的重叠时长</summary><ul class="record-list"><li v-for="r in pressure.rounds.filter(x=>x.condition==='solver')" :key="r.pair"><span>第 {{r.pair}} 对</span><strong>{{metric(r.solverOverlapSeconds,' 秒')}} · {{metric((r.overlapFraction??0)*100,'%')}}</strong></li></ul></details>
  </article>
  <article v-else-if="load" class="card workflow-card">
    <h2>{{ mode === 'uniform' ? '均匀负载' : '热点负载' }} · 并发梯度</h2>
    <p>
      实测最高通过并发 {{ metric(load.highestPassing) }}，首个未通过并发
      {{ metric(load.firstNonPassing) }}。结论仅适用于本次机器、数据和负载。
    </p>
    <p v-if="load.goals">每轮读 P95 ≤ {{load.goals.readP95Ms}} ms、写 P95 ≤ {{load.goals.writeP95Ms}} ms、技术错误率 &lt; {{metric(100*load.goals.technicalErrorRateExclusive,'%')}}，且独立 SQL 对账差异为零。</p>
    <p v-if="baseline">V2 对照仅绘制同一负载、并发级及轮次的实测点。该对照最高通过 {{metric(baseline.highestPassing)}} 并发，首个未通过 {{metric(baseline.firstNonPassing)}}。</p>
    <label v-if="series.length" class="field">图表指标<select v-model="chartMetric">
      <option value="throughput">请求吞吐</option><option value="read">读取 P95 延迟</option><option value="write">写入 P95 延迟</option>
    </select></label>
    <div v-if="series.length" class="chart-body">
      <AnalyticsChart :option="curve" :label="'各轮实测'+chartNames[chartMetric]+'，完整数值列于下表。'" />
    </div>
    <div class="table-wrap">
      <table class="mobile-records">
        <thead>
          <tr>
            <th>并发 / 轮次</th>
            <th>请求/秒</th>
            <th>读 P95</th>
            <th>写 P95</th>
            <th>技术错误率</th>
            <th>业务拒绝</th><th>一致性差异</th><th>轮次 / 梯度</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in series" :key="r.clients + '-' + r.index">
            <td data-label="并发 / 轮次">{{ r.clients }} / {{ r.index }}</td>
            <td data-label="请求/秒">{{ metric(r.requestsPerSecond) }}</td>
            <td data-label="读 P95">{{ metric(r.read?.p95Ms, ' ms') }}</td>
            <td data-label="写 P95">{{ metric(r.write?.p95Ms, ' ms') }}</td>
            <td data-label="技术错误率">{{ metric(r.technicalErrorRate * 100, '%') }}</td>
            <td data-label="业务拒绝">{{metric(r.businessRefusals)}}</td><td data-label="一致性差异">{{metric(Object.values(r.mismatches || {}).reduce((sum,n)=>sum+n,0))}}</td><td data-label="轮次 / 梯度">{{ r.passed ? '通过' : '未通过' }} / {{r.stagePassed?'通过':'未通过'}}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <details v-if="levels.length" class="workflow-details"><summary>查看数据库资源与等待锁的实测采样</summary>
      <p>{{resources?.machine}}。资源指标为各梯度三轮期间的采样值。峰值可能发生在采样间隔之间。</p>
      <div class="table-wrap"><table class="mobile-records"><thead><tr><th>并发</th><th>采样数</th><th>观察到等待锁的样本</th><th>等待锁峰值</th><th>数据库 CPU / 内存峰值</th><th>API CPU / 内存峰值</th></tr></thead>
      <tbody><tr v-for="r in levels" :key="r.clients"><td data-label="并发">{{r.clients}}</td><td data-label="采样数">{{r.resourceSampleCount}}</td><td data-label="等待锁样本">{{r.samplesWithWaitingLocks}}</td><td data-label="等待锁峰值">{{r.peakObservedWaitingLocks}}</td><td data-label="数据库 CPU / 内存">{{metric(r.databasePeakCpuPct,'%')}} / {{metric(r.databasePeakMiB,' MiB')}}</td><td data-label="API CPU / 内存">{{metric(r.apiPeakCpuPct,'%')}} / {{metric(r.apiPeakMiB,' MiB')}}</td></tr></tbody></table></div>
    </details>
  </article>
</template>

<style scoped>
.workflow-card, .record-list li, .record-list li > span { min-width: 0; }
.record-list li > span:only-child { flex: 1 1 0%; max-width: 100%; }
.workflow-card p, .record-list small, .record-list li > span { overflow-wrap: anywhere; }
@media (max-width: 700px) {
  .workflow-card { max-width: 100%; }
  .workflow-details { min-width: 0; }
}
</style>
