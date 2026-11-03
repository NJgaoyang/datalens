<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import * as echarts from 'echarts/core';
import { BarChart, LineChart, PieChart } from 'echarts/charts';
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
echarts.use([BarChart, LineChart, PieChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer]);
import type { ChartSpec } from '../api/charts';
import type { QueryResult } from '../api/queries';
import { chartOptions, validateSpec } from '../charts/render';
const props = defineProps<{ spec: ChartSpec; result: QueryResult }>();
const container = ref<HTMLElement>(), error = ref(''), page = ref(1);
let chart: echarts.ECharts|undefined, observer: ResizeObserver|undefined, generation = 0;
const tableColumns = computed(() => props.result.columns.map((column,index) => ({...column,index})).filter(column => !props.spec.measures.length || props.spec.measures.includes(column.name)));
const rows = computed(() => props.result.rows.slice((page.value - 1) * 50, page.value * 50));
const kpi = computed(() => props.result.rows[0]?.[props.result.columns.findIndex(c => c.name === props.spec.measures[0])] ?? 'NULL');
function dispose() { observer?.disconnect(); chart?.dispose(); chart = undefined; }
watch(() => [props.spec, props.result], async () => {
  const epoch = ++generation; dispose(); error.value = ''; page.value = 1;
  try {
    validateSpec(props.spec, props.result); const options = chartOptions(props.spec, props.result);
    await nextTick(); if (epoch !== generation || !options || !container.value) return;
    chart = echarts.init(container.value); chart.setOption(options); observer = new ResizeObserver(() => chart?.resize()); observer.observe(container.value);
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '无法绘图'; }
}, { immediate: true, deep: true });
onBeforeUnmount(() => { generation++; dispose(); });
</script>
<template>
  <el-alert v-if="error" :title="error" type="error" :closable="false"/>
  <template v-else>
    <el-alert v-if="result.truncated" title="结果已截断，仅代表返回的部分数据；不计算总额或占比。" type="warning" :closable="false"/>
    <template v-if="spec.type === 'TABLE'"><el-table :data="rows" max-height="480" empty-text="查询成功，没有匹配数据"><el-table-column v-for="column in tableColumns" :key="column.index" :label="column.name" min-width="160"><template #default="{row}">{{ row[column.index] === null ? 'NULL' : row[column.index] }}</template></el-table-column></el-table><el-pagination v-model:current-page="page" :page-size="50" :total="result.rows.length" layout="prev, pager, next"/></template>
    <div v-else-if="spec.type === 'KPI'" class="chart-kpi"><span>{{ spec.measures[0] }}</span><strong>{{ result.rows.length ? kpi : '暂无数据' }}</strong></div>
    <template v-else><p class="scope-note">坐标仅作近似绘图；提示信息保留原始数值，不对结果再次汇总。</p><div ref="container" class="chart-canvas" role="img" :aria-label="`${spec.type} 图表`"/><details><summary>查看精确数据</summary><el-table :data="rows"><el-table-column v-for="(column,index) in result.columns" :key="index" :label="column.name"><template #default="{row}">{{ row[index] === null ? 'NULL' : row[index] }}</template></el-table-column></el-table><el-pagination v-model:current-page="page" :page-size="50" :total="result.rows.length" layout="prev, pager, next"/></details></template>
  </template>
</template>
