<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { listSubjects, metadata, fieldKey, type Subject, type Column } from '../api/datasources';
import { ApiError, errorMessage } from '../api/http';
import { submitQuery, readQuery, readResult, cancelQuery, parseParameters, statusLabels, isPending, isUnconfirmed, type QueryInput, type QueryTask, type QueryResult } from '../api/queries';
const subjects = ref<Subject[]>([]), fields = ref<Column[]>([]), selected = ref(''), sql = ref(''), parameters = ref('[]');
const loading = ref(false), busy = ref(false), error = ref(''), uncertain = ref(false), query = ref<QueryTask>(), result = ref<QueryResult>(), page = ref(1);
const pendingInput = ref<QueryInput>();
const locked = computed(() => busy.value || uncertain.value || !!query.value && isPending(query.value.status));
const rows = computed(() => result.value?.rows.slice((page.value - 1) * 50, page.value * 50) ?? []);
let generation = 0, request: AbortController|undefined, timer: ReturnType<typeof setTimeout>|undefined;
function resetRequest() { generation++; request?.abort(); clearTimeout(timer); request = new AbortController(); return { epoch: generation, signal: request.signal }; }
function current(epoch: number) { return epoch === generation; }
function fail(cause: unknown) {
  result.value = undefined;
  error.value = errorMessage(cause);
  if (cause instanceof ApiError && [401, 403, 404].includes(cause.status ?? 0)) { query.value = undefined; fields.value = []; uncertain.value = false; pendingInput.value = undefined; }
}
async function loadSubjects() {
  const { epoch, signal } = resetRequest(); loading.value = true; error.value = ''; fields.value = []; result.value = undefined; selected.value = ''; query.value = undefined;
  try { const value = await listSubjects(false, signal); if (current(epoch)) subjects.value = value; }
  catch (cause) { if (current(epoch)) { subjects.value = []; fail(cause); } }
  finally { if (current(epoch)) loading.value = false; }
}
async function selectSubject() {
  const { epoch, signal } = resetRequest(); fields.value = []; query.value = undefined; result.value = undefined; error.value = ''; pendingInput.value = undefined; loading.value = true;
  try { const value = await metadata(selected.value, false, signal); if (current(epoch)) fields.value = value; }
  catch (cause) { if (current(epoch)) fail(cause); }
  finally { if (current(epoch)) loading.value = false; }
}
function schedule(epoch: number) { if (current(epoch) && query.value && isPending(query.value.status)) timer = setTimeout(() => { void refresh(); }, 1000); }
async function accept(value: QueryTask, epoch: number, signal: AbortSignal) {
  if (!current(epoch)) return;
  query.value = value; uncertain.value = false; page.value = 1;
  if (value.status === 'SUCCEEDED') {
    const data = await readResult(value.id, signal);
    if (current(epoch)) result.value = data;
  }
  schedule(epoch);
}
async function execute(retry = false) {
  if (busy.value || (!retry && locked.value)) return;
  error.value = ''; result.value = undefined;
  if (!retry) {
    try {
      if (!selected.value || !sql.value.trim() || sql.value.length > 16000) throw new Error('请选择主题并填写 SQL（最多 16000 字符）');
      pendingInput.value = { subjectId: selected.value, sql: sql.value.trim(), parameters: parseParameters(parameters.value), idempotencyKey: crypto.randomUUID() };
    } catch (cause) { fail(cause); return; }
  }
  if (!pendingInput.value) return;
  const { epoch, signal } = resetRequest(); busy.value = true; query.value = undefined;
  try { await accept(await submitQuery(pendingInput.value, signal), epoch, signal); }
  catch (cause) { if (current(epoch)) { fail(cause); uncertain.value = !!pendingInput.value && (!(cause instanceof ApiError) || cause.code === 'NETWORK_ERROR' || cause.code === 'INVALID_RESPONSE' || (cause.status ?? 0) >= 500); } }
  finally { if (current(epoch)) busy.value = false; }
}
async function refresh(cancel = false) {
  if (busy.value || !query.value) return;
  const id = query.value.id, { epoch, signal } = resetRequest(); busy.value = true; error.value = ''; result.value = undefined;
  try { await accept(await (cancel ? cancelQuery(id, signal) : readQuery(id, signal)), epoch, signal); }
  catch (cause) { if (current(epoch)) { fail(cause); if (cancel && query.value) error.value += '。取消是否生效尚未确认，请重新检查状态。'; } }
  finally { if (current(epoch)) busy.value = false; }
}
onMounted(loadSubjects);
onUnmounted(() => { generation++; clearTimeout(timer); request?.abort(); });
</script>
<template><section class="page query-page">
  <div class="page-heading"><div><div class="eyebrow">WORKSPACE / CONTROLLED QUERY</div><h1>安全 SQL 查询</h1><p class="subtitle">在业务主题授权范围内执行只读查询。权限、字段、函数和参数均由服务端再次校验。</p></div></div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" class="form-alert"/>
  <el-alert v-if="uncertain" title="提交结果未知。请使用相同请求重试以确认原任务，不会主动创建新的重复任务。" type="warning" :closable="false" class="form-alert"/>
  <div class="query-layout"><div class="panel">
    <el-form label-position="top" @submit.prevent="execute()">
      <el-form-item label="业务主题"><el-select v-model="selected" aria-label="查询业务主题" :disabled="locked || loading" placeholder="选择获准访问的主题" @change="selectSubject"><el-option v-for="subject in subjects" :key="subject.id" :label="subject.name" :value="subject.id"/></el-select></el-form-item>
      <p v-if="!subjects.length && !loading" class="scope-note">暂无可访问主题，请联系管理员授权。</p>
      <el-form-item label="只读 SQL"><el-input v-model="sql" aria-label="只读 SQL" type="textarea" :rows="9" :disabled="locked" maxlength="16000" placeholder="SELECT orders.total FROM orders WHERE orders.id = ?"/></el-form-item>
      <el-form-item label="绑定参数（JSON 数组）"><el-input v-model="parameters" aria-label="绑定参数" type="textarea" :rows="3" :disabled="locked" placeholder='[{"type":"INTEGER","value":"42"}]'/></el-form-item>
      <p class="scope-note">按 ? 顺序绑定参数；整数和金额使用字符串以保留精度。必须显式列出字段，不支持 SELECT *。最多返回 1000 行，数据库执行限时 10 秒。</p>
      <div class="query-actions"><el-button v-if="uncertain" type="primary" :loading="busy" @click="execute(true)">重试确认原任务</el-button><el-button v-else type="primary" native-type="submit" :loading="busy" :disabled="locked || loading || !selected">执行查询</el-button><el-button :disabled="locked || loading" @click="loadSubjects">刷新主题</el-button></div>
    </el-form>
  </div><aside class="panel"><h2>可用字段</h2><p class="scope-note">只展示当前主题获准读取的字段。</p><ul class="query-fields"><li v-for="field in fields" :key="fieldKey(field)"><strong>{{ fieldKey(field) }}</strong><span>{{ field.typeName }}</span></li></ul><p v-if="!fields.length">{{ loading ? '读取中…' : '选择主题以查看字段' }}</p></aside></div>
  <div v-if="query" class="panel query-status" aria-live="polite">
    <div class="panel-heading"><h2>{{ statusLabels[query.status] }}</h2><div><el-button :disabled="busy" @click="refresh()">重新检查状态</el-button><el-button v-if="['QUEUED','RUNNING'].includes(query.status)" :disabled="busy" type="warning" @click="refresh(true)">取消查询</el-button></div></div>
    <p>任务 {{ query.id }}<template v-if="query.durationMs !== null"> · 耗时 {{ query.durationMs }} ms</template></p>
    <el-alert v-if="isUnconfirmed(query.status)" title="数据库端是否停止尚未确认，请联系管理员核查。此状态不代表查询已取消。" type="warning" :closable="false" class="form-alert"/>
    <el-alert v-if="query.errorCode" :title="`执行信息：${query.errorCode}`" type="error" :closable="false" class="form-alert"/>
    <p v-if="isPending(query.status)" class="scope-note">自动检查任务状态。离开页面会停止轮询，不会自动取消数据库查询。</p>
    <template v-if="result">
      <el-alert v-if="result.truncated" title="结果已截断：仅展示部分行，不能据此重新计算完整数据的总额。" type="warning" :closable="false" class="form-alert"/>
      <p class="scope-note">返回 {{ result.rows.length }} 行 · 上限 {{ result.rowLimit }} 行 · 每页 50 行；大整数和金额按原始文本展示。</p>
      <el-table :data="rows" max-height="480" empty-text="查询成功，没有匹配数据"><el-table-column v-for="(column, index) in result.columns" :key="index" :label="`${column.name} · ${column.jdbcType}`" min-width="160"><template #default="{row}"><span v-if="row[index] === null" class="null-value">NULL</span><span v-else>{{ row[index] }}</span></template></el-table-column></el-table>
      <el-pagination v-model:current-page="page" :page-size="50" :total="result.rows.length" layout="prev, pager, next" class="pagination"/>
    </template>
  </div>
</section></template>
