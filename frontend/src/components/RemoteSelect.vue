<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount } from 'vue'
import { Search } from 'lucide-vue-next'
import { useAsync } from '../lib/useAsync'
import { findRecords } from '../lib/lookup'
import type { Row } from '../lib/api'

const props = defineProps<{
  modelValue?: string | number
  path: string
  label: string
  disabled?: boolean
  required?: boolean
  exclude?: string
}>()
const emit = defineEmits<{ 'update:modelValue': [string]; select: [Row] }>()
const keyword = ref(''),
  page = ref(0),
  total = ref(0),
  records = ref<Row[]>([])
const { run, loading, error } = useAsync()
const chosen = ref<Row>()
const idOf = (row: Row) => String(row.id ?? row.enrollmentId ?? row.sectionId)
const options = computed(() => records.value.filter((row) => idOf(row) !== props.exclude))
const visibleCurrent = computed(
  () => String(props.modelValue ?? '') && !options.value.some((row) => idOf(row) === String(props.modelValue))
)
function description(row: Row) {
  const id = idOf(row),
    title = row.courseTitle || row.name || row.title || row.displayName || row.studentName
  return [title, row.teacherName, row.termName, id].filter(Boolean).join(' · ')
}
function load() {
  return run(
    () => findRecords(props.path, keyword.value, page.value),
    (data) => {
      records.value = data.items
      total.value = data.total
    }
  )
}
watch(
  [() => props.path, keyword],
  () => {
    page.value = 0
  },
  { flush: 'sync' }
)
let timer: ReturnType<typeof setTimeout> | undefined
watch(
  [() => props.path, keyword, page, () => props.disabled],
  () => {
    clearTimeout(timer)
    if (props.disabled) return
    timer = setTimeout(load, 180)
  },
  { immediate: true }
)
onBeforeUnmount(() => clearTimeout(timer))
function change(ev: Event) {
  const value = (ev.target as HTMLSelectElement).value
  chosen.value = options.value.find((row) => idOf(row) === value)
  emit('update:modelValue', value)
  if (chosen.value) emit('select', chosen.value)
}
</script>

<template>
  <span class="remote-select">
    <span v-if="!disabled" class="remote-search"
      ><Search :size="14" /><input
        v-model="keyword"
        type="search"
        :aria-label="'搜索' + label"
        placeholder="输入名称或编号搜索"
    /></span>
    <select
      :aria-label="label"
      :value="String(modelValue ?? '')"
      :disabled="disabled"
      :required="required"
      @change="change"
    >
      <option value="">请选择{{ label }}</option>
      <option v-if="visibleCurrent" :value="String(modelValue)">
        {{ chosen && idOf(chosen) === String(modelValue) ? description(chosen) : modelValue }}
      </option>
      <option v-for="row in options" :key="idOf(row)" :value="idOf(row)">{{ description(row) }}</option>
    </select>
    <small v-if="error" role="alert"
      >{{ error }} <button type="button" class="text-button" @click="load">重试</button></small
    >
    <small v-else-if="!disabled && (keyword || total > 20)" class="lookup-pages" aria-live="polite">
      {{ loading ? '正在查询…' : total + ' 条匹配' }}
      <button v-if="page > 0" type="button" class="text-button" @click="page--">上一页</button>
      <button v-if="(page + 1) * 20 < total" type="button" class="text-button" @click="page++">下一页</button>
    </small>
  </span>
</template>
