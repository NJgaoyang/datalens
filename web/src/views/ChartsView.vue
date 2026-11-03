<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { listSubjects, type Subject } from '../api/datasources';
import { ApiError, errorMessage } from '../api/http';
import { readQuery, parseParameters, isPending, statusLabels, type QueryTask, type QueryResult } from '../api/queries';
import { chartTypes, listCharts, readChart, createPreview, previewResult, saveChart, publishRevision, refreshChart, refreshResult, type ChartSummary, type SavedChart, type Preview, type ChartDefinition, type ChartType } from '../api/charts';
import { chartOptions } from '../charts/render';
import ChartRenderer from '../components/ChartRenderer.vue';
const route = useRoute(), router = useRouter();
const listPage = ref(1);
const charts = ref<ChartSummary[]>([]), total = ref(0), subjects = ref<Subject[]>([]), chart = ref<SavedChart>(), preview = ref<Preview>(), task = ref<QueryTask>(), result = ref<QueryResult>();
const busy = ref(false), error = ref(''), editing = ref(false), name = ref(''), subject = ref(''), sql = ref(''), parameters = ref('[]'), metric = ref(''), type = ref<ChartType>('TABLE'), dimension = ref(''), measures = ref(''), timeKind = ref<'NONE'|'LAST_DAYS'>('NONE'), days = ref(7), timeZone = ref('UTC'), startIndex = ref(0), endIndex = ref(1);
const isNew = computed(() => route.path === '/charts/new'), id = computed(() => typeof route.params.id === 'string' ? route.params.id : ''), editor = computed(() => isNew.value || editing.value);
const fingerprint = computed(() => JSON.stringify([subject.value, sql.value, parameters.value, metric.value, type.value, dimension.value, measures.value, timeKind.value, days.value, timeZone.value, startIndex.value, endIndex.value]));
let generation = 0, controller: AbortController|undefined, timer: ReturnType<typeof setTimeout>|undefined, previewKey = crypto.randomUUID(), refreshKey = crypto.randomUUID();
function reset() { generation++; controller?.abort(); clearTimeout(timer); controller = new AbortController(); return { epoch: generation, signal: controller.signal }; }
function active(epoch: number) { return epoch === generation; }
function fail(cause: unknown) {
  error.value = errorMessage(cause); result.value = undefined;
  if (cause instanceof ApiError && [401,403,404].includes(cause.status ?? 0)) { chart.value = undefined; preview.value = undefined; task.value = undefined; charts.value = []; subjects.value = []; editing.value = false; sql.value = ''; parameters.value = '[]'; metric.value = ''; }
}
function definition(): ChartDefinition {
  if (!subject.value || !sql.value.trim()) throw new Error('请选择业务主题并填写只读 SQL');
  return { subjectId: subject.value, sql: sql.value.trim(), parameters: parseParameters(parameters.value), metricDescription: metric.value, spec: { type: type.value, dimension: dimension.value.trim() || null, measures: measures.value.split(',').map(v => v.trim()).filter(Boolean) }, timeRule: timeKind.value === 'NONE' ? { kind: 'NONE' } : { kind: 'LAST_DAYS', days: days.value, timeZone: timeZone.value, startIndex: startIndex.value, endIndex: endIndex.value } };
}
function fill(value: ChartDefinition) { subject.value = value.subjectId; sql.value = value.sql; parameters.value = JSON.stringify(value.parameters); metric.value = value.metricDescription; type.value = value.spec.type; dimension.value = value.spec.dimension ?? ''; measures.value = value.spec.measures.join(','); timeKind.value = value.timeRule.kind; days.value = value.timeRule.days ?? 7; timeZone.value = value.timeRule.timeZone ?? 'UTC'; startIndex.value = value.timeRule.startIndex ?? 0; endIndex.value = value.timeRule.endIndex ?? 1; }
watch(fingerprint, () => { previewKey = crypto.randomUUID(); if (editor.value) { reset(); preview.value = undefined; task.value = undefined; result.value = undefined; busy.value = false; } }, { flush: 'sync' });
async function accept(value: QueryTask, epoch: number, signal: AbortSignal, mode: 'preview'|'refresh') {
  if (!active(epoch)) return; task.value = value;
  if (value.status === 'SUCCEEDED') {
    const data = mode === 'preview' ? await previewResult(preview.value!.id, signal) : await refreshResult(id.value, value.id, signal);
    if (!active(epoch)) return;
    chartOptions((mode === 'preview' ? preview.value!.definition : chart.value!.definition).spec, data); result.value = data;
    if (mode === 'refresh') {
      const revisionId = chart.value!.revisionId; const latest = await readChart(id.value, signal);
      if (active(epoch)) { chart.value = latest; if (latest.revisionId !== revisionId) { result.value = undefined; task.value = undefined; error.value = '图表版本已变化，请重新刷新以读取当前版本的数据。'; } }
    }
  } else if (isPending(value.status)) {
    timer = setTimeout(async () => {
      if (!active(epoch)) return;
      try { await accept(await readQuery(value.id, signal), epoch, signal, mode); }
      catch (cause) { if (active(epoch)) { fail(cause); busy.value = false; } }
    }, 700);
  }
  if (active(epoch)) busy.value = isPending(value.status);
}
async function runPreview() {
  if (busy.value) return;
  let input: ChartDefinition; try { input = definition(); } catch (cause) { fail(cause); return; }
  const {epoch,signal} = reset(); busy.value = true; error.value = ''; result.value = undefined; task.value = undefined; preview.value = undefined;
  try { const value = await createPreview(input, previewKey, signal); if (!active(epoch)) return; preview.value = value; await accept(await readQuery(value.taskId, signal), epoch, signal, 'preview'); }
  catch (cause) { if (active(epoch)) { fail(cause); busy.value = false; } }
}
async function refresh() {
  if (busy.value || !chart.value) return;
  const {epoch,signal} = reset(); busy.value = true; error.value = ''; result.value = undefined; task.value = undefined;
  try { const value = await refreshChart(id.value, refreshKey, signal); if (!active(epoch)) return; refreshKey = crypto.randomUUID(); await accept(value, epoch, signal, 'refresh'); }
  catch (cause) { if (active(epoch)) { fail(cause); busy.value = false; } }
}
async function save() {
  if (busy.value || !preview.value || !result.value || !name.value.trim()) return;
  const {epoch,signal} = reset(); busy.value = true; error.value = '';
  try { const value = chart.value ? await publishRevision(chart.value.id, name.value.trim(), preview.value.id, chart.value.version, signal) : await saveChart(name.value.trim(), preview.value.id, signal); if (!active(epoch)) return; preview.value = undefined; editing.value = false; await router.push(`/charts/${encodeURIComponent(value.id)}`); if (active(epoch)) await load(); }
  catch (cause) { if (active(epoch)) { fail(cause); busy.value = false; } }
}
async function edit() {
  if (!chart.value || busy.value) return;
  reset(); result.value = undefined; task.value = undefined; preview.value = undefined; editing.value = true; name.value = chart.value.name; fill(chart.value.definition); error.value = '';
  const {epoch,signal} = reset(); busy.value = true;
  try { const value = await listSubjects(false, signal); if (active(epoch)) subjects.value = value; }
  catch (cause) { if (active(epoch)) fail(cause); }
  finally { if (active(epoch)) busy.value = false; }
}
async function load() {
  if (isNew.value) { name.value = ''; fill({ subjectId: '', sql: '', parameters: [], metricDescription: '', spec: {type: 'TABLE', dimension: null, measures: []}, timeRule: {kind: 'NONE'} }); }
  const {epoch,signal} = reset(); busy.value = true; error.value = ''; editing.value = false; chart.value = undefined; preview.value = undefined; task.value = undefined; result.value = undefined; charts.value = [];
  try {
    if (isNew.value) { const value = await listSubjects(false, signal); if (active(epoch)) subjects.value = value; }
    else if (id.value) { const value = await readChart(id.value, signal); if (!active(epoch)) return; chart.value = value; name.value = value.name; busy.value = false; refreshKey = crypto.randomUUID(); await refresh(); return; }
    else { const value = await listCharts(signal, listPage.value); if (active(epoch)) { charts.value = value.items; total.value = value.total; } }
  } catch (cause) { if (active(epoch)) fail(cause); }
  finally { if (active(epoch)) busy.value = false; }
}
watch(() => route.fullPath, load, { immediate: true });
onBeforeUnmount(() => { generation++; controller?.abort(); clearTimeout(timer); });
</script>
<template><section class="page">
  <div class="page-heading"><div><div class="eyebrow">WORKSPACE / PRIVATE CHARTS</div><h1>{{ editor ? (isNew ? '新建私有图表' : '编辑图表') : chart ? chart.name : '我的图表' }}</h1><p class="subtitle">预览不自动保存。保存的图表仅本人可见，每次打开都会重新执行授权查询。</p></div><RouterLink v-if="!id && !isNew" to="/charts/new" class="el-button el-button--primary">新建图表</RouterLink><RouterLink v-else to="/charts/mine">返回我的图表</RouterLink></div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" class="form-alert"/>
  <div v-if="!id && !isNew" class="panel"><p v-if="busy">正在读取…</p><p v-else-if="!charts.length">尚未保存图表。先预览查询，再确认保存。</p><p v-else class="scope-note">共 {{ total }} 个私有图表</p><ul class="chart-list"><li v-for="item in charts" :key="item.id"><RouterLink :to="`/charts/${encodeURIComponent(item.id)}`">{{ item.name }}</RouterLink><span>版本 {{ item.version }} · 更新于：{{ item.updatedAt }}</span></li></ul><el-pagination v-if="total > 20" v-model:current-page="listPage" :page-size="20" :total="total" layout="prev, pager, next" @current-change="load"/></div>
  <form v-if="editor" class="panel chart-form" @submit.prevent="runPreview">
    <label>图表名称<input v-model="name" aria-label="图表名称" maxlength="120" :disabled="busy" required></label>
    <label>业务主题<select v-model="subject" aria-label="图表业务主题" :disabled="busy" required><option value="">请选择主题</option><option v-for="item in subjects" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
    <label>只读 SQL<textarea v-model="sql" aria-label="图表 SQL" rows="6" maxlength="16000" :disabled="busy" required/></label>
    <label>绑定参数（JSON）<textarea v-model="parameters" aria-label="图表绑定参数" rows="2" :disabled="busy"/></label>
    <label>指标口径<textarea v-model="metric" aria-label="指标口径" maxlength="2000" :disabled="busy"/></label>
    <div class="chart-form-row"><label>图表类型<select v-model="type" aria-label="图表类型" :disabled="busy"><option v-for="value in chartTypes" :key="value">{{ value }}</option></select></label><label>维度列<input v-model="dimension" aria-label="维度列" :disabled="busy" placeholder="结果列名（TABLE/KPI 可留空）"></label><label>指标列（逗号分隔）<input v-model="measures" aria-label="指标列" :disabled="busy" placeholder="结果列名"></label></div>
    <label>时间规则<select v-model="timeKind" aria-label="时间规则" :disabled="busy"><option value="NONE">固定参数</option><option value="LAST_DAYS">最近 N 天</option></select></label>
    <div v-if="timeKind === 'LAST_DAYS'" class="chart-form-row"><label>天数<input v-model.number="days" type="number" min="1" max="3660" :disabled="busy"></label><label>时区<input v-model="timeZone" :disabled="busy"></label><label>起始参数索引<input v-model.number="startIndex" type="number" min="0" :disabled="busy"></label><label>结束参数索引<input v-model.number="endIndex" type="number" min="0" :disabled="busy"></label></div>
    <p class="scope-note">时间索引从 0 开始，替换 DATE 参数。最近 N 天包含业务时区的今天，结束日期为明天（SQL 使用 &lt; 上界）。所有指标均来自 SQL 结果，不在浏览器中汇总；KPI 请使用单行 SQL 聚合结果。</p>
    <div class="query-actions"><el-button native-type="submit" type="primary" :loading="busy">执行预览</el-button><el-button v-if="preview && result" :disabled="busy || !name.trim()" type="success" @click="save">{{ chart ? '确认发布新版本' : '确认保存图表' }}</el-button><el-button v-if="editing" :disabled="busy" @click="load">取消编辑</el-button></div>
  </form>
  <div v-if="chart && !editor" class="panel"><div class="panel-heading"><h2>当前版本 {{ chart.version }}</h2><div><el-button :disabled="busy" @click="refresh">重新刷新</el-button><el-button :disabled="busy" @click="edit">编辑图表</el-button></div></div><p>最近成功：{{ chart.lastSuccessAt ?? '尚无成功刷新' }}</p><p>{{ chart.definition.metricDescription }}</p><p class="scope-note">{{ chart.definition.timeRule.kind === 'NONE' ? '固定查询参数' : `最近 ${chart.definition.timeRule.days} 天 · ${chart.definition.timeRule.timeZone}` }} · 私有图表</p></div>
  <div v-if="task" class="panel query-status" aria-live="polite"><h2>{{ statusLabels[task.status] }}</h2><p>本次任务：{{ task.id }}</p><el-alert v-if="task.errorCode" :title="`本次刷新失败：${task.errorCode}`" type="error" :closable="false"/><p v-if="isPending(task.status)">正在自动检查状态，旧结果不会作为本次数据展示。</p><p v-if="task.status.includes('UNCONFIRMED')">数据库执行状态未确认，请联系管理员核查。</p><ChartRenderer v-if="result && (preview || chart)" :spec="(preview?.definition ?? chart!.definition).spec" :result="result"/></div>
</section></template>
