<script lang="ts">
import { ref } from 'vue'
export const toasts = ref<{ id: number; text: string; error: boolean }[]>([])
export function toast(text: string, error = false) {
  const id = Date.now() + Math.random()
  toasts.value.push({ id, text, error })
  window.setTimeout(() => { toasts.value = toasts.value.filter(item => item.id !== id) }, error ? 10000 : 5500)
}
</script>
<script setup lang="ts">
import { CheckCircle2, CircleAlert, X } from 'lucide-vue-next'
</script>
<template><div class="toast-stack" aria-live="polite" aria-atomic="false"><div v-for="item in toasts" :key="item.id" class="toast" :class="{ 'toast-error': item.error }" :role="item.error ? 'alert' : 'status'"><CircleAlert v-if="item.error" :size="18"/><CheckCircle2 v-else :size="18"/><span>{{ item.text }}</span><button class="icon-button" aria-label="关闭消息" @click="toasts = toasts.filter(t => t.id !== item.id)"><X :size="15"/></button></div></div></template>
