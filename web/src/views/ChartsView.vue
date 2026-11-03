<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { session } from '../api/auth';
import { listSubjects, type Subject } from '../api/datasources';
import { ApiError, errorMessage } from '../api/http';
import { readQuery, parseParameters, isPending, statusLabels, type QueryTask, type QueryResult } from '../api/queries';
import { chartTypes, listCharts, readChart, createPreview, previewResult, saveChart, publishRevision, refreshChart, refreshResult, listChartRecipients, updateChartSharing, deleteChart, restoreChart, transferChart, type ChartScope, type ChartVisibility, type ChartRecipient, type ChartSummary, type SavedChart, type Preview, type ChartDefinition, type ChartType } from '../api/charts';
import { chartOptions } from '../charts/render';
import ChartRenderer from '../components/ChartRenderer.vue';
const route = useRoute(), router = useRouter();
const listPage = ref(1);
const scope = computed<ChartScope>(() => ({'/charts/shared':'shared','/charts/public':'all','/charts/admin':'admin','/charts/trash':'trash'} as Record<string, ChartScope>)[route.path] ?? 'mine');
const listTitles: Record<ChartScope,string> = {mine:'我的图表',shared:'共享给我',all:'全员共享',admin:'图表管理',trash:'图表回收站'};
const visibilityLabels: Record<ChartVisibility,string> = {PRIVATE:'私有',ALL:'全员共享',SPECIFIC:'指定用户'};
const recipients = ref<ChartRecipient[]>([]), sharingOpen = ref(false), visibility = ref<ChartVisibility>('PRIVATE'), recipientIds = ref<number[]>([]), newOwner = ref<number>();
const isAdmin = computed(() => session.user?.role === 'SUPER_ADMIN');
const charts = ref<ChartSummary[]>([]), total = ref(0), subjects = ref<Subject[]>([]), chart = ref<SavedChart>(), preview = ref<Preview>(), task = ref<QueryTask>(), result = ref<QueryResult>();
const busy = ref(false), error = ref(''), editing = ref(false), name = ref(''), subject = ref(''), sql = ref(''), parameters = ref('[]'), metric = ref(''), type = ref<ChartType>('TABLE'), dimension = ref(''), measures = ref(''), timeKind = ref<'NONE'|'LAST_DAYS'|'LAST_MONTH'>('NONE'), days = ref(7), timeZone = ref('UTC'), startIndex = ref(0), endIndex = ref(1);
const isNew = computed(() => route.path === '/charts/new'), id = computed(() => typeof route.params.id === 'string' ? route.params.id : ''), editor = computed(() => isNew.value || editing.value);
const fingerprint = computed(() => JSON.stringify([subject.value, sql.value, parameters.value, metric.value, type.value, dimension.value, measures.value, timeKind.value, days.value, timeZone.value, startIndex.value, endIndex.value]));
const semanticBinding = ref<ChartDefinition['semanticBinding']>();
let generation = 0, controller: AbortController|undefined, timer: ReturnType<typeof setTimeout>|undefined, previewKey = crypto.randomUUID(), refreshKey = crypto.randomUUID();
function reset() { generation++; controller?.abort(); clearTimeout(timer); controller = new AbortController(); return { epoch: generation, signal: controller.signal }; }
function active(epoch: number) { return epoch === generation; }
function fail(cause: unknown) {
  error.value = errorMessage(cause); result.value = undefined;
  if (cause instanceof ApiError && [401,403,404].includes(cause.status ?? 0)) { chart.value = undefined; preview.value = undefined; task.value = undefined; charts.value = []; subjects.value = []; editing.value = false; sql.value = ''; parameters.value = '[]'; metric.value = ''; sharingOpen.value = false; recipients.value = []; recipientIds.value = []; newOwner.value = undefined; semanticBinding.value = undefined; }
}
function definition(): ChartDefinition {
  if (!subject.value || !sql.value.trim()) throw new Error('请选择业务主题并填写只读 SQL');
  return { subjectId: subject.value, sql: sql.value.trim(), parameters: parseParameters(parameters.value), metricDescription: metric.value, semanticBinding: semanticBinding.value, spec: { type: type.value, dimension: dimension.value.trim() || null, measures: measures.value.split(',').map(v => v.trim()).filter(Boolean) }, timeRule: timeKind.value === 'NONE' ? { kind: 'NONE' } : { kind: timeKind.value, days: timeKind.value === 'LAST_DAYS' ? days.value : null, timeZone: timeZone.value, startIndex: startIndex.value, endIndex: endIndex.value } };
}
function fill(value: ChartDefinition) { semanticBinding.value = value.semanticBinding; subject.value = value.subjectId; sql.value = value.sql; parameters.value = JSON.stringify(value.parameters); metric.value = value.metricDescription; type.value = value.spec.type; dimension.value = value.spec.dimension ?? ''; measures.value = value.spec.measures.join(','); timeKind.value = value.timeRule.kind; days.value = value.timeRule.days ?? 7; timeZone.value = value.timeRule.timeZone ?? 'UTC'; startIndex.value = value.timeRule.startIndex ?? 0; endIndex.value = value.timeRule.endIndex ?? 1; }
watch(fingerprint, () => { previewKey = crypto.randomUUID(); if (editor.value) { reset(); preview.value = undefined; task.value = undefined; result.value = undefined; busy.value = false; } }, { flush: 'sync' });
async function accept(value: QueryTask, epoch: number, signal: AbortSignal, mode: 'preview'|'refresh') {
  if (!active(epoch)) return; task.value = value;
  if (value.status === 'SUCCEEDED') {
    const data = mode === 'preview' ? await previewResult(preview.value!.id, signal) : await refreshResult(id.value, value.id, signal);
    if (!active(epoch)) return;
    chartOptions((mode === 'preview' ? preview.value!.definition : chart.value!.definition).spec, data);
    if (mode === 'refresh') {
      const revisionId = chart.value!.revisionId; const latest = await readChart(id.value, signal);
      if (active(epoch)) { chart.value = latest; if (latest.revisionId !== revisionId || latest.deleted) { result.value = undefined; task.value = undefined; error.value = '图表版本已变化，请重新刷新以读取当前版本的数据。'; } else result.value = data; }
    } else result.value = data;
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
  if (busy.value || !chart.value || chart.value.deleted) return;
  const {epoch,signal} = reset(); busy.value = true; error.value = ''; result.value = undefined; task.value = undefined;
  try { const value = await refreshChart(id.value, refreshKey, signal); if (!active(epoch)) return; refreshKey = crypto.randomUUID(); await accept(value, epoch, signal, 'refresh'); }
  catch (cause) { if (active(epoch)) { fail(cause); busy.value = false; } }
}
async function save() {
  if (busy.value || !preview.value || !result.value || !name.value.trim() || (chart.value && (!chart.value.canManage || chart.value.deleted))) return;
  const {epoch,signal} = reset(); busy.value = true; error.value = '';
  try { const value = chart.value ? await publishRevision(chart.value.id, name.value.trim(), preview.value.id, chart.value.version, signal) : await saveChart(name.value.trim(), preview.value.id, signal); if (!active(epoch)) return; preview.value = undefined; editing.value = false; await router.push(`/charts/${encodeURIComponent(value.id)}`); if (active(epoch)) await load(); }
  catch (cause) { if (active(epoch)) { fail(cause); busy.value = false; } }
}
async function edit() {
  if (!chart.value?.canManage || chart.value.deleted || busy.value) return;
  reset(); result.value = undefined; task.value = undefined; preview.value = undefined; editing.value = true; name.value = chart.value.name; fill(chart.value.definition); error.value = '';
  const {epoch,signal} = reset(); busy.value = true;
  try { const value = await listSubjects(false, signal); if (active(epoch)) subjects.value = value; }
  catch (cause) { if (active(epoch)) fail(cause); }
  finally { if (active(epoch)) busy.value = false; }
}
async function load() {
  if (isNew.value) { name.value = ''; fill({ subjectId: '', sql: '', parameters: [], metricDescription: '', spec: {type: 'TABLE', dimension: null, measures: []}, timeRule: {kind: 'NONE'} }); }
  const {epoch,signal} = reset(); busy.value = true; error.value = ''; editing.value = false; sharingOpen.value = false; recipients.value = []; recipientIds.value = []; newOwner.value = undefined; semanticBinding.value = undefined; sql.value = ''; parameters.value = '[]'; metric.value = ''; subjects.value = []; chart.value = undefined; preview.value = undefined; task.value = undefined; result.value = undefined; charts.value = [];
  try {
    if (isNew.value) { const value = await listSubjects(false, signal); if (active(epoch)) subjects.value = value; }
    else if (id.value) { const value = await readChart(id.value, signal); if (!active(epoch)) return; chart.value = value; name.value = value.name; busy.value = false; refreshKey = crypto.randomUUID(); if (!value.deleted) await refresh(); return; }
    else { const value = await listCharts(signal, listPage.value, scope.value); if (active(epoch)) { charts.value = value.items; total.value = value.total; } }
  } catch (cause) { if (active(epoch)) fail(cause); }
  finally { if (active(epoch)) busy.value = false; }
}
async function openSharing() {
  if (!chart.value?.canManage || chart.value.deleted || busy.value) return;
  const {epoch,signal} = reset(); busy.value = true; error.value = '';
  visibility.value = chart.value.visibility; recipientIds.value = [...chart.value.userIds]; newOwner.value = undefined;
  try { const value = await listChartRecipients(signal); if (active(epoch)) { recipients.value = value; sharingOpen.value = true; } }
  catch (cause) { if (active(epoch)) fail(cause); }
  finally { if (active(epoch)) busy.value = false; }
}
function closeSharing() { reset(); sharingOpen.value = false; recipients.value = []; recipientIds.value = []; newOwner.value = undefined; busy.value = false; }
async function manage(action: 'sharing'|'delete'|'restore'|'owner', item: SavedChart|ChartSummary = chart.value!) {
  if (busy.value || !item || (action !== 'restore' && !chart.value?.canManage)) return;
  if (action === 'restore' && (!item.deleted || (!isAdmin.value && item.ownerId !== session.user?.id))) return;
  if (action === 'sharing' && visibility.value === 'SPECIFIC' && !recipientIds.value.length) { error.value = '请选择至少一位共享用户'; return; }
  if (action === 'owner' && (!isAdmin.value || !newOwner.value || !window.confirm('确认转移所有权？接收者必须有权访问该图表的数据范围。'))) return;
  if (action === 'delete' && !window.confirm('确认将图表移入回收站？所有共享用户将立即失去访问权限。')) return;
  const {epoch,signal} = reset(); busy.value = true; error.value = ''; result.value = undefined; task.value = undefined;
  try {
    if (action === 'sharing') await updateChartSharing(item.id, visibility.value, visibility.value === 'SPECIFIC' ? recipientIds.value : [], item.version, signal);
    else if (action === 'delete') await deleteChart(item.id, item.version, signal);
    else if (action === 'restore') await restoreChart(item.id, item.version, signal);
    else await transferChart(item.id, newOwner.value!, item.version, signal);
    if (!active(epoch)) return;
    if (action === 'delete') await router.push('/charts/trash'); else await load();
  } catch (cause) { if (active(epoch)) fail(cause); }
  finally { if (active(epoch)) busy.value = false; }
}
watch(() => route.fullPath, () => { listPage.value = 1; void load(); }, { immediate: true });
watch(() => session.user?.id, () => { reset(); chart.value = undefined; preview.value = undefined; result.value = undefined; task.value = undefined; charts.value = []; subjects.value = []; recipients.value = []; recipientIds.value = []; sharingOpen.value = false; editing.value = false; semanticBinding.value = undefined; sql.value = ''; parameters.value = '[]'; metric.value = ''; busy.value = false; });
onBeforeUnmount(() => { generation++; controller?.abort(); clearTimeout(timer); });
</script>
<template><section class="page">
  <div class="page-heading"><div><div class="eyebrow">WORKSPACE / CHARTS</div><h1>{{ editor ? (isNew ? '新建私有图表' : '编辑图表') : chart ? chart.name : listTitles[scope] }}</h1><p class="subtitle">预览不自动保存，新建图表默认为私有。共享仅授予已发布图表的查看权，不包含自由查询或导出权。</p></div><RouterLink v-if="!id && !isNew" to="/charts/new" class="el-button el-button--primary">新建图表</RouterLink><RouterLink v-else to="/charts/mine">返回我的图表</RouterLink></div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" class="form-alert"/>
  <div v-if="!id && !isNew" class="panel">
    <nav class="query-actions" aria-label="图表范围"><RouterLink to="/charts/mine" :aria-current="scope === 'mine' ? 'page' : undefined">我的图表</RouterLink><RouterLink to="/charts/shared" :aria-current="scope === 'shared' ? 'page' : undefined">共享给我</RouterLink><RouterLink to="/charts/public" :aria-current="scope === 'all' ? 'page' : undefined">全员共享</RouterLink><RouterLink to="/charts/trash" :aria-current="scope === 'trash' ? 'page' : undefined">回收站</RouterLink><RouterLink v-if="isAdmin" to="/charts/admin">管理全部图表</RouterLink></nav>
    <p v-if="busy">正在读取…</p><p v-else-if="!charts.length">暂无图表。</p><p v-else class="scope-note">共 {{ total }} 个图表</p>
    <ul class="chart-list"><li v-for="item in charts" :key="item.id"><RouterLink :to="`/charts/${encodeURIComponent(item.id)}`">{{ item.name }}</RouterLink><span>{{ visibilityLabels[item.visibility] }} · 所有者 #{{ item.ownerId }} · 版本 {{ item.version }} · 更新于：{{ item.updatedAt }}</span><el-button v-if="item.deleted && (isAdmin || item.ownerId === session.user?.id)" :disabled="busy" @click="manage('restore', item)">恢复图表</el-button></li></ul>
    <el-pagination v-if="total > 20" v-model:current-page="listPage" :page-size="20" :total="total" layout="prev, pager, next" @current-change="load"/>
  </div>
  <form v-if="editor" class="panel chart-form" @submit.prevent="runPreview">
    <label>图表名称<input v-model="name" aria-label="图表名称" maxlength="120" :disabled="busy" required></label>
    <label>业务主题<select v-model="subject" aria-label="图表业务主题" :disabled="busy" required><option value="">请选择主题</option><option v-for="item in subjects" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
    <label>只读 SQL<textarea v-model="sql" aria-label="图表 SQL" rows="6" maxlength="16000" :disabled="busy" required/></label>
    <label>绑定参数（JSON）<textarea v-model="parameters" aria-label="图表绑定参数" rows="2" :disabled="busy"/></label>
    <label>指标口径<textarea v-model="metric" aria-label="指标口径" maxlength="2000" :disabled="busy"/></label>
    <div class="chart-form-row"><label>图表类型<select v-model="type" aria-label="图表类型" :disabled="busy"><option v-for="value in chartTypes" :key="value">{{ value }}</option></select></label><label>维度列<input v-model="dimension" aria-label="维度列" :disabled="busy" placeholder="结果列名（TABLE/KPI 可留空）"></label><label>指标列（逗号分隔）<input v-model="measures" aria-label="指标列" :disabled="busy" placeholder="结果列名"></label></div>
    <label>时间规则<select v-model="timeKind" aria-label="时间规则" :disabled="busy"><option value="NONE">固定参数</option><option value="LAST_DAYS">最近 N 天</option><option value="LAST_MONTH">上一个完整自然月</option></select></label>
    <div v-if="timeKind !== 'NONE'" class="chart-form-row"><label v-if="timeKind === 'LAST_DAYS'">天数<input v-model.number="days" type="number" min="1" max="3660" :disabled="busy"></label><label>时区<input v-model="timeZone" :disabled="busy"></label><label>起始参数索引<input v-model.number="startIndex" type="number" min="0" :disabled="busy"></label><label>结束参数索引<input v-model.number="endIndex" type="number" min="0" :disabled="busy"></label></div>
    <p class="scope-note">时间索引从 0 开始，替换 DATE 参数。最近 N 天包含业务时区的今天，结束日期为明天（SQL 使用 &lt; 上界）。所有指标均来自 SQL 结果，不在浏览器中汇总；KPI 请使用单行 SQL 聚合结果。</p>
    <div class="query-actions"><el-button native-type="submit" type="primary" :loading="busy">执行预览</el-button><el-button v-if="preview && result" :disabled="busy || !name.trim()" type="success" @click="save">{{ chart ? '确认发布新版本' : '确认保存图表' }}</el-button><el-button v-if="editing" :disabled="busy" @click="load">取消编辑</el-button></div>
  </form>
  <div v-if="chart && !editor" class="panel"><div class="panel-heading"><h2>当前版本 {{ chart.version }}</h2><div><el-button v-if="!chart.deleted" :disabled="busy" @click="refresh">重新刷新</el-button><el-button v-if="chart.canManage && !chart.deleted" :disabled="busy" @click="edit">编辑图表</el-button><el-button v-if="chart.canManage && !chart.deleted" :disabled="busy" @click="openSharing">管理共享</el-button><el-button v-if="chart.canManage && !chart.deleted" :disabled="busy" type="danger" @click="manage('delete')">移入回收站</el-button><el-button v-if="chart.canManage && chart.deleted" :disabled="busy" @click="manage('restore')">恢复图表</el-button></div></div><p>最近成功：{{ chart.lastSuccessAt ?? '尚无成功刷新' }}</p><p>{{ chart.definition.metricDescription }}</p><p class="scope-note">{{ chart.definition.timeRule.kind === 'NONE' ? '固定查询参数' : chart.definition.timeRule.kind === 'LAST_MONTH' ? `上一个完整自然月 · ${chart.definition.timeRule.timeZone}` : `最近 ${chart.definition.timeRule.days} 天 · ${chart.definition.timeRule.timeZone}` }} · {{ visibilityLabels[chart.visibility] }} · 所有者 #{{ chart.ownerId }}{{ chart.deleted ? ' · 已删除，无法刷新' : '' }}</p><p v-if="!chart.canManage" class="scope-note">只读共享图表。编辑、共享和删除仅限所有者或超管。</p></div>
  <div v-if="sharingOpen && chart?.canManage && !editor" class="panel chart-form" aria-label="图表管理">
    <h2>共享与管理</h2><label>共享范围<select v-model="visibility" aria-label="共享范围" :disabled="busy"><option value="PRIVATE">私有</option><option value="ALL">全员共享（仅有效登录用户）</option><option value="SPECIFIC">指定用户</option></select></label>
    <label v-if="visibility === 'SPECIFIC'">共享用户<select v-model="recipientIds" multiple aria-label="共享用户" :disabled="busy"><option v-for="user in recipients.filter(user => user.id !== chart!.ownerId)" :key="user.id" :value="user.id">{{ user.displayName || user.username }} ({{ user.username }})</option></select></label>
    <p class="scope-note">保存时重新校验数据授权。恢复私有会立即撤销其他用户的共享访问。</p><div><el-button :disabled="busy" @click="manage('sharing')">保存共享范围</el-button><el-button :disabled="busy" @click="closeSharing">关闭管理</el-button></div>
    <template v-if="isAdmin"><label>新所有者<select v-model="newOwner" aria-label="新所有者" :disabled="busy"><option :value="undefined">请选择用户</option><option v-for="user in recipients.filter(user => user.id !== chart!.ownerId)" :key="user.id" :value="user.id">{{ user.displayName || user.username }} ({{ user.username }})</option></select></label><el-button :disabled="busy || !newOwner" @click="manage('owner')">转移所有权</el-button></template>
  </div>
  <div v-if="task" class="panel query-status" aria-live="polite"><h2>{{ statusLabels[task.status] }}</h2><p>本次任务：{{ task.id }}</p><el-alert v-if="task.errorCode" :title="`本次刷新失败：${task.errorCode}`" type="error" :closable="false"/><p v-if="isPending(task.status)">正在自动检查状态，旧结果不会作为本次数据展示。</p><p v-if="task.status.includes('UNCONFIRMED')">数据库执行状态未确认，请联系管理员核查。</p><ChartRenderer v-if="result && (preview || chart)" :spec="(preview?.definition ?? chart!.definition).spec" :result="result"/></div>
</section></template>
