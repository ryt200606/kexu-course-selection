<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { FileCheck2, Send, ClipboardCheck } from 'lucide-vue-next'
import PageHeading from '../components/PageHeading.vue'
import StatePanel from '../components/StatePanel.vue'
import AppModal from '../components/AppModal.vue'
import { api, encode, query, type Enrollment, type Page } from '../lib/api'
import { formatTime } from '../lib/format'
import { formatScore } from '../lib/grades'
import { v3, statusName, type GradeReview } from '../lib/v3'
import { useWorkspace } from '../stores/workspace'
import { useAuth } from '../stores/auth'
import { useAsync } from '../lib/useAsync'

const workspace = useWorkspace(),
  auth = useAuth(),
  { run, loading, error } = useAsync()
const items = ref<GradeReview[]>([]),
  total = ref(0),
  page = ref(0),
  state = ref('')
const eligible = ref<Enrollment[]>([]),
  selected = ref<GradeReview>(),
  modal = ref(false)
const reason = ref(''),
  proposed = ref(''),
  gradeId = ref(''),
  approve = ref(true)
const busy = ref(false),
  actionError = ref(''),
  message = ref('')
const role = computed(() => auth.user?.role)
const action = computed(() => {
  if (!selected.value) return 'apply'
  if (
    role.value === 'TEACHER' &&
    ['REQUESTED', 'SUBMITTED', 'OPEN', 'PENDING'].includes(selected.value.state)
  )
    return 'recommend'
  if (role.value === 'ADMIN' && selected.value.state === 'RECOMMENDED') return 'decide'
  if (role.value === 'TEACHER' && selected.value.state === 'APPROVED') return 'publish'
  return 'read'
})
const actionTitle = computed(
  () =>
    ({
      apply: '申请成绩复核',
      recommend: '提交教师核查意见',
      decide: '记录教务审核决定',
      publish: '发布更正成绩',
      read: '复核记录',
    })[action.value]
)
async function load() {
  if (!workspace.termId) return
  await run(
    () =>
      v3<Page<GradeReview>>(
        '/grade-reviews' + query({ termId: workspace.termId, state: state.value, size: 15, page: page.value })
      ),
    (data) => {
      items.value = data.items
      total.value = data.total
    }
  )
}
watch(
  [() => workspace.termId, state],
  () => {
    page.value = 0
    void load()
  },
  { immediate: true }
)
async function open(item?: GradeReview) {
  selected.value = item
  modal.value = true
  reason.value = ''
  proposed.value = ''
  approve.value = true
  gradeId.value = ''
  actionError.value = ''
  eligible.value = []
  if (item) {
    busy.value = true
    try {
      selected.value = await v3<GradeReview>('/grade-reviews/' + encode(item.id))
      proposed.value = formatScore(selected.value.proposedGrade ?? selected.value.originalGrade)
    } catch (e) {
      actionError.value = e instanceof Error ? e.message : '读取记录失败'
    } finally {
      busy.value = false
    }
    return
  }
  busy.value = true
  try {
    let p = 0
    const available: Enrollment[] = []
    for (;;) {
      const data = await api<Page<Enrollment>>(
        '/student/enrollments' + query({ termId: workspace.termId, size: 100, page: p })
      )
      available.push(...data.items.filter((row) => row.grade != null))
      if (++p * data.size >= data.total || !data.items.length) break
    }
    eligible.value = available
  } catch (e) {
    actionError.value = e instanceof Error ? e.message : '读取成绩失败'
  } finally {
    busy.value = false
  }
}
async function submit() {
  if (busy.value || action.value === 'read') return
  const item = selected.value,
    mode = action.value
  busy.value = true
  actionError.value = ''
  try {
    if (mode === 'apply') {
      await v3('/student/grade-reviews', {
        method: 'POST',
        body: { enrollmentId: gradeId.value, reason: reason.value.trim() },
      })
    } else if (item) {
      const base =
        '/' + (mode === 'decide' ? 'admin' : 'teacher') + '/grade-reviews/' + encode(item.id) + '/' + mode
      const body =
        mode === 'publish'
          ? { version: item.version }
          : mode === 'decide'
            ? { version: item.version, approve: approve.value, reason: reason.value.trim() }
            : { version: item.version, proposedGrade: Number(proposed.value), reason: reason.value.trim() }
      await v3(base, { method: 'POST', body })
    }
    message.value = mode === 'publish' ? '更正已发布，可在成绩单核对。' : '已记录，流程进度已更新。'
    modal.value = false
    await load()
  } catch (e) {
    actionError.value = e instanceof Error ? e.message : '提交失败'
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <PageHeading
    eyebrow="GRADE RECORDS"
    title="成绩复核"
    description="从核查申请到正式发布，逐步记录责任、意见和分数。"
  >
    <select v-model="workspace.termId" class="select-control" aria-label="选择学期">
      <option v-for="t in workspace.terms" :key="t.id" :value="t.id">{{ t.name }}</option>
    </select>
    <button v-if="role === 'STUDENT'" class="button" @click="open()"><Send :size="15" />申请复核</button>
  </PageHeading>
  <div class="workflow-track" aria-label="复核流程">
    <span>学生申请</span><b>→</b><span>教师核查</span><b>→</b><span>教务审核</span><b>→</b
    ><span>教师发布</span>
  </div>
  <p v-if="message" class="notice-banner" role="status">{{ message }}</p>
  <section class="card workflow-card">
    <div class="row-between">
      <h2><FileCheck2 :size="20" /> 复核记录</h2>
      <select v-model="state" class="select-control" aria-label="筛选复核状态">
        <option value="">全部状态</option>
        <option
          v-for="s in ['REQUESTED', 'RECOMMENDED', 'APPROVED', 'REJECTED', 'PUBLISHED', 'STALE']"
          :key="s"
          :value="s"
        >
          {{ statusName(s) }}
        </option>
      </select>
    </div>
    <StatePanel v-if="loading || error" :loading="loading" :error="error" @retry="load" />
    <StatePanel
      v-else-if="!items.length"
      title="没有符合条件的复核记录"
      description="学生可针对已发布成绩提交复核。"
    />
    <template v-else
      ><div class="table-wrap">
        <table class="mobile-records">
          <caption class="sr-only">
            成绩复核记录
          </caption>
          <thead>
            <tr>
              <th>课程 / 学生</th>
              <th>原成绩</th>
              <th>建议成绩</th>
              <th>进度</th>
              <th>处理</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in items" :key="item.id">
              <td data-label="课程 / 学生">
                <strong>{{ item.courseTitle }}</strong
                ><small>{{ item.studentName }} · {{ item.studentId }}</small>
              </td>
              <td data-label="原成绩">{{ formatScore(item.originalGrade) }}</td>
              <td data-label="建议成绩">{{ formatScore(item.proposedGrade) }}</td>
              <td data-label="进度">
                <span class="tag">{{ statusName(item.state) }}</span>
              </td>
              <td data-label="操作"><button class="text-button" @click="open(item)">查看记录 →</button></td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="workflow-actions">
        <span>{{ total }} 条 · 第 {{ page + 1 }} 页</span
        ><button
          class="text-button"
          :disabled="page === 0"
          @click="page--; load()"
        >
          上一页</button
        ><button
          class="text-button"
          :disabled="(page + 1) * 15 >= total"
          @click="page++; load()"
        >
          下一页
        </button>
      </div></template
    >
  </section>
  <AppModal :open="modal" :title="actionTitle || '复核记录'" :busy="busy" @close="modal = false">
    <p v-if="actionError" class="error-inline" role="alert">{{ actionError }}</p>
    <div v-if="selected" class="detail-summary">
      <h3>{{ selected.courseTitle }} · {{ selected.studentName }}</h3>
      <p>
        正式原分 {{ formatScore(selected.originalGrade) }} · 建议 {{ formatScore(selected.proposedGrade) }}
      </p>
      <p><strong>申请说明</strong>　{{ selected.studentReason }}</p>
      <p v-if="selected.teacherReason"><strong>教师核查</strong>　{{ selected.teacherReason }}</p>
      <p v-if="selected.adminReason"><strong>教务决定</strong>　{{ selected.adminReason }}</p>
      <details v-if="selected.history?.length" class="workflow-details">
        <summary>处理时间线</summary>
        <ul class="record-list">
          <li v-for="(e, i) in selected.history" :key="i">
            <span
              >{{ e.message }}<small>{{ e.actorName }} · {{ formatTime(e.createdAt) }}</small></span
            >
          </li>
        </ul>
      </details>
      <p v-if="selected.crossesPassThreshold" class="notice-banner">
        本次更正跨越及格线，正式发布后学分与资格将重新计算。
      </p>
    </div>
    <form v-if="action !== 'read'" id="review-action" class="workflow-form" @submit.prevent="submit">
      <label v-if="action === 'apply'" class="field"
        >已发布成绩<select v-model="gradeId" required>
          <option value="">选择需要复核的课程</option>
          <option v-for="e in eligible" :key="e.id" :value="e.id">
            {{ e.courseTitle }} · {{ formatScore(e.grade) }}
          </option></select
        ><small v-if="!busy && !eligible.length">本学期没有可申请复核的正式成绩。</small></label
      >
      <label v-if="action === 'recommend'" class="field"
        >建议成绩<input v-model="proposed" type="number" min="0" max="100" step="0.01" required
      /></label>
      <label v-if="action === 'decide'" class="field"
        >审核决定<select v-model="approve">
          <option :value="true">批准，由任课教师发布</option>
          <option :value="false">驳回，保留原成绩</option>
        </select></label
      >
      <label v-if="action !== 'publish'" class="field"
        >具体说明<textarea
          v-model="reason"
          minlength="5"
          maxlength="500"
          required
          placeholder="说明申请理由或核查依据"
        />
      </label>
      <p v-if="action === 'publish'" class="notice-banner">
        <ClipboardCheck :size="16" />发布后学生可看到教务批准的新成绩。
      </p>
    </form>
    <template #footer
      ><button class="button button-secondary" :disabled="busy" @click="modal = false">关闭</button
      ><button v-if="action !== 'read'" type="submit" form="review-action" class="button" :disabled="busy">
        {{ actionTitle }}
      </button></template
    >
  </AppModal>
</template>
