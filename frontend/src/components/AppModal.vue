<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch, nextTick, useId } from 'vue'
import { X } from 'lucide-vue-next'
const props = withDefaults(defineProps<{ open: boolean; title: string; wide?: boolean; busy?: boolean }>(), { wide: false, busy: false })
const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLDialogElement>()
const titleId = useId()
let previousFocus: HTMLElement | null = null
watch(() => props.open, async value => {
  await nextTick()
  if (value && !dialog.value?.open) { previousFocus = document.activeElement as HTMLElement; dialog.value?.showModal() }
  else if (!value && dialog.value?.open) { dialog.value.close(); previousFocus?.focus() }
}, { immediate: true })
function cancel(event: Event) { event.preventDefault(); if (!props.busy) emit('close') }
onMounted(() => { if (props.open && !dialog.value?.open) dialog.value?.showModal() })
onBeforeUnmount(() => { dialog.value?.close(); previousFocus?.focus() })
</script>
<template><dialog ref="dialog" class="modal" :class="{ 'modal-wide': wide }" :aria-labelledby="titleId" @cancel="cancel"><div class="modal-header"><div><p class="eyebrow">COURSE STUDIO</p><h2 :id="titleId">{{ title }}</h2></div><button class="icon-button" aria-label="关闭对话框" :disabled="busy" @click="emit('close')"><X :size="21"/></button></div><div class="modal-body"><slot /></div><div v-if="$slots.footer" class="modal-footer"><slot name="footer"/></div></dialog></template>
