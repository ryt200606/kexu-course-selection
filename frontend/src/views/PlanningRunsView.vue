<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { CalendarRange, Check, Plus, Trash2, Users } from 'lucide-vue-next'
import PageHeading from '../components/PageHeading.vue'
import RemoteSelect from '../components/RemoteSelect.vue'
import MeetingFields from '../components/MeetingFields.vue'
import StatePanel from '../components/StatePanel.vue'
import AppModal from '../components/AppModal.vue'
import { api, query, encode, newKey, type Meeting, type Round, type Page } from '../lib/api'
import { v3 } from '../lib/v3'
import { useWorkspace } from '../stores/workspace'
import { useAsync } from '../lib/useAsync'

interface Run {
  id: string
  kind: string
  state: string
  snapshotHash: string
  solverStatus: string
  metrics: Record<string, unknown>
  assignments: Record<string, unknown>[]
  greedy: Record<string, unknown> | Record<string, unknown>[]
  excluded?: Record<string, unknown>[]
  unassigned?: Record<string, unknown>[]
  seedCommitment?: string
  seedReveal?: string
  provenObjectives?: unknown
  resourceVersion?: number
  resourceMode?: string
  stages?: unknown[]
}
const defaults = (): Meeting => ({
  weekday: 1,
  room: '',
  startPeriod: 1,
  endPeriod: 2,
  startWeek: 1,
  endWeek: 16,
})
const route = useRoute(),
  router = useRouter(),
  ws = useWorkspace()
const mode = ref<'schedule' | 'allocation'>('schedule'),
  rooms = ref([{ id: '', capacity: 30 }])
const sections = ref<{ sectionId: string; slots: Meeting[]; lockedSlot?: number }[]>([
  { sectionId: '', slots: [defaults()] },
])
const blocked = ref<{ teacherId: string; slot: Meeting }[]>([]),
  rounds = ref<Round[]>([]),
  roundId = ref('')
interface Resource {
  version: number
  rooms: { id: string; capacity: number }[]
  unavailable: {
    teacherId: string
    weekday: number
    startPeriod: number
    endPeriod: number
    startWeek: number
    endWeek: number
  }[]
}
interface RunSummary {
  id: string
  kind: string
  state: string
  createdAt?: string
}
const resourceVersion = ref(0),
  resourceRequest = useAsync(),
  historyRequest = useAsync(),
  history = ref<RunSummary[]>([]),
  historyPage = ref(0),
  historyTotal = ref(0)
function loadResource() {
  if (!ws.termId) return
  return resourceRequest.run(
    () => v3<Resource>('/admin/planning/resources/' + encode(ws.termId)),
    (data) => {
      resourceVersion.value = data.version
      rooms.value = data.rooms.length ? data.rooms.map((r) => ({ ...r })) : [{ id: '', capacity: 30 }]
      blocked.value = data.unavailable.map(({ teacherId, ...slot }) => ({
        teacherId,
        slot: { ...slot, room: '' },
      }))
    }
  )
}
function historyLoad() {
  if (!ws.termId) return
  return historyRequest.run(
    () =>
      v3<Page<RunSummary>>(
        '/admin/planning/runs' + query({ termId: ws.termId, page: historyPage.value, size: 10 })
      ),
    (data) => {
      history.value = data.items
      historyTotal.value = data.total
    }
  )
}
async function saveResource() {
  busy.value = true
  issue.value = ''
  message.value = ''
  try {
    const rows = rooms.value.map((r) => ({ id: r.id.trim(), capacity: r.capacity }))
    if (!rows.length || rows.some((r) => !r.id || !Number.isInteger(r.capacity) || r.capacity < 1))
      throw new Error('每间教室都需要名称和有效容量。')
    const data = await v3<Resource>('/admin/planning/resources/' + encode(ws.termId), {
      method: 'PUT',
      body: {
        version: resourceVersion.value,
        rooms: rows,
        unavailable: blocked.value.map(({ teacherId, slot: { room: _, ...slot } }) => ({
          teacherId,
          ...slot,
        })),
      },
    })
    resourceVersion.value = data.version
    invalidate()
    message.value = '学期资源已保存 · 第 ' + data.version + ' 版。'
  } catch (e) {
    issue.value = e instanceof Error ? e.message : '资源保存失败'
  } finally {
    busy.value = false
  }
}
const { error, run, loading } = useAsync(),
  work = ref<Run>(),
  busy = ref(false),
  issue = ref(''),
  message = ref(''),
  confirm = ref(false),
  commitment = ref(''),
  findId = ref(''),
  publicationKey = ref('')
const clock = ref(Date.now())
let tick: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  tick = setInterval(() => {
    clock.value = Date.now()
  }, 2000)
})
onBeforeUnmount(() => clearInterval(tick))
const activeRound = computed(() => rounds.value.find((r) => r.id === roundId.value))
const canCommit = computed(
  () => activeRound.value?.state === 'DRAFT' && Date.parse(activeRound.value.startsAt) > clock.value
)
const canAllocate = computed(
  () =>
    !!activeRound.value &&
    (activeRound.value.state === 'CLOSED' ||
      (activeRound.value.state === 'OPEN' && Date.parse(activeRound.value.endsAt) <= clock.value))
)
const roomIds = computed(() => rooms.value.map((r) => r.id.trim()).filter(Boolean))
const labels: Record<string, string> = {
  assigned: '已分配',
  assignedCount: '分配数',
  unassigned: '待处理',
  unassignedCount: '未分配数',
  scheduled: '已排教学班',
  scheduledCount: '已排教学班',
  firstChoices: '第一志愿',
  firstChoiceCount: '第一志愿人数',
  total: '对象数',
  teacherId: '教师',
  studentId: '学生',
  sectionId: '教学班',
  room: '教室',
  weekday: '星期',
  startPeriod: '开始节次',
  endPeriod: '结束节次',
  startWeek: '起始周',
  endWeek: '结束周',
  rank: '志愿顺位',
  score: '目标值',
  penalty: '惩罚值',
  solverStatus: '求解状态',
  capacity: '容量',
  requestCount: '申请数',
  slot: '时段',
  covered: '覆盖学生数',
  rankHits: '各顺位命中数',
  requested: '申请排课数',
  unusedSeats: '空余座位',
  index: '候选序号',
  slotIndex: '候选下标',
  reasons: '原因',
  reason: '原因',
}
function label(k: string) {
  return labels[k] || k
}
function value(v: unknown): string {
  if (v === null || v === undefined) return '—'
  if (Array.isArray(v)) return v.map(value).join('、')
  if (typeof v === 'object')
    return Object.entries(v)
      .map(([k, x]) => label(k) + ' ' + value(x))
      .join(' · ')
  return String(v)
}
const summary = computed(() => Object.entries(work.value?.metrics || {}))
const greedySummary = computed(() =>
  Object.entries(work.value?.greedy || {}).filter(
    ([k, v]) => k !== 'assignments' && (Array.isArray(v) || typeof v !== 'object')
  )
)
const canPublish = computed(
  () =>
    !!work.value &&
    work.value.state === 'PREVIEW' &&
    work.value.solverStatus === 'OPTIMAL' &&
    (work.value.kind !== 'SCHEDULE' ||
      (work.value.metrics.scheduled === work.value.metrics.requested &&
        work.value.resourceMode === 'REGISTERED'))
)
const columns = computed(() => [
  ...new Set((work.value?.assignments || []).flatMap((row) => Object.keys(row))),
])
const stateNames: Record<string, string> = {
  PREVIEW: '待发布',
  PREVIEWED: '待发布',
  DRAFT: '待发布',
  PUBLISHED: '已发布',
  STALE: '快照已过期',
}
function invalidate() {
  work.value = undefined
  confirm.value = false
  publicationKey.value = ''
  message.value = ''
}
watch([mode, rooms, sections, blocked, roundId], invalidate, { deep: true })
watch(
  () => ws.termId,
  () => {
    invalidate()
    commitment.value = ''
    if (!ws.termId) return
    historyPage.value = 0
    void historyLoad()
    void loadResource()
    void run(
      () => api<Page<Round>>('/rounds' + query({ termId: ws.termId, size: 100 })),
      (d) => {
        rounds.value = d.items
        roundId.value = d.items.find((r) => r.state === 'OPEN')?.id || d.items[0]?.id || ''
      }
    )
  },
  { immediate: true }
)
function record(data: Run) {
  work.value = data
  publicationKey.value = newKey()
  findId.value = data.id
}
async function find(id: string) {
  if (!id.trim()) return
  busy.value = true
  issue.value = ''
  try {
    const data = await v3<Run>('/admin/planning/runs/' + encode(id.trim()))
    record(data)
  } catch (e) {
    issue.value = e instanceof Error ? e.message : '读取失败'
  } finally {
    busy.value = false
  }
}
watch(
  () => route.query.run,
  (id) => {
    if (typeof id === 'string' && id !== work.value?.id) void find(id)
  },
  { immediate: true }
)
async function commitSeed() {
  busy.value = true
  issue.value = ''
  message.value = ''
  try {
    const d = await v3<{ seedCommitment?: string; commitment?: string }>(
      '/admin/planning/rounds/' + encode(roundId.value) + '/commit',
      { method: 'POST' }
    )
    commitment.value = d.seedCommitment || d.commitment || ''
    message.value = '本轮种子承诺已登记，可按计划开放填报。'
  } catch (e) {
    issue.value = e instanceof Error ? e.message : '提交失败'
  } finally {
    busy.value = false
  }
}
async function preview() {
  busy.value = true
  issue.value = ''
  message.value = ''
  try {
    const body =
      mode.value === 'allocation'
        ? { roundId: roundId.value }
        : {
            termId: ws.termId,
            rooms: rooms.value.map((r) => ({ id: r.id.trim(), capacity: r.capacity })),
            sections: sections.value,
            unavailable: blocked.value.map(({ teacherId, slot }) => ({
              teacherId,
              weekday: slot.weekday,
              startWeek: slot.startWeek,
              endWeek: slot.endWeek,
              startPeriod: slot.startPeriod,
              endPeriod: slot.endPeriod,
            })),
          }
    const data = await v3<Run>(
      '/admin/planning/' + (mode.value === 'allocation' ? 'allocations' : 'schedules') + '/preview',
      { method: 'POST', body }
    )
    record(data)
    await router.replace({ query: { ...route.query, run: data.id } })
    await historyLoad()
  } catch (e) {
    issue.value = e instanceof Error ? e.message : '预览失败'
  } finally {
    busy.value = false
  }
}
async function publish() {
  if (!work.value) return
  busy.value = true
  issue.value = ''
  try {
    const id = work.value.id
    await v3('/admin/planning/runs/' + encode(id) + '/publish', {
      method: 'POST',
      idempotent: true,
      key: publicationKey.value,
      body: { snapshotHash: work.value.snapshotHash },
    })
    record(await v3<Run>('/admin/planning/runs/' + encode(id)))
    confirm.value = false
    await historyLoad()
    message.value = '批次已发布，结果已重新读取。'
  } catch (e) {
    issue.value = e instanceof Error ? e.message : '发布失败'
    confirm.value = false
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <PageHeading
    eyebrow="PLANNING STUDIO"
    title="排课与分配"
    description="先预览约束和方案，再将确认的批次发布到教学记录。"
    ><select v-model="ws.termId" class="select-control" aria-label="选择学期" :disabled="busy">
      <option v-for="t in ws.terms" :key="t.id" :value="t.id">{{ t.name }}</option>
    </select></PageHeading
  >
  <nav class="workflow-tabs" aria-label="规划方式">
    <button :aria-pressed="mode === 'schedule'" :disabled="busy" @click="mode = 'schedule'">
      <CalendarRange :size="16" /> 批量排课</button
    ><button :aria-pressed="mode === 'allocation'" :disabled="busy" @click="mode = 'allocation'">
      <Users :size="16" /> 志愿分配
    </button>
  </nav>
  <p v-if="issue" class="error-inline" role="alert">{{ issue }}</p>
  <p v-if="message" class="notice-banner" role="status"><Check :size="16" />{{ message }}</p>
  <StatePanel v-if="error" :error="error" @retry="ws.loadTerms" />
  <form class="card workflow-card workflow-form" @submit.prevent="preview">
    <template v-if="mode === 'schedule'">
      <div class="row-between">
        <div>
          <h2>教室资源</h2>
          <p>候选班须处于草稿状态，且所属开设已发布。为每班提供 1—12 个候选时段。</p>
        </div>
        <button type="button" class="text-button" @click="rooms.push({ id: '', capacity: 30 })">
          <Plus :size="14" /> 教室
        </button>
      </div>
      <div v-for="(r, i) in rooms" :key="i" class="inline-fields">
        <label class="field"
          >教室名称<input v-model="r.id" required maxlength="80" placeholder="例如：教101" /></label
        ><label class="field">容量<input v-model.number="r.capacity" type="number" min="1" required /></label
        ><button
          v-if="rooms.length > 1"
          type="button"
          class="icon-button"
          :aria-label="'删除教室' + (i + 1)"
          @click="rooms.splice(i, 1)"
        >
          <Trash2 :size="15" />
        </button>
      </div>
      <div class="workflow-actions">
        <strong>{{
          resourceVersion ? '学期资源 · 第 ' + resourceVersion + ' 版' : '尚未保存学期资源'
        }}</strong
        ><button
          type="button"
          class="button button-secondary"
          :disabled="busy || resourceRequest.loading.value"
          @click="saveResource"
        >
          保存学期资源</button
        ><button type="button" class="text-button" :disabled="busy" @click="loadResource">重新读取</button>
      </div>
      <p v-if="resourceRequest.error.value" class="error-inline" role="alert">
        {{ resourceRequest.error.value }}
      </p>
      <div class="row-between">
        <h2>待排教学班与候选时段</h2>
        <button
          class="text-button"
          type="button"
          @click="sections.push({ sectionId: '', slots: [defaults()] })"
        >
          <Plus :size="14" /> 教学班
        </button>
      </div>
      <fieldset v-for="(s, i) in sections" :key="i" class="workflow-fieldset">
        <legend>教学班 {{ i + 1 }}</legend>
        <label class="field"
          >选择教学班<RemoteSelect
            v-model="s.sectionId"
            :path="'/admin/sections' + query({ termId: ws.termId })"
            label="待排教学班"
            required
        /></label>
        <div v-for="(_, j) in s.slots" :key="j" class="candidate-slot">
          <div class="row-between">
            <strong>候选时段 {{ j + 1 }}</strong
            ><button
              v-if="s.slots.length > 1"
              type="button"
              class="text-button"
              @click="s.slots.splice(j, 1)"
            >
              移除此时段
            </button>
          </div>
          <MeetingFields v-model="s.slots[j]!" :rooms="roomIds" />
        </div>
        <label class="field"
          >固定时段<select v-model="s.lockedSlot">
            <option :value="undefined">允许从候选中择优</option>
            <option v-for="(_, j) in s.slots" :key="j" :value="j">锁定候选 {{ j + 1 }}</option>
          </select></label
        >
        <div class="workflow-actions">
          <button
            type="button"
            class="text-button"
            :disabled="s.slots.length >= 12"
            @click="s.slots.push(defaults())"
          >
            添加可选时段</button
          ><button
            v-if="sections.length > 1"
            type="button"
            class="text-button"
            @click="sections.splice(i, 1)"
          >
            删除该教学班
          </button>
        </div>
      </fieldset>
      <div class="row-between">
        <h3>教师不可排时段</h3>
        <button type="button" class="text-button" @click="blocked.push({ teacherId: '', slot: defaults() })">
          添加限制
        </button>
      </div>
      <div v-for="(b, i) in blocked" :key="i" class="workflow-fieldset">
        <label class="field"
          >教师<RemoteSelect
            v-model="b.teacherId"
            path="/admin/teachers"
            label="不可排教师"
            required /></label
        ><MeetingFields v-model="b.slot" /><button
          type="button"
          class="text-button"
          @click="blocked.splice(i, 1)"
        >
          移除限制
        </button>
      </div>
    </template>
    <template v-else
      ><h2>志愿分配批次</h2>
      <label class="field"
        >选课轮次<select v-model="roundId" required>
          <option v-for="r in rounds" :key="r.id" :value="r.id">{{ r.name }}</option>
        </select></label
      >
      <p>
        轮次仍为草稿且开始时间尚未来到时登记种子承诺。学生仅在开放窗口填报，关闭轮次或到达截止时间后，教务才能预览和发布。
      </p>
      <button
        type="button"
        class="button button-secondary"
        :disabled="busy || !canCommit"
        @click="commitSeed"
      >
        登记种子承诺</button
      ><small v-if="commitment" class="hash-line">{{ commitment }}</small></template
    >
    <div class="workflow-actions">
      <button
        class="button"
        :disabled="busy || loading || !ws.termId || (mode === 'allocation' && !canAllocate)"
      >
        生成{{ mode === 'schedule' ? '排课' : '分配' }}预览
      </button>
    </div>
  </form>
  <section class="card workflow-card mt-24">
    <div class="row-between"><h2>批次结果</h2></div>
    <form class="inline-fields" @submit.prevent="find(findId)">
      <label class="field"
        >按批次编号回读<input v-model="findId" required placeholder="输入上次生成的批次编号" /></label
      ><button class="button button-secondary" :disabled="busy">读取</button>
    </form>
    <details v-if="history.length" class="workflow-details">
      <summary>本学期历史批次（{{ historyTotal }}）</summary>
      <ul class="record-list">
        <li v-for="r in history" :key="r.id">
          <span
            >{{ r.kind === 'SCHEDULE' ? '批量排课' : '志愿分配' }} · {{ stateNames[r.state] || r.state
            }}<small>{{ r.id }}</small></span
          ><button type="button" class="text-button" @click="find(r.id)">读回结果</button>
        </li>
      </ul>
      <div class="workflow-actions">
        <button
          class="text-button"
          :disabled="!historyPage"
          @click="historyPage--; historyLoad()"
        >
          上一页</button
        ><button
          class="text-button"
          :disabled="(historyPage + 1) * 10 >= historyTotal"
          @click="historyPage++; historyLoad()"
        >
          下一页
        </button>
      </div>
    </details>
    <p v-if="historyRequest.error.value" class="error-inline">
      {{ historyRequest.error.value }} <button class="text-button" @click="historyLoad">重试</button>
    </p>
    <template v-if="work"
      ><p v-if="work.resourceMode === 'EXAMPLE'" class="notice-banner">
        临时教室资源可用于预览。先保存资源版本，再生成可发布批次。
      </p>
      <div class="run-heading">
        <div>
          <strong>{{ stateNames[work.state] || work.state }}</strong
          ><small>{{ work.id }} · {{ work.solverStatus }}</small>
        </div>
        <button class="button" :disabled="busy || !canPublish" @click="confirm = true">发布此批次</button>
      </div>
      <dl class="run-metrics">
        <div v-for="[k, v] in summary" :key="k">
          <dt>{{ label(k) }}</dt>
          <dd>{{ value(v) }}</dd>
        </div>
      </dl>
      <p v-if="greedySummary.length" class="workflow-note">
        贪心对照　{{ greedySummary.map(([k, v]) => label(k) + ' ' + value(v)).join(' · ') }}
      </p>
      <div v-if="work.assignments?.length" class="table-wrap">
        <table class="mobile-records">
          <caption class="sr-only">
            求解分配明细
          </caption>
          <thead>
            <tr>
              <th v-for="col in columns" :key="col">{{ label(col) }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, i) in work.assignments" :key="i">
              <td v-for="col in columns" :key="col" :data-label="label(col)">{{ value(row[col]) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else class="workflow-note">此批次尚无可分配记录。</p>
      <details v-if="work.excluded?.length || work.unassigned?.length" class="workflow-details">
        <summary>未排入或未满足的请求</summary>
        <ul class="record-list">
          <li v-for="(r, i) in [...(work.excluded || []), ...(work.unassigned || [])]" :key="i">
            {{ value(r) }}
          </li>
        </ul>
      </details>
      <details v-if="work.seedCommitment" class="workflow-details">
        <summary>抽签种子核验</summary>
        <p class="hash-line">事先承诺 {{ work.seedCommitment }}</p>
        <p class="hash-line">公布种子 {{ work.seedReveal }}</p>
      </details>
      <small class="hash-line">输入摘要 {{ work.snapshotHash }}</small>
    </template>
  </section>
  <AppModal :open="confirm" title="发布当前批次" :busy="busy" @close="confirm = false"
    ><p>确认将 {{ work?.id }} 的结果写入教学记录？服务器会核对输入摘要，并在发生变化时拒绝整批发布。</p>
    <template #footer
      ><button class="button button-secondary" :disabled="busy" @click="confirm = false">返回预览</button
      ><button class="button" :disabled="busy" @click="publish">确认发布</button></template
    ></AppModal
  >
</template>
