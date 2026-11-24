<script setup lang="ts">
import { ref } from 'vue'
import PageHeading from '../components/PageHeading.vue'
const step = ref(0)
const stages = [
  {
    name: '约束输入',
    body: '两个教学班、两间教室。A 班同时接受周一和周三，B 班仅能安排在周一。',
    rows: [
      ['A 班', '周一或周三'],
      ['B 班', '仅周一'],
    ],
  },
  {
    name: '对照方案',
    body: '按输入次序的贪心法先给 A 班安排周一，B 班无法上课。换一种分配可排齐两个班。',
    rows: [
      ['贪心示例', '1 / 2 个班'],
      ['可行安排', '2 / 2 个班'],
    ],
  },
  {
    name: '排课结果',
    body: 'A 班改排周三，释放 B 班唯一可用的周一。此处是手工构造的教学案例，实际求解请运行三层部署。',
    rows: [
      ['A 班', '周三 · 教 201'],
      ['B 班', '周一 · 教 201'],
    ],
  },
]
</script>
<template>
  <PageHeading
    eyebrow="SCHEDULING EXAMPLE"
    title="排课为什么需要整体比较"
    description="逐步查看同一组约束如何得到不同结果。"
  />
  <section class="card workflow-card">
    <nav class="workflow-tabs" aria-label="排课样例步骤">
      <button v-for="(s, i) in stages" :key="s.name" :aria-pressed="step === i" @click="step = i">
        {{ s.name }}
      </button>
    </nav>
    <h2>{{ stages[step]?.name }}</h2>
    <p>{{ stages[step]?.body }}</p>
    <ul class="record-list">
      <li v-for="r in stages[step]?.rows" :key="r[0]">
        <strong>{{ r[0] }}</strong
        ><span>{{ r[1] }}</span>
      </li>
    </ul>
    <button v-if="step < 2" class="button" @click="step++">下一步</button>
  </section>
</template>
