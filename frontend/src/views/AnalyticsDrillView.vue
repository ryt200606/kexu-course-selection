<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import PageHeading from '../components/PageHeading.vue'
import StatePanel from '../components/StatePanel.vue'
import { useAsync } from '../lib/useAsync'
import { useWorkspace } from '../stores/workspace'
import { v3 } from '../lib/v3'
import { query, type Page } from '../lib/api'
import { csvDownload } from '../lib/format'
const metrics = {
  CAPACITY: ['席位利用', '在读人数', '总容量'],
  WAITLIST: ['候补需求', '候补人数', '总容量'],
  GRADES: ['学业预警', '已发布不及格人数', '有正式成绩人数'],
  UNASSIGNED: ['未获课', '未获课学生人次', '正式填报学生人次'],
  PREFERENCE: ['志愿命中', '命中志愿条数', '填报志愿条数'],
  WAIT_TIME: ['候补等待', '等待总秒数', '已转正候补人数'],
} as const
type Metric = keyof typeof metrics
interface Entry {
  sectionId: string
  courseTitle: string
  teacherName: string
  numerator: number
  denominator: number
}
interface Settlement {
  metric: string
  definition: string
  unit: string
  numerator: number
  denominator: number
  value: number | null
  items: { itemId: string; label: string; numerator: number; denominator: number }[]
  page: number
  size: number
  total: number
}
const settlement = ref<Settlement>(),
  settlementMode = computed(() => ['UNASSIGNED', 'PREFERENCE', 'WAIT_TIME'].includes(metric.value))
const metric = ref<Metric>('CAPACITY'),
  labels = computed(() => metrics[metric.value]),
  ws = useWorkspace()
const { run, error, loading } = useAsync(),
  items = ref<Entry[]>([]),
  total = ref(0),
  page = ref(0)
function load() {
  if (!ws.termId) return
  settlement.value = undefined
  if (settlementMode.value)
    return run(
      () =>
        v3<Settlement>(
          '/admin/analytics/settlement' +
            query({ termId: ws.termId, metric: metric.value, page: page.value, size: 20 })
        ),
      (r) => {
        settlement.value = r
        items.value = r.items.map((i) => ({
          sectionId: i.itemId,
          courseTitle: i.label,
          teacherName: '',
          numerator: i.numerator,
          denominator: i.denominator,
        }))
        total.value = r.total
      }
    )
  return run(
    () =>
      v3<Page<Entry>>(
        '/admin/analytics/drill' +
          query({ termId: ws.termId, metric: metric.value, page: page.value, size: 20 })
      ),
    (r) => {
      items.value = r.items
      total.value = r.total
    }
  )
}
watch(
  [metric, () => ws.termId],
  () => {
    page.value = 0
    void load()
  },
  { immediate: true }
)
function rate(n: number, d: number) {
  if (!d) return metric.value === 'GRADES' ? '暂无已发布样本' : '—'
  return metric.value === 'WAIT_TIME' ? (n / d).toFixed(1) + ' 秒' : ((100 * n) / d).toFixed(1) + '%'
}
function exportPage() {
  csvDownload('教学分析-' + metric.value + '-第' + (page.value + 1) + '页.csv', [
    [
      settlementMode.value ? '明细编号' : '教学班',
      settlementMode.value ? '记录' : '课程',
      '教师',
      labels.value[1],
      labels.value[2],
    ],
    ...items.value.map((r) => [r.sectionId, r.courseTitle, r.teacherName, r.numerator, r.denominator]),
  ])
}
</script>
<template>
  <PageHeading
    eyebrow="TEACHING ANALYTICS"
    title="教学指标明细"
    description="并列核对各教学班的分子、分母和统计口径。"
    ><select v-model="ws.termId" class="select-control" aria-label="选择学期">
      <option v-for="t in ws.terms" :key="t.id" :value="t.id">{{ t.name }}</option>
    </select></PageHeading
  >
  <nav class="workflow-tabs" aria-label="指标">
    <button v-for="(label, k) in metrics" :key="k" :aria-pressed="metric === k" @click="metric = k">
      {{ label[0] }}
    </button>
  </nav>
  <section class="card workflow-card">
    <div class="row-between">
      <div>
        <h2>{{ labels[0] }}</h2>
        <p>{{ labels[1] }} ÷ {{ labels[2] }} · 本学期数据，分母为零时无可用样本</p>
      </div>
      <button class="button button-secondary" :disabled="!items.length || loading" @click="exportPage">
        导出本页
      </button>
    </div>
    <div v-if="settlement" class="plan-verdict">
      <strong>学期结算 {{ rate(settlement.numerator, settlement.denominator) }}</strong>
      <p>{{ settlement.definition }}</p>
      <small>{{ labels[1] }} {{ settlement.numerator }} · {{ labels[2] }} {{ settlement.denominator }}</small>
    </div>
    <StatePanel v-if="loading || error" :loading="loading" :error="error" @retry="load" />
    <StatePanel v-else-if="!items.length" title="当前学期暂无指标明细" />
    <template v-else
      ><div class="table-wrap">
        <table class="mobile-records">
          <caption class="sr-only">
            教学指标分子与分母
          </caption>
          <thead>
            <tr>
              <th>{{ settlementMode ? '记录' : '教学班' }}</th>
              <th v-if="!settlementMode">教师</th>
              <th>{{ labels[1] }}</th>
              <th>{{ labels[2] }}</th>
              <th>{{ metric === 'WAIT_TIME' ? '平均等待' : '占比' }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in items" :key="r.sectionId">
              <td data-label="记录">
                <strong v-if="settlementMode">{{ r.courseTitle }}</strong
                ><RouterLink v-else :to="{ path: '/admin/enrollments', query: { sectionId: r.sectionId } }">{{
                  r.courseTitle
                }}</RouterLink
                ><small>{{ r.sectionId }}</small>
              </td>
              <td data-label="教师" v-if="!settlementMode">{{ r.teacherName }}</td>
              <td :data-label="labels[1]">{{ r.numerator }}</td>
              <td :data-label="labels[2]">{{ r.denominator }}</td>
              <td :data-label="metric === 'WAIT_TIME' ? '平均等待' : '占比'">
                {{ rate(r.numerator, r.denominator) }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="workflow-actions">
        <span>共 {{ total }} 条记录 · 第 {{ page + 1 }} 页</span
        ><button
          class="text-button"
          :disabled="!page"
          @click="page--; load()"
        >
          上一页</button
        ><button
          class="text-button"
          :disabled="(page + 1) * 20 >= total"
          @click="page++; load()"
        >
          下一页
        </button>
      </div></template
    >
  </section>
</template>
