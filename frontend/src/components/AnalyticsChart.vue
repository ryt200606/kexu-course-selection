<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { EChartsCoreOption, EChartsType } from 'echarts/core'
echarts.use([BarChart, LineChart, PieChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])
const props = defineProps<{ option: EChartsCoreOption; label: string }>()
const root = ref<HTMLDivElement>()
let chart: EChartsType | undefined, observer: ResizeObserver | undefined
onMounted(() => { chart = echarts.init(root.value); chart.setOption(props.option); observer = new ResizeObserver(() => chart?.resize()); if (root.value) observer.observe(root.value) })
watch(() => props.option, value => chart?.setOption(value, true), { deep: true })
onBeforeUnmount(() => { observer?.disconnect(); chart?.dispose() })
</script>
<template><div ref="root" class="analytics-chart" role="img" :aria-label="label"/></template>
