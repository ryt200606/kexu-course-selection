<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { ArrowDown, ArrowUp, Check, CircleAlert, Plus, Trash2 } from 'lucide-vue-next'
import PlanAgenda from '../components/PlanAgenda.vue'
import PageHeading from '../components/PageHeading.vue'
import RemoteSelect from '../components/RemoteSelect.vue'
import StatePanel from '../components/StatePanel.vue'
import { api, encode, query, type Page, type Round, type Row } from '../lib/api'
import { v3, type Curriculum, type DraftPlans, type PlanResult } from '../lib/v3'
import { useAsync } from '../lib/useAsync'
import { useWorkspace } from '../stores/workspace'

const workspace = useWorkspace()
const tabs = ['培养进度', '方案对比', '选课志愿'] as const
const tab = ref<(typeof tabs)[number]>('培养进度'),
  curriculum = ref<Curriculum>()
const plans = ref<DraftPlans>({ left: [], right: [], version: 0 })
const sides = ['left', 'right'] as const
type Side = (typeof sides)[number]
const compared = ref<{ left: PlanResult; right: PlanResult }>()
const sectionPick = ref({ left: '', right: '', preference: '' })
const titles = ref<Record<string, string>>({})
const rounds = ref<Round[]>([]),
  roundId = ref(''),
  ranked = ref<string[]>([]),
  preferenceVersion = ref(0),
  commitment = ref(''),
  phase = ref(''),
  allocated = ref<{ sectionId: string; rank: number }[]>([])
const missed = ref<{ sectionId: string; rank: number; explanation: string }[]>([])
const busy = ref(false),
  message = ref(''),
  actionError = ref('')
const { run, loading, error } = useAsync()
const catalog = computed(() => '/catalog' + query({ termId: workspace.termId }))
const round = computed(() => rounds.value.find((r) => r.id === roundId.value))
function remember(row: Row) {
  titles.value[String(row.id)] = String(row.courseTitle || row.id)
}
function sectionLabel(id: string) {
  return titles.value[id] ? titles.value[id] + ' · ' + id : id
}
async function load() {
  message.value = actionError.value = ''
  compared.value = undefined
  const termId = workspace.termId
  if (tab.value === '培养进度') {
    await run(
      () => v3<Curriculum>('/student/curriculum'),
      (data) => {
        curriculum.value = data
      }
    )
  } else if (termId && tab.value === '方案对比') {
    await run(
      () => v3<DraftPlans>('/student/plans' + query({ termId })),
      (data) => {
        plans.value = data
      }
    )
  } else if (termId) {
    await run(
      () => api<Page<Round>>('/rounds' + query({ termId, size: 100 })),
      (data) => {
        rounds.value = data.items
        roundId.value = data.items.find((r) => r.state === 'OPEN')?.id || data.items[0]?.id || ''
      }
    )
  }
}
watch(
  [tab, () => workspace.termId],
  () => {
    roundId.value = ''
    ranked.value = []
    void load()
  },
  { immediate: true }
)
const clock = ref(Date.now())
let tick: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  tick = setInterval(() => {
    clock.value = Date.now()
  }, 2000)
})
onBeforeUnmount(() => clearInterval(tick))
const acceptsRank = computed(
  () =>
    phase.value === 'COLLECTING' &&
    round.value?.state === 'OPEN' &&
    Date.parse(round.value.startsAt) <= clock.value &&
    Date.parse(round.value.endsAt) > clock.value
)
const prefRead = useAsync()
watch(roundId, (id) => {
  ranked.value = []
  commitment.value = ''
  if (id)
    void prefRead.run(
      () =>
        v3<{
          sectionIds: string[]
          version: number
          seedCommitment?: string
          phase: string
          assignments?: { sectionId: string; rank: number }[]
          unassigned?: { sectionId: string; rank: number; explanation: string }[]
        }>('/student/preferences/' + encode(id)),
      (data) => {
        ranked.value = data.sectionIds
        preferenceVersion.value = data.version
        commitment.value = data.seedCommitment || ''
        phase.value = data.phase
        allocated.value = data.assignments || []
        missed.value = data.unassigned || []
      }
    )
})
function edit(id: string, side: Side | 'preference', operation: 'add' | 'remove') {
  const list = side === 'preference' ? ranked.value : plans.value[side]
  if (side === 'preference' && !acceptsRank.value) return
  if (operation === 'remove') list.splice(list.indexOf(id), 1)
  if (operation === 'add' && id && !list.includes(id)) list.push(id)
  sectionPick.value[side] = ''
  compared.value = undefined
  message.value = ''
}
function shift(index: number, step: number) {
  if (!acceptsRank.value) return
  const items = ranked.value,
    item = items[index]
  if (!item || index + step < 0 || index + step >= items.length) return
  items.splice(index, 1)
  items.splice(index + step, 0, item)
  message.value = ''
}
async function act(action: 'save' | 'compare' | 'rank') {
  busy.value = true
  actionError.value = ''
  message.value = ''
  try {
    if (action === 'rank') {
      const data = await v3<{ version: number; sectionIds: string[] }>(
        '/student/preferences/' + encode(roundId.value),
        { method: 'PUT', body: { sectionIds: ranked.value, version: preferenceVersion.value } }
      )
      preferenceVersion.value = data.version
      ranked.value = data.sectionIds
      message.value = '志愿顺序已保存。'
    } else if (action === 'save') {
      plans.value = await v3<DraftPlans>('/student/plans' + query({ termId: workspace.termId }), {
        method: 'PUT',
        body: plans.value,
      })
      message.value = '两套试排方案已保存。'
    } else {
      compared.value = await v3('/student/plans/compare', {
        method: 'POST',
        body: { termId: workspace.termId, left: plans.value.left, right: plans.value.right },
      })
      message.value = '已按当前课程数据完成比较。'
    }
  } catch (e) {
    actionError.value = e instanceof Error ? e.message : '提交失败'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <PageHeading
    eyebrow="ACADEMIC PLANNING"
    title="学业规划"
    description="核对培养要求，先试排课表，再确定本轮志愿。"
  >
    <select v-model="workspace.termId" class="select-control" aria-label="选择学期" :disabled="busy">
      <option v-for="t in workspace.terms" :key="t.id" :value="t.id">{{ t.name }}</option>
    </select>
  </PageHeading>
  <nav class="workflow-tabs" aria-label="规划工具">
    <button v-for="t in tabs" :key="t" :aria-pressed="tab === t" :disabled="busy" @click="tab = t">
      {{ t }}
    </button>
  </nav>
  <p v-if="message" class="notice-banner" role="status"><Check :size="16" />{{ message }}</p>
  <p v-if="actionError" role="alert" class="error-inline">
    {{ actionError }} <button class="text-button" @click="load">重新读取</button>
  </p>
  <StatePanel v-if="loading || error" :loading="loading" :error="error" @retry="load" />
  <template v-else-if="tab === '培养进度'">
    <section v-if="curriculum?.curriculum" class="workflow-hero">
      <div>
        <small>我的培养方案 · 第 {{ curriculum.curriculum.version }} 版</small>
        <h2>{{ curriculum.curriculum.name }}</h2>
        <p>{{ curriculum.curriculum.cohort }} 级 · 学分按已发布的通过成绩统计</p>
      </div>
      <div class="hero-metric">
        <strong
          >{{ curriculum.earnedCredits }}<small> / {{ curriculum.requiredCredits }}</small></strong
        ><span>已获学分 / 方案要求</span>
      </div>
    </section>
    <div v-if="curriculum?.curriculum" class="workflow-grid">
      <article v-for="module in curriculum.modules" :key="module.name" class="card workflow-card">
        <div class="row-between">
          <h3>{{ module.name }}</h3>
          <span class="tag">{{ module.remainingCredits ? '修读中' : '已满足' }}</span>
        </div>
        <progress
          :value="module.earnedCredits"
          :max="module.requiredCredits || 1"
          :aria-label="module.name + '学分进度'"
        />
        <p>
          {{ module.earnedCredits }} / {{ module.requiredCredits }} 学分 · 还需
          {{ module.remainingCredits }} 学分
        </p>
        <ul class="record-list">
          <li v-for="c in module.courses" :key="c.courseId">
            <span
              >{{ c.title }}<small>{{ c.courseId }}</small></span
            ><span v-if="c.passed">已通过 · 计入 {{ c.credits }} 学分</span>
            <div v-else class="curriculum-offerings"><small>待修读</small>
              <RouterLink v-if="workspace.termId" :to="{path:'/student/catalog',query:{courseId:c.courseId,termId:workspace.termId}}"
                :aria-label="c.title+'：查看本学期教学班'">查看本学期教学班 →</RouterLink>
            </div>
          </li>
        </ul>
      </article>
    </div>
    <StatePanel
      v-else
      title="尚未绑定培养方案"
      description="教务绑定入学届次的方案后，可在这里看到课程要求和进度。"
    />
  </template>
  <template v-else-if="tab === '方案对比'">
    <p class="workflow-note">试排仅计算资格和学分；正式选课时会重新核对席位与时间。</p>
    <div class="workflow-grid">
      <article v-for="side in sides" :key="side" class="card workflow-card">
        <h2>方案 {{ side === 'left' ? 'A' : 'B' }}</h2>
        <label class="field"
          >添加教学班<RemoteSelect
            v-model="sectionPick[side]"
            :path="catalog"
            :label="'方案' + (side === 'left' ? 'A' : 'B') + '教学班'"
            :disabled="busy"
            @select="remember"
        /></label>
        <button
          class="button button-secondary"
          :disabled="busy || !sectionPick[side] || plans[side].includes(sectionPick[side])"
          @click="edit(sectionPick[side], side, 'add')"
        >
          <Plus :size="14" />加入方案
        </button>
        <ul class="record-list">
          <li v-for="id in plans[side]" :key="id">
            <span>{{ sectionLabel(id) }}</span
            ><button
              :aria-label="'移除 ' + id"
              class="icon-button"
              :disabled="busy"
              @click="edit(id, side, 'remove')"
            >
              <Trash2 :size="14" />
            </button>
          </li>
        </ul>
        <p v-if="!plans[side].length" class="workflow-muted">尚未加入教学班。</p>
        <PlanAgenda v-if="compared && plans[side].length" :ids="plans[side]" :term-id="workspace.termId"/>
<div v-if="compared" class="plan-verdict">
          <strong
            >{{ compared[side].feasible ? '方案可行' : '存在资格或课程冲突' }} ·
            {{ compared[side].credits }} 学分</strong
          >
          <div v-for="item in compared[side].items" :key="item.sectionId" class="verdict-row">
            <strong>{{ item.courseTitle }}</strong>
            <span>{{ item.eligible ? '符合当前选课条件' : item.reasons.join('、') }}</span>
            <button
              v-for="alt in item.alternatives"
              :key="alt.sectionId"
              class="text-button"
              :disabled="busy || plans[side].includes(alt.sectionId)"
              @click="edit(item.sectionId, side, 'remove'); edit(alt.sectionId, side, 'add')"
            >
              改选 {{ alt.teacherName }} · {{ alt.sectionId }}
            </button>
            <RouterLink
              :to="{ path: '/student/catalog', query: { section: item.sectionId } }"
              class="text-button"
              >查看教学班 →</RouterLink
            >
          </div>
        </div>
      </article>
    </div>
    <div class="workflow-actions">
      <button class="button" :disabled="busy" @click="act('compare')">比较两个方案</button
      ><button class="button button-secondary" :disabled="busy" @click="act('save')">保存两套方案</button>
    </div>
  </template>
  <article v-else class="card workflow-card">
    <h2>按优先顺序填报志愿</h2>
    <p>首项为第一志愿。教务按已承诺的随机种子和实际约束计算分配。</p>
    <label class="field"
      >选课轮次<select v-model="roundId" :disabled="busy">
        <option v-for="r in rounds" :key="r.id" :value="r.id">{{ r.name }}</option>
      </select></label
    >
    <StatePanel
      v-if="prefRead.loading.value || prefRead.error.value"
      :loading="prefRead.loading.value"
      :error="prefRead.error.value"
      @retry="load"
    />
    <template v-else-if="round"
      ><p class="notice-banner">
        {{
          acceptsRank
            ? '志愿征集中，可填报最多 8 个志愿。'
            : phase === 'COLLECTING'
              ? '本轮已登记承诺，填报需在轮次开放窗口内进行。'
              : phase === 'UNCONFIGURED'
                ? '本轮尚未登记种子承诺。'
                : phase === 'FROZEN'
                  ? '志愿已封存，等待发布结果。'
                  : '本轮已结束，可查看个人结果。'
        }}
      </p>
      <h3 v-if="phase === 'PUBLISHED'">本轮分配结果</h3>
      <ul v-if="allocated.length" class="record-list allocation-list">
        <li v-for="a in allocated" :key="a.sectionId">获分配 {{ a.sectionId }} · 第 {{ a.rank }} 志愿</li>
      </ul>
      <ul v-if="missed.length" class="record-list allocation-list">
        <li v-for="m in missed" :key="m.sectionId">
          <strong>第 {{ m.rank }} 志愿 · {{ m.sectionId }}</strong
          ><span>{{ m.explanation }}</span>
        </li>
      </ul>
      <details v-if="commitment" class="workflow-details"><summary>查看轮次种子承诺</summary><small class="hash-line">{{ commitment }}</small></details>
      <template v-if="acceptsRank">
      <label class="field"
        >志愿教学班<RemoteSelect
          v-model="sectionPick.preference"
          :path="catalog"
          label="志愿教学班"
          :disabled="busy"
          @select="remember"
      /></label>
      <button
        class="button button-secondary"
        :disabled="
          busy ||
          !acceptsRank ||
          ranked.length >= 8 ||
          !sectionPick.preference ||
          ranked.includes(sectionPick.preference)
        "
        @click="edit(sectionPick.preference, 'preference', 'add')"
      >
        <Plus :size="14" />添加志愿
      </button>
      <ol class="record-list">
        <li v-for="(id, i) in ranked" :key="id">
          <strong>{{ i + 1 }}. {{ sectionLabel(id) }}</strong
          ><span class="row">
            <button
              class="icon-button"
              :aria-label="'上移 ' + id"
              :disabled="busy || !acceptsRank || !i"
              @click="shift(i, -1)"
            >
              <ArrowUp :size="14" />
            </button>
            <button
              class="icon-button"
              :aria-label="'下移 ' + id"
              :disabled="busy || !acceptsRank || i === ranked.length - 1"
              @click="shift(i, 1)"
            >
              <ArrowDown :size="14" />
            </button>
            <button
              :aria-label="'删除 ' + id"
              class="icon-button"
              :disabled="busy || !acceptsRank"
              @click="edit(id, 'preference', 'remove')"
            >
              <Trash2 :size="14" />
            </button>
          </span>
        </li>
      </ol>
      <button class="button" :disabled="busy" @click="act('rank')">保存志愿顺序</button>
      </template>
      <template v-else-if="ranked.length && phase !== 'PUBLISHED'">
        <h3>已提交的志愿顺序</h3>
        <ol class="record-list"><li v-for="(id,i) in ranked" :key="id">第 {{i+1}} 志愿 · {{sectionLabel(id)}}</li></ol>
      </template>
    </template>
    <p v-else class="workflow-note"><CircleAlert :size="15" />当前学期没有可查看的轮次。</p>
  </article>
</template>

<style scoped>
/* Published explanations may contain several constraints and long identifiers. */
.allocation-list li { display: grid; grid-template-columns: minmax(0, 1fr); gap: .35rem; justify-items: start; }
.allocation-list li > * { min-width: 0; max-width: 100%; overflow-wrap: anywhere; }
.allocation-list li span { line-height: 1.6; }
.curriculum-offerings { flex: 0 0 auto; text-align: right; }
.curriculum-offerings a { display: inline-block; padding: .25rem 0; color: var(--green, #20573e); font-size: .85rem; text-decoration: underline; text-underline-offset: .16rem; }
</style>
