<script setup lang="ts">defineProps<{ label: string; value: string | number; note?: string; accent?: boolean }>()</script>
<template><article class="stat-card" :class="{ 'stat-accent': accent }"><span class="stat-label">{{ label }}</span><strong>{{ value }}</strong><small>{{ note }}</small><div class="stat-symbol" aria-hidden="true"><slot/></div></article></template>
