<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import type { ConversationTurn } from '../api/conversations';
import type { QueryResult } from '../api/queries';
import ChartRenderer from './ChartRenderer.vue';
const props = defineProps<{ turn: ConversationTurn; result: QueryResult }>();
type Tab = 'data'|'chart'|'details';
const tab = ref<Tab>('data'), page = ref(1);
const hasChart = computed(() => !!props.turn.spec && props.turn.spec.type !== 'TABLE');
const tabs: { id: Tab; label: string }[] = [{ id: 'chart', label: '图表' }, { id: 'data', label: '数据表格' }, { id: 'details', label: '查询说明' }];
const rows = computed(() => props.result.rows.slice((page.value - 1) * 10, page.value * 10));
const chartHeading = computed(() => props.turn.spec?.dimension ? '按 ' + props.turn.spec.dimension + ' 分析' : '本次查询结果');
const facts = computed(() => '本次返回 ' + props.result.rows.length + ' 行、' + props.result.columns.length + ' 列数据');
const topValue = computed(() => {
  if (props.result.truncated || !props.turn.spec?.dimension || props.turn.spec.measures.length !== 1 || props.result.rows.length < 2) return null;
  const name = props.turn.spec.measures[0]!, measureIndex = props.result.columns.findIndex(column => column.name === name);
  const dimIndex = props.result.columns.findIndex(column => column.name === props.turn.spec?.dimension);
  if (measureIndex < 0 || dimIndex < 0 || props.result.rows.length > 500) return null;
  // Exact decimal-only comparison: avoid binary floating point for money and large integers.
  const values: { label: string; value: string; scaled: bigint; scale: number }[] = [];
  for (const row of props.result.rows) {
    const value = row[measureIndex], label = row[dimIndex];
    if (!value || label == null) return null;
    const match = /^([+-]?)(\d+)(?:\.(\d+))?$/.exec(value);
    if (!match) return null;
    const scale = (match[3] || '').length;
    const scaled = BigInt((match[1] === '-' ? '-' : '') + (match[2] || '0') + (match[3] || ''));
    values.push({ label, value, scaled, scale });
  }
  if (!values.length) return null;
  const maxScale = Math.max(...values.map(item => item.scale));
  let winner = values[0]!;
  for (const item of values.slice(1)) {
    if (item.scaled * 10n ** BigInt(maxScale - item.scale) > winner.scaled * 10n ** BigInt(maxScale - winner.scale)) winner = item;
  }
  return { label: winner.label, value: winner.value, measure: name };
});
watch(() => [props.turn.id, props.result], () => { page.value = 1; tab.value = hasChart.value ? 'chart' : 'data'; }, { immediate: true });
async function moveTab(event: KeyboardEvent) {
  if (!['ArrowLeft','ArrowRight','Home','End'].includes(event.key)) return;
  event.preventDefault();
  const root = event.currentTarget as HTMLElement;
  const enabled: Tab[] = hasChart.value ? ['chart','data','details'] : ['data','details'];
  const index = enabled.indexOf(tab.value);
  tab.value = enabled[event.key === 'Home' ? 0 : event.key === 'End' ? enabled.length - 1 : (index + (event.key === 'ArrowRight' ? 1 : -1) + enabled.length) % enabled.length]!;
  await nextTick(); root.querySelector<HTMLButtonElement>('[aria-selected="true"]')?.focus();
}
</script>
<template>
  <section class="analysis-result" :aria-label="'第 ' + turn.sequence + ' 轮查询结果'">
    <header class="result-toolbar">
      <div class="result-tabs" role="tablist" aria-label="结果展示方式" @keydown="moveTab">
        <button v-for="item in tabs" :id="'result-' + turn.id + '-' + item.id" :key="item.id" type="button" role="tab" :aria-selected="tab === item.id" :aria-controls="'result-panel-' + turn.id" :tabindex="tab === item.id ? 0 : -1" :disabled="item.id === 'chart' && !hasChart" :title="item.id === 'chart' && !hasChart ? '当前指标只提供表格结果' : undefined" @click="tab = item.id">{{ item.label }}</button>
      </div>
      <div class="result-actions"><slot name="actions" /></div>
    </header>
    <div :id="'result-panel-' + turn.id" role="tabpanel" :aria-labelledby="'result-' + turn.id + '-' + tab" tabindex="0" class="result-panel">
      <div v-if="tab !== 'details'" class="result-title">
        <strong>{{ chartHeading }}</strong>
        <span>{{ facts }}</span>
      </div>
      <el-alert v-if="result.truncated && tab !== 'details'" title="结果已截断，仅代表返回的部分数据；不计算总额或占比。" type="warning" :closable="false" />
      <template v-if="tab === 'data'">
        <el-table :data="rows" max-height="370" empty-text="查询成功，没有匹配数据">
          <el-table-column v-for="(column, index) in result.columns" :key="index" :label="column.name" min-width="140" resizable show-overflow-tooltip><template #default="{ row }"><span :class="{ 'null-value': row[index] === null }">{{ row[index] === null ? 'NULL' : row[index] }}</span></template></el-table-column>
        </el-table>
        <div class="result-pagination"><span>原始值精确展示 · 仅分页本次返回数据</span><el-pagination v-model:current-page="page" :page-size="10" :total="result.rows.length" layout="prev, pager, next" hide-on-single-page /></div>
      </template>
      <ChartRenderer v-else-if="tab === 'chart' && turn.spec" :spec="turn.spec" :result="result" />
      <dl v-else class="result-facts"><dt>查询任务</dt><dd>{{ turn.taskId || '未提供' }}</dd><dt>返回上限</dt><dd>{{ result.rowLimit }} 行{{ result.truncated ? '，结果已截断' : '' }}</dd><dt>指标字段</dt><dd>{{ turn.spec?.measures.join('、') || '未提供' }}</dd><dt>查询说明</dt><dd>所有字段均属于当前授权范围。切换图表和表格不会重新查询，数值表保留原始精度。</dd><dt>历史记录</dt><dd>当前展示本轮查询预览；打开已保存图表时才会触发独立刷新。</dd></dl>
      <div v-if="tab !== 'details' && result.rows.length" class="result-insight"><span class="result-insight-icon" aria-hidden="true">✦</span><strong>数据洞察</strong><div><p>{{ facts }}；请根据实际口径理解结果。</p><p v-if="topValue">在本次完整返回的数据中，{{ topValue.label }} 的 {{ topValue.measure }} 最大，为 {{ topValue.value }}。</p><p v-if="result.truncated">当前结果不完整，不提供整体排名或占比结论。</p></div></div>
    </div>
    <footer class="result-time"><span>查询完成时间：{{ turn.queryTime || '尚未完成' }}</span><span>· 图表仅用于数据展示</span></footer>
  </section>
</template>
<style scoped>
.analysis-result{min-width:0;border:1px solid #dfe7f2;border-radius:11px;overflow:hidden;background:#fff;box-shadow:0 2px 12px #20407408}
.result-toolbar{display:flex;align-items:center;justify-content:space-between;gap:10px;min-height:48px;padding:0 13px;background:#fbfcff;border-bottom:1px solid #e5eaf3}.result-tabs{display:flex;align-items:center;gap:4px;min-width:0;flex-wrap:wrap}.result-tabs button{border:0;border-bottom:2px solid transparent;padding:14px 12px 12px;background:transparent;color:#637891;font-size:12px;cursor:pointer}.result-tabs button[aria-selected=true]{color:#2162e4;border-bottom-color:#2266ea;font-weight:650}.result-tabs button:disabled{color:#b0b9c6;cursor:not-allowed}.result-actions{display:flex;align-items:center;justify-content:flex-end;gap:8px}.result-actions :deep(.el-button){height:31px;font-size:11px;padding:6px 11px}.result-actions :deep(.ask-chart-action-link){display:block;border:1px solid #d9e4f2;border-radius:6px;padding:7px 9px;font-size:11px;color:#2363dc;background:#fff;white-space:nowrap}
.result-panel{padding:16px;min-width:0;outline-offset:-2px}.result-title{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap;margin-bottom:13px}.result-title>strong{font-size:14px;font-weight:650;color:#263b5c}.result-title>span{color:#9ca9b9;font-size:11px}.result-panel :deep(.chart-canvas){height:290px!important;width:100%;max-width:100%}.result-panel :deep(.chart-kpi){padding:18px 0}.result-panel :deep(.el-table){font-size:12px}.result-panel :deep(.el-table th.el-table__cell){background:#f7faff;color:#60718c;font-weight:600}.result-panel :deep(.el-table td.el-table__cell){color:#334968}.result-panel> :deep(.el-alert){margin:0 0 10px}.result-pagination{display:flex;justify-content:space-between;align-items:center;gap:10px;padding-top:12px;font-size:10px;color:#98a5b5;flex-wrap:wrap}.result-insight{margin-top:15px;background:#f0f6ff;border-radius:8px;padding:12px 14px;display:flex;align-items:flex-start;gap:11px;color:#35588c;font-size:11px;line-height:1.8}.result-insight>strong{min-width:56px;font-weight:630;margin-top:2px}.result-insight-icon{color:#2775ef;font-size:17px;line-height:22px}.result-insight>div{display:grid;gap:3px;min-width:0}.result-insight p{overflow-wrap:anywhere}.result-time{display:flex;justify-content:space-between;gap:12px;flex-wrap:wrap;padding:10px 16px;background:#fcfdff;border-top:1px solid #edf1f7;color:#93a2b5;font-size:10px;overflow-wrap:anywhere}.result-facts{display:grid;grid-template-columns:85px minmax(0,1fr);gap:13px 20px;margin:3px 0 10px;font-size:12px;line-height:1.8}.result-facts dt{color:#91a0b4}.result-facts dd{margin:0;color:#4b617c;overflow-wrap:anywhere}.result-insight>div p::selection{background:#cfe0ff}
@media(max-width:800px){.result-toolbar{padding:0 6px}.result-tabs button{padding:13px 7px}.result-panel{padding:11px}.result-panel :deep(.chart-canvas){height:240px!important}.result-insight{flex-wrap:wrap}}
@media(max-width:540px){.result-toolbar{flex-direction:column;align-items:stretch;padding-bottom:6px}.result-actions{justify-content:flex-start;padding:0 6px}.result-title>span{display:none}.result-facts{grid-template-columns:1fr;gap:5px}}
</style>
