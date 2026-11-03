<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue';
import { session } from '../api/auth';
import { listCharts, type ChartSummary, type ChartScope } from '../api/charts';
import { errorMessage } from '../api/http';
const sections: {scope:ChartScope; title:string; path:string}[] = [
  {scope:'mine',title:'我的图表',path:'/charts/mine'},
  {scope:'shared',title:'共享给我',path:'/charts/shared'},
  {scope:'all',title:'全员共享',path:'/charts/public'},
];
const groups = ref<{total:number; items:ChartSummary[]}[]>([]), busy = ref(false), error = ref('');
let controller: AbortController|undefined;
async function load() {
  controller?.abort(); const current = new AbortController(); controller = current; groups.value = []; error.value = ''; busy.value = true;
  try {
    const values = await Promise.all(sections.map(section => listCharts(current.signal,1,section.scope)));
    if (!current.signal.aborted) groups.value = values.map(value => ({total:value.total,items:value.items.slice(0,5)}));
  } catch (cause) { if (!current.signal.aborted) error.value = errorMessage(cause); }
  finally { if (!current.signal.aborted) busy.value = false; }
}
watch(() => session.user?.id, () => { controller?.abort(); groups.value = []; if (session.user) void load(); }, {immediate:true});
onBeforeUnmount(() => controller?.abort());
</script>
<template><section class="page">
  <div class="page-heading"><div><div class="eyebrow">DATALENS / WORKSPACE</div><h1>工作台</h1><p class="subtitle">从问题到图表，继续查看和管理你的分析资产。</p></div><RouterLink to="/ask" class="el-button el-button--primary">开始智能问数</RouterLink></div>
  <el-alert v-if="error" :title="error" type="error" :closable="false"/>
  <div class="query-actions"><RouterLink to="/charts/new">新建 SQL 图表</RouterLink><RouterLink to="/charts/trash">图表回收站</RouterLink><el-button :loading="busy" @click="load">刷新资产列表</el-button></div>
  <p class="scope-note">只读取已授权资产的名称与数量，不执行图表查询。进入详情后才刷新数据。</p>
  <p v-if="busy" role="status">正在读取工作台…</p>
  <div v-if="groups.length" class="workbench-grid"><section v-for="(section,index) in sections" :key="section.scope" class="panel"><div class="panel-heading"><h2>{{ section.title }}</h2><span>{{ groups[index]?.total ?? 0 }} 个</span></div>
    <p v-if="!groups[index]?.items.length">暂无可查看的图表。</p><ul class="chart-list"><li v-for="chart in groups[index]?.items" :key="chart.id"><RouterLink :to="`/charts/${encodeURIComponent(chart.id)}`">{{ chart.name }}</RouterLink><span>更新于 {{ chart.updatedAt }}</span></li></ul><RouterLink :to="section.path">查看与搜索{{ section.title }}</RouterLink>
  </section></div>
</section></template>
