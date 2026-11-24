<script setup lang="ts">
import type { Meeting } from '../lib/api'
const props = defineProps<{ modelValue: Meeting; rooms?: string[]; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [Meeting] }>()
const days = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
function update(key: keyof Meeting, e: Event) {
  const val = (e.target as HTMLInputElement).value
  emit('update:modelValue', { ...props.modelValue, [key]: key === 'room' ? val : Number(val) })
}
</script>
<template>
  <div class="slot-grid">
    <label class="field"
      >星期<select :value="modelValue.weekday" :disabled="disabled" @change="update('weekday', $event)">
        <option v-for="(d, i) in days" :key="d" :value="i + 1">{{ d }}</option>
      </select></label
    >
    <label v-if="rooms" class="field"
      >教室<select :value="modelValue.room" required :disabled="disabled" @change="update('room', $event)">
        <option value="">选择教室</option>
        <option v-for="id in rooms" :key="id">{{ id }}</option>
      </select></label
    >
    <label class="field"
      >起始节<input
        type="number"
        :value="modelValue.startPeriod"
        min="1"
        max="14"
        required
        :disabled="disabled"
        @input="update('startPeriod', $event)"
    /></label>
    <label class="field"
      >结束节<input
        type="number"
        :value="modelValue.endPeriod"
        :min="modelValue.startPeriod"
        max="14"
        required
        :disabled="disabled"
        @input="update('endPeriod', $event)"
    /></label>
    <label class="field"
      >起始周<input
        type="number"
        :value="modelValue.startWeek"
        min="1"
        max="30"
        required
        :disabled="disabled"
        @input="update('startWeek', $event)"
    /></label>
    <label class="field"
      >结束周<input
        type="number"
        :value="modelValue.endWeek"
        :min="modelValue.startWeek"
        max="30"
        required
        :disabled="disabled"
        @input="update('endWeek', $event)"
    /></label>
  </div>
</template>
