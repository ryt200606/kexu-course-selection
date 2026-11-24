<script setup lang="ts">
import { ref } from 'vue'
import { RouterView, RouterLink, useRoute } from 'vue-router'
import { GraduationCap, BookOpen, LibraryBig, RotateCcw, ArrowUpRight } from 'lucide-vue-next'
import { previewRole, resetPreview } from './data'
import ToastStack from '../components/ToastStack.vue'
function skip() {
  document.getElementById('preview-content')?.focus()
}
const route = useRoute(),
  restarting = ref(false),
  generation = ref(0)
const roles = [
  { key: 'STUDENT', path: '/student/planning', label: '学生', icon: GraduationCap },
  { key: 'TEACHER', path: '/teacher/reviews', label: '教师', icon: BookOpen },
  { key: 'ADMIN', path: '/admin/indicators', label: '教务', icon: LibraryBig },
]
const menus = {
  STUDENT: [
    ['学业规划', '/student/planning'],
    ['课程目录', '/student/catalog'],
    ['成绩单', '/student/grades'],
    ['成绩复核', '/student/reviews'],
  ],
  TEACHER: [['复核工作台', '/teacher/reviews']],
  ADMIN: [
    ['教学指标', '/admin/indicators'],
    ['成绩审核', '/admin/reviews'],
    ['排课结果', '/admin/planning'],
  ],
}
function restore() {
  resetPreview()
  generation.value++
  restarting.value = false
}
</script>
<template>
  <a href="#preview-content" class="skip-link sr-only" @click.prevent="skip()">跳至主要内容</a>
  <header class="preview-header">
    <div class="preview-brand">
      <span class="brand-mark">课</span>
      <div><strong>课序</strong><small>COURSE STUDIO</small></div>
    </div>
    <div class="row">
      <RouterLink class="preview-source" to="/evidence">实测证据</RouterLink
      ><a
        href="https://github.com/ryt200606/kexu-course-selection"
        rel="noopener noreferrer"
        class="preview-source"
        >项目源码 <ArrowUpRight :size="15"
      /></a>
    </div>
  </header>
  <main class="preview-frame">
    <div class="preview-strip">
      <div>
        <strong>公开交互样例</strong>
        <p>使用虚构教学数据，操作只保存在当前浏览器标签页。数据库与求解器运行记录见项目仓库。</p>
      </div>
      <button v-if="!restarting" type="button" class="text-button" @click="restarting = true">
        <RotateCcw :size="14" /> 重置样例</button
      ><span v-else class="row"
        ><button class="text-button" @click="restore">确认重置</button
        ><button class="text-button" @click="restarting = false">取消</button></span
      >
    </div>
    <nav class="preview-roles" aria-label="体验角色">
      <RouterLink
        v-for="r in roles"
        :key="r.key"
        :to="r.path"
        :aria-current="previewRole === r.key ? 'true' : undefined"
        ><component :is="r.icon" :size="17" />{{ r.label }}</RouterLink
      >
    </nav>
    <nav class="preview-menu" aria-label="该角色的功能">
      <RouterLink v-for="[label, path] in menus[previewRole]" :key="path" :to="path">{{ label }}</RouterLink>
    </nav>
    <section id="preview-content" tabindex="-1"><RouterView :key="route.path + '-' + generation" /></section>
  </main>
  <ToastStack />
</template>
<style scoped>
.preview-header,
.preview-frame {
  max-width: 1160px;
  margin: auto;
  padding: 0 clamp(1rem, 4vw, 3.5rem);
}
.preview-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 72px;
  border-bottom: 1px solid #dce3d8;
}
.preview-brand {
  display: flex;
  gap: 0.7rem;
  align-items: center;
}
.preview-brand strong {
  font:
    650 1.3rem/1.2 Inter,
    'Microsoft YaHei',
    sans-serif;
}
.preview-brand small {
  display: block;
  font-size: 0.6rem;
  letter-spacing: 0.12em;
  color: #607363;
}
.preview-source,
.preview-roles a {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  font-size: 0.85rem;
  font-weight: 600;
  color: #375744;
}
.preview-frame {
  padding-bottom: 4rem;
}
.preview-strip {
  margin: 1.25rem 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  border-left: 3px solid #9e864a;
  background: #f1eee1;
  padding: 0.7rem 1rem;
}
.preview-strip strong {
  font-size: 0.88rem;
  color: #564b33;
}
.preview-strip p {
  font-size: 0.79rem;
  color: #595c4c;
}
.preview-roles,
.preview-menu {
  display: flex;
  gap: 0.4rem;
  align-items: center;
  flex-wrap: wrap;
}
.preview-roles a {
  border: 1px solid #cad7c9;
  padding: 0.55rem 0.9rem;
  border-radius: 7px;
  background: white;
}
.preview-roles a[aria-current='true'] {
  background: #214d39;
  color: white;
}
.preview-menu {
  border-bottom: 1px solid #dbe3d9;
  margin: 0.7rem 0 1rem;
}
.preview-menu a {
  padding: 0.6rem 0.65rem;
  font-size: 0.85rem;
}
.preview-menu a.router-link-active {
  border-bottom: 2px solid #416944;
  color: #274f36;
  font-weight: 650;
}
.preview-frame :deep(.page-heading) {
  padding-top: 0.8rem;
}
@media (max-width: 560px) {
  .preview-strip {
    align-items: flex-start;
    flex-direction: column;
    gap: 0.4rem;
  }
  .preview-header {
    height: 61px;
  }
  .preview-roles a {
    padding: 0.5rem 0.75rem;
  }
}
</style>
