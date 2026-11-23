<script setup lang="ts">
import { UserRound, ShieldCheck, Info } from 'lucide-vue-next'
import PageHeading from '../components/PageHeading.vue'
import { useAuth } from '../stores/auth'
import { roleLabel, avatarText } from '../lib/format'
const auth = useAuth()
</script>
<template><PageHeading eyebrow="YOUR SPACE, YOUR IDENTITY" title="个人信息" description="你的教学身份与账户信息，清晰呈现。"/><div class="content-columns"><section class="card card-padding"><div class="profile-summary"><span class="avatar avatar-large">{{ avatarText(auth.user?.displayName) }}</span><div><h2>{{ auth.user?.displayName }}</h2><p>{{ roleLabel(auth.user?.role || '') }} · {{ auth.user?.username }}</p></div></div><dl class="detail-grid"><div><dt>账户名</dt><dd>{{ auth.user?.username }}</dd></div><div><dt>显示名称</dt><dd>{{ auth.user?.displayName }}</dd></div><div><dt>教学角色</dt><dd>{{ roleLabel(auth.user?.role || '') }}</dd></div><div><dt>关联身份</dt><dd>{{ auth.user?.studentId || auth.user?.teacherId || '教务管理账户' }}</dd></div></dl><div class="notice-banner mt-24"><Info :size="16"/><span>账户与学生或教师档案由教务管理员关联。身份信息需要更正时，请联系教务管理员；学生只能操作自己名下的选课与候补。</span></div></section><aside class="aside-card"><div class="aside-icon"><ShieldCheck :size="19"/></div><h3>各有其职，<br>学习安心有序。</h3><p>当前页面根据你的账户身份提供工作空间。退出后会结束本次登录，登录密码不会保存到浏览器存储中。</p><div class="info-list"><div class="info-item"><span><UserRound :size="13"/></span><div><strong>身份清晰关联</strong><small>选课与成绩始终对应实际身份</small></div></div></div></aside></div></template>
