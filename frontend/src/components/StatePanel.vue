<script setup lang="ts">
import { Inbox, CircleAlert, LoaderCircle } from 'lucide-vue-next'
defineProps<{ loading?: boolean; error?: string; title?: string; description?: string }>()
defineEmits<{ retry: [] }>()
</script>
<template><div class="state-panel" :class="{ 'state-error': error }" :role="error ? 'alert' : 'status'"><LoaderCircle v-if="loading" :size="30" class="spin"/><CircleAlert v-else-if="error" :size="30"/><Inbox v-else :size="30"/><h3>{{ loading ? '正在读取教学数据' : error ? '暂时未能加载' : title || '这里还没有记录' }}</h3><p>{{ error || description || (loading ? '请稍候，马上就好。' : '新的记录会在这里井然呈现。') }}</p><button v-if="error" class="button button-secondary" @click="$emit('retry')">重新加载</button><slot/></div></template>
