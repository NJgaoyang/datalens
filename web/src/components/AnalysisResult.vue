<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import { describeTimeRule, type ConversationTurn } from '../api/conversations';
import type { QueryResult } from '../api/queries';
import ChartRenderer from './ChartRenderer.vue';
const props = defineProps<{ turn: ConversationTurn; result: QueryResult }>();
type Tab = 'data'|'chart'|'details';
const tab = ref<Tab>('data'), page = ref(1);
const hasChart = computed(() => !!props.turn.spec && props.turn.spec.type !== 'TABLE');
const tabs: { id: Tab; label: string }[] = [{ id: 'data', label: '数据表格' }, { id: 'chart', label: '图表' }, { id: 'details', label: '查询说明' }];
const rows = computed(() => props.result.rows.slice((page.value - 1) * 10, page.value * 10));
watch(() => [props.turn.id, props.result], () => { page.value = 1; tab.value = hasChart.value ? 'chart' : 'data'; }, { immediate: true });
async function moveTab(event: KeyboardEvent) {
  if (!['ArrowLeft','ArrowRight','Home','End'].includes(event.key)) return;
  event.preventDefault();
  const root = event.currentTarget as HTMLElement;
  const enabled: Tab[] = hasChart.value ? ['data','chart','details'] : ['data','details'];
  const index = enabled.indexOf(tab.value);
  tab.value = enabled[event.key === 'Home' ? 0 : event.key === 'End' ? enabled.length - 1 : (index + (event.key === 'ArrowRight' ? 1 : -1) + enabled.length) % enabled.length]!;
  await nextTick(); root.querySelector<HTMLButtonElement>('[aria-selected="true"]')?.focus();
}
</script>
<template>
  <section class="analysis-result" :aria-label="`第 ${turn.sequence} 轮查询结果`">
    <div class="result-tabs" role="tablist" aria-label="结果展示方式" @keydown="moveTab">
      <button v-for="item in tabs" :id="`result-${turn.id}-${item.id}`" :key="item.id" type="button" role="tab" :aria-selected="tab === item.id" :aria-controls="`result-panel-${turn.id}`" :tabindex="tab === item.id ? 0 : -1" :disabled="item.id === 'chart' && !hasChart" :title="item.id === 'chart' && !hasChart ? '当前查询未提供图表配置，请先使用数据表格查看' : undefined" @click="tab = item.id">{{ item.label }}</button>
      <span>本次返回 {{ result.rows.length }} 行</span>
    </div>
    <div class="result-context"><p><strong>指标口径：</strong>{{ turn.metricDescription || '未提供' }}</p><p>{{ describeTimeRule(turn.timeRule) }}</p></div>
    <el-alert v-if="result.truncated && tab !== 'chart'" title="结果已截断，仅代表返回的部分数据；不计算总额或占比。" type="warning" :closable="false" />
    <div :id="`result-panel-${turn.id}`" role="tabpanel" :aria-labelledby="`result-${turn.id}-${tab}`" tabindex="0" class="result-panel">
      <template v-if="tab === 'data'">
        <el-table :data="rows" max-height="420" empty-text="查询成功，没有匹配数据">
          <el-table-column v-for="(column, index) in result.columns" :key="index" :label="column.name" min-width="160" resizable show-overflow-tooltip><template #default="{ row }"><span :class="{ 'null-value': row[index] === null }">{{ row[index] === null ? 'NULL' : row[index] }}</span></template></el-table-column>
        </el-table>
        <div class="result-pagination"><span>仅分页显示本次返回的数据</span><el-pagination v-model:current-page="page" :page-size="10" :total="result.rows.length" layout="prev, pager, next" hide-on-single-page /></div>
      </template>
      <ChartRenderer v-else-if="tab === 'chart' && turn.spec" :spec="turn.spec" :result="result" />
      <dl v-else class="result-facts"><dt>查询任务</dt><dd>{{ turn.taskId || '未提供' }}</dd><dt>返回上限</dt><dd>{{ result.rowLimit }} 行{{ result.truncated ? '，结果已截断' : '' }}</dd><dt>展示说明</dt><dd>切换表格和图表不会重新查询，也不会改变保存的图表配置。金额与大数值保持查询返回的原始精度。</dd><dt>历史记录</dt><dd>这是本轮查询结果。打开历史会话不会自动重新执行 SQL；已保存图表在详情页单独刷新。</dd></dl>
    </div>
    <div class="result-time">查询完成时间：{{ turn.queryTime || '尚未完成' }}</div>
  </section>
</template>
<style scoped>
.analysis-result{min-width:0;border:1px solid #e2e7ed;border-radius:6px;background:#fff;overflow:hidden}.result-tabs{display:flex;align-items:center;gap:4px;padding:0 14px;border-bottom:1px solid #e7ebf0;background:#fafbfc}.result-tabs button{padding:13px 11px;border:0;border-bottom:2px solid transparent;background:none;color:#6c7889;font-size:13px;cursor:pointer}.result-tabs button[aria-selected=true]{border-bottom-color:#3568bd;color:#285da8;font-weight:600}.result-tabs button:disabled{color:#b0b7c0;cursor:not-allowed}.result-tabs>span{margin-left:auto;font-size:11px;color:#8993a0}.result-context{padding:13px 18px 0;display:grid;gap:5px;font-size:12px;line-height:1.7;color:#6b7889}.result-context strong{font-weight:500;color:#48586b}.result-panel{padding:14px 18px;min-width:0;outline-offset:-2px}.result-pagination{display:flex;justify-content:space-between;align-items:center;gap:10px;margin-top:12px;font-size:11px;color:#8c96a4;flex-wrap:wrap}.result-time{padding:10px 18px;background:#fafbfc;border-top:1px solid #edf0f4;font-size:11px;color:#7c8796;overflow-wrap:anywhere}.result-facts{display:grid;grid-template-columns:80px minmax(0,1fr);gap:15px 20px;font-size:12px;line-height:1.8;margin:0;padding:5px 0}.result-facts dt{color:#8590a0}.result-facts dd{margin:0;overflow-wrap:anywhere;color:#536276}.analysis-result>.el-alert{margin:12px;width:auto}@media(max-width:700px){.result-tabs{padding:0 6px}.result-tabs>span{display:none}.result-panel{padding:10px}.result-facts{grid-template-columns:1fr;gap:5px}}
</style>
