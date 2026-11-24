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
interface Cycle {
  requestsPerSecond: number
  read: { p95Ms: number }
  write: { p95Ms: number }
  technicalErrorRate: number
  mismatches: Record<string, number>
}
interface Load {
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
] as const
const mode = ref<string>('sql-matrix'),
  records = ref<Record<string, Sql | Load>>({}),
  busy = ref(false),
  error = ref('')
const sql = computed(() => records.value['sql-matrix'] as Sql | undefined),
  load = computed(() => records.value[mode.value] as Load | undefined)
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
      result: Record<string, Sql | Load> = {}
    for (const [key] of modes) {
      if (!available.includes(key)) continue
      const r = await fetch(base + key + '.json')
      if (!r.ok) throw new Error('读取实测记录失败')
      result[key] = (await r.json()) as Sql | Load
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
    (s.rounds || []).map((r, i) => ({ clients: s.clients, passed: s.passed, index: i + 1, ...r }))
  )
)
const curve = computed(() => ({
  tooltip: { trigger: 'axis' },
  grid: { left: 60, right: 20, bottom: 50, top: 24 },
  xAxis: {
    type: 'category',
    name: '并发 / 轮次',
    nameLocation: 'center',
    nameGap: 29,
    data: series.value.map((i) => i.clients + ' / ' + i.index),
  },
  yAxis: { type: 'value', name: '请求/秒' },
  series: [
    {
      name: '请求/秒',
      type: 'line',
      showSymbol: true,
      data: series.value.map((r) => r.requestsPerSecond),
      lineStyle: { color: '#477754' },
    },
  ],
}))
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
    v-else-if="!records[mode]"
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
  <article v-else-if="load" class="card workflow-card">
    <h2>{{ mode === 'uniform' ? '均匀负载' : '热点负载' }} · 并发梯度</h2>
    <p>
      实测最高通过并发 {{ metric(load.highestPassing) }}，首个未通过并发
      {{ metric(load.firstNonPassing) }}。结论仅适用于本次机器、数据和负载。
    </p>
    <div v-if="series.length" class="chart-body">
      <AnalyticsChart :option="curve" label="各轮实测请求吞吐，完整数值列于下表。" />
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
            <th>梯度结果</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in series" :key="r.clients + '-' + r.index">
            <td data-label="并发 / 轮次">{{ r.clients }} / {{ r.index }}</td>
            <td data-label="请求/秒">{{ metric(r.requestsPerSecond) }}</td>
            <td data-label="读 P95">{{ metric(r.read?.p95Ms, ' ms') }}</td>
            <td data-label="写 P95">{{ metric(r.write?.p95Ms, ' ms') }}</td>
            <td data-label="技术错误率">{{ metric(r.technicalErrorRate * 100, '%') }}</td>
            <td data-label="梯度结果">{{ r.passed ? '通过' : '未通过' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </article>
</template>
