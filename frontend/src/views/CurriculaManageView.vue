<script setup lang="ts">
import { ref } from 'vue'
import { BookOpen, Plus, Trash2 } from 'lucide-vue-next'
import PageHeading from '../components/PageHeading.vue'
import RemoteSelect from '../components/RemoteSelect.vue'
import StatePanel from '../components/StatePanel.vue'
import AppModal from '../components/AppModal.vue'
import { query, encode, type Page, type Row } from '../lib/api'
import { v3 } from '../lib/v3'
import { useAsync } from '../lib/useAsync'
interface Entry {
  id: string
  cohort: string
  name: string
  version: number
  studentCount?: number
}
type Module = { name: string; requiredCredits: number; courseIds: string[]; pick: string }
const { run, loading, error } = useAsync(),
  rows = ref<Entry[]>([]),
  page = ref(0),
  total = ref(0)
const dialog = ref(''),
  current = ref<Entry>(),
  cohort = ref(''),
  name = ref(''),
  modules = ref<Module[]>([]),
  ids = ref<string[]>([]),
  pick = ref('')
const labels = ref<Record<string, string>>({}),
  busy = ref(false),
  issue = ref(''),
  message = ref('')
interface Progress {
  studentId: string
  studentName: string
  department: string
  earnedCredits: number
  requiredCredits: number
  remainingCredits: number
}
const progress = ref<Progress[]>([]),
  detailPage = ref(0),
  detailTotal = ref(0)
async function progressPage() {
  if (!current.value) return
  busy.value = true
  issue.value = ''
  try {
    const data = await v3<Page<Progress>>(
      '/admin/curricula/' +
        encode(current.value.id) +
        '/progress' +
        query({ page: detailPage.value, size: 20 })
    )
    progress.value = data.items
    detailTotal.value = data.total
  } catch (e) {
    issue.value = e instanceof Error ? e.message : '读取修读进度失败'
  } finally {
    busy.value = false
  }
}
function showProgress(r: Entry) {
  start('progress', r)
  detailPage.value = 0
  progress.value = []
  void progressPage()
}

function blank() {
  return { name: '', requiredCredits: 3, courseIds: [], pick: '' }
}
function remember(row: Row) {
  labels.value[String(row.id)] = String(row.name || row.title || row.id)
}
function load() {
  return run(
    () => v3<Page<Entry>>('/admin/curricula' + query({ page: page.value, size: 15 })),
    (data) => {
      rows.value = data.items
      total.value = data.total
    }
  )
}
void load()
function start(mode: string, row?: Entry) {
  dialog.value = mode
  current.value = row
  cohort.value = row?.cohort || ''
  name.value = row?.name || ''
  modules.value = [blank()]
  ids.value = []
  pick.value = ''
  issue.value = ''
}
async function save() {
  busy.value = true
  issue.value = ''
  try {
    if (dialog.value === 'assign' && current.value) {
      await v3('/admin/curricula/' + encode(current.value.id) + '/assign', {
        method: 'POST',
        body: { studentIds: ids.value },
      })
      message.value = '所选学生已绑定本版本。'
    } else {
      if (modules.value.some((m) => !m.courseIds.length)) throw new Error('每个模块至少添加一门课程。')
      await v3('/admin/curricula', {
        method: 'POST',
        body: {
          name: name.value.trim(),
          cohort: cohort.value.trim(),
          modules: modules.value.map(({ name, requiredCredits, courseIds }) => ({
            name: name.trim(),
            requiredCredits,
            courseIds,
          })),
        },
      })
      message.value = '新版本已创建，可继续绑定学生。'
    }
    dialog.value = ''
    await load()
  } catch (e) {
    issue.value = e instanceof Error ? e.message : '保存失败'
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <PageHeading
    eyebrow="CURRICULUM REGISTRY"
    title="培养方案"
    description="按入学届次发布课程模块，将明确的方案版本绑定到学生。"
    ><button class="button" @click="start('create')"><Plus :size="15" />新建方案版本</button></PageHeading
  >
  <p v-if="message" role="status" class="notice-banner">{{ message }}</p>
  <section class="card workflow-card">
    <h2><BookOpen :size="20" /> 已发布版本</h2>
    <StatePanel v-if="loading || error" :loading="loading" :error="error" @retry="load" />
    <StatePanel
      v-else-if="!rows.length"
      title="暂无培养方案"
      description="先定义届次、必修模块和学分要求。"
    />
    <template v-else
      ><ul class="record-list">
        <li v-for="r in rows" :key="r.id">
          <span
            ><strong>{{ r.name }}</strong
            ><small
              >{{ r.cohort }} 级 · 第 {{ r.version }} 版<span v-if="r.studentCount != null">
                · {{ r.studentCount }} 名学生</span
              ></small
            ></span
          ><span
            ><button class="text-button" @click="showProgress(r)">修读进度</button> ·
            <button class="text-button" @click="start('assign', r)">绑定学生 →</button></span
          >
        </li>
      </ul>
      <div class="workflow-actions">
        <span>{{ total }} 个版本</span
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
  <AppModal
    :open="!!dialog"
    :title="dialog === 'progress' ? '学生修读进度' : dialog === 'assign' ? '绑定学生' : '新建培养方案版本'"
    :busy="busy"
    @close="dialog = ''"
  >
    <p v-if="issue" class="error-inline" role="alert">{{ issue }}</p>
    <template v-if="dialog === 'progress'"
      ><p>{{ current?.name }} · 第 {{ current?.version }} 版</p>
      <ul class="record-list">
        <li v-for="s in progress" :key="s.studentId">
          <span
            >{{ s.studentName }} · {{ s.studentId }}<small>{{ s.department }}</small></span
          ><span>{{ s.earnedCredits }} / {{ s.requiredCredits }} 学分 · 还需 {{ s.remainingCredits }}</span>
        </li>
      </ul>
      <p v-if="!busy && !progress.length">该版本尚未绑定学生。</p>
      <div class="workflow-actions">
        <span>{{ detailTotal }} 人</span
        ><button
          class="text-button"
          :disabled="busy || !detailPage"
          @click="detailPage--; progressPage()"
        >
          上一页</button
        ><button
          class="text-button"
          :disabled="busy || (detailPage + 1) * 20 >= detailTotal"
          @click="detailPage++; progressPage()"
        >
          下一页
        </button>
      </div></template
    >
    <form v-else id="curriculum-action" class="workflow-form" @submit.prevent="save">
      <template v-if="dialog === 'assign'">
        <p>{{ current?.name }} · {{ current?.cohort }} 级 · 第 {{ current?.version }} 版</p>
        <label class="field"
          >学生档案<RemoteSelect v-model="pick" path="/admin/students" label="待绑定学生" @select="remember"
        /></label>
        <button
          type="button"
          class="button button-secondary"
          :disabled="!pick || ids.includes(pick)"
          @click="ids.push(pick); pick = ''"
        >
          加入绑定名单
        </button>
        <ul class="record-list">
          <li v-for="(id, i) in ids" :key="id">
            <span>{{ labels[id] }} · {{ id }}</span
            ><button type="button" class="text-button" @click="ids.splice(i, 1)">移除</button>
          </li>
        </ul>
      </template>
      <template v-else>
        <label class="field"
          >方案名称<input
            v-model="name"
            required
            maxlength="100"
            placeholder="例如：计算机科学与技术本科培养方案"
        /></label>
        <label class="field"
          >入学届次<input v-model="cohort" required maxlength="30" placeholder="例如：2026"
        /></label>
        <fieldset v-for="(m, i) in modules" :key="i" class="workflow-fieldset">
          <legend>课程模块 {{ i + 1 }}</legend>
          <label class="field">模块名称<input v-model="m.name" required maxlength="100" /></label>
          <label class="field"
            >要求学分<input
              v-model.number="m.requiredCredits"
              type="number"
              step="1"
              min="1"
              max="300"
              required
          /></label>
          <label class="field"
            >模块课程<RemoteSelect v-model="m.pick" path="/admin/courses" label="模块课程" @select="remember"
          /></label>
          <button
            type="button"
            class="text-button"
            :disabled="!m.pick || m.courseIds.includes(m.pick)"
            @click="m.courseIds.push(m.pick); m.pick = ''"
          >
            加入课程
          </button>
          <ul class="record-list">
            <li v-for="(id, j) in m.courseIds" :key="id">
              <span>{{ labels[id] }} · {{ id }}</span
              ><button class="text-button" type="button" @click="m.courseIds.splice(j, 1)">移除</button>
            </li>
          </ul>
          <button v-if="modules.length > 1" type="button" class="text-button" @click="modules.splice(i, 1)">
            <Trash2 :size="14" /> 删除此模块
          </button>
        </fieldset>
        <button type="button" class="button button-secondary" @click="modules.push(blank())">
          <Plus :size="14" />添加模块
        </button>
      </template>
    </form>
    <template #footer
      ><button class="button button-secondary" :disabled="busy" @click="dialog = ''">取消</button
      ><button
        v-if="dialog !== 'progress'"
        type="submit"
        form="curriculum-action"
        class="button"
        :disabled="busy || (dialog === 'assign' && !ids.length)"
      >
        {{ dialog === 'assign' ? '绑定所选学生' : '创建新版本' }}
      </button></template
    >
  </AppModal>
</template>
