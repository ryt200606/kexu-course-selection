<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import { Bell, CheckCheck, Check, ChevronLeft, ChevronRight } from 'lucide-vue-next'
import PageHeading from '../components/PageHeading.vue'
import StatePanel from '../components/StatePanel.vue'
import { toast } from '../components/ToastStack.vue'
import { api, query, encode, type Page, type Notification } from '../lib/api'
import { useAsync } from '../lib/useAsync'
import { formatTime } from '../lib/format'
const { loading, error, run } = useAsync(), items = ref<Notification[]>([]), page = ref(0), total = ref(0), busy = ref(false), size = 15
const unread = computed(() => items.value.filter(item => !item.readAt)), pages = computed(() => Math.max(1, Math.ceil(total.value / size)))
async function load() { await run(async () => { const result = await api<Page<Notification>>('/notifications' + query({ page: page.value, size })); items.value = result.items; total.value = result.total }) }
async function mark(item: Notification) { busy.value = true; try { await api('/notifications/' + encode(item.id) + '/read', { method: 'PUT' }); await load() } catch (e) { toast(e instanceof Error ? e.message : '标记失败', true) } finally { busy.value = false } }
async function markPage() { busy.value = true; try { for (const item of unread.value) await api('/notifications/' + encode(item.id) + '/read', { method: 'PUT' }); toast('本页消息已全部标为已读。'); await load() } catch (e) { toast(e instanceof Error ? e.message : '标记失败，部分消息可能已成功处理。', true); await load() } finally { busy.value = false } }
function paginate(delta: number) { page.value += delta; void load() }
onMounted(load)
</script>
<template><PageHeading eyebrow="STAY IN THE KNOW" title="消息中心" description="重要的教学动态与候补进展，及时为你送达。"><button class="button button-secondary" :disabled="busy || !unread.length" @click="markPage"><CheckCheck :size="14"/>本页标为已读</button></PageHeading><section class="card"><div class="card-header"><div><h2>我的消息</h2><p>共 {{ total }} 条记录 · 本页 {{ unread.length }} 条未读</p></div><button class="text-button" :disabled="loading || busy" @click="load">刷新消息 ↻</button></div><StatePanel v-if="loading" loading/><StatePanel v-else-if="error" :error="error" @retry="load"/><StatePanel v-else-if="!items.length" title="暂时没有新消息" description="选课、候补与成绩的相关通知会在这里呈现。"/><div v-else class="notification-list"><article v-for="item in items" :key="item.id" class="notification-item" :class="{ unread: !item.readAt }"><span class="notification-icon"><Bell :size="17"/></span><div class="spacer"><h3>{{ item.title }}</h3><p>{{ item.message }}</p><time :datetime="item.createdAt">{{ formatTime(item.createdAt) }}</time></div><button v-if="!item.readAt" class="text-button" :disabled="busy" @click="mark(item)"><Check :size="12"/>标为已读</button><span v-else class="muted" style="font-size:10px">已读</span></article></div><div v-if="total" class="card-footer"><span>第 {{ page + 1 }} / {{ pages }} 页</span><div class="pagination"><button aria-label="上一页" :disabled="page === 0 || loading" @click="paginate(-1)"><ChevronLeft :size="14"/></button><button aria-label="下一页" :disabled="page + 1 >= pages || loading" @click="paginate(1)"><ChevronRight :size="14"/></button></div></div></section></template>
