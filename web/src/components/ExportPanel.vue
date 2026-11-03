<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { session } from '../api/auth';
import { type SavedChart, type ChartRecipient, listChartRecipients } from '../api/charts';
import { ApiError, errorMessage } from '../api/http';
import { createExport, readExport, exportPermission, downloadExport, setExportGrant, listExportGrants, exportPending, exportLabels, type ExportJob, type ExportGrant } from '../api/exports';
const props = defineProps<{chart: SavedChart; taskId?: string; truncated: boolean}>();
const admin = computed(() => session.user?.role === 'SUPER_ADMIN');
const allowed = ref(false), checking = ref(false), busy = ref(false), error = ref(''), notice = ref('');
const job = ref<ExportJob>(), recipients = ref<ChartRecipient[]>([]), grants = ref<ExportGrant[]>([]), selectedUser = ref<number>(), manageOpen = ref(false);
let epoch = 0, controller: AbortController|undefined, timer: ReturnType<typeof setTimeout>|undefined, key = crypto.randomUUID();
function reset() { epoch++; controller?.abort(); clearTimeout(timer); controller = new AbortController(); return {generation:epoch,signal:controller.signal}; }
function active(generation: number) { return epoch === generation && !!session.user; }
function failed(cause: unknown) { error.value = errorMessage(cause); job.value = undefined; allowed.value = false; }
async function check() {
  const {generation,signal} = reset(); checking.value = true; allowed.value = false; error.value = ''; job.value = undefined; busy.value = false; manageOpen.value = false; recipients.value = []; grants.value = []; selectedUser.value = undefined;
  try { const value = await exportPermission(props.chart.id,signal); if (active(generation)) allowed.value = value.allowed; }
  catch (cause) { if (active(generation)) failed(cause); }
  finally { if (active(generation)) checking.value = false; }
}
async function accept(value: ExportJob, generation: number, signal: AbortSignal) {
  if (!active(generation)) return;
  job.value = value; busy.value = exportPending(value.status);
  if (exportPending(value.status)) timer = setTimeout(async () => {
    try { await accept(await readExport(value.id,signal),generation,signal); }
    catch (cause) { if (active(generation)) { failed(cause); busy.value = false; } }
  },800);
}
async function start() {
  if (!props.taskId || props.truncated || !allowed.value || busy.value || checking.value) return;
  if (job.value && !exportPending(job.value.status)) key = crypto.randomUUID();
  const {generation,signal} = reset(); busy.value = true; error.value = ''; notice.value = '';
  try { await accept(await createExport(props.chart.id,props.taskId,key,signal),generation,signal); }
  catch (cause) { if (active(generation)) { error.value = errorMessage(cause); job.value = undefined; busy.value = false; if (cause instanceof ApiError && [401,403,404].includes(cause.status ?? 0)) allowed.value = false; } }
}
async function download() {
  if (job.value?.status !== 'SUCCEEDED' || busy.value) return;
  const {generation,signal} = reset(); busy.value = true; error.value = '';
  try {
    const blob = await downloadExport(job.value.id,signal); if (!active(generation)) return;
    const url = URL.createObjectURL(blob), anchor = document.createElement('a'); anchor.href = url; anchor.download = 'datalens-chart.csv';
    document.body.appendChild(anchor); anchor.click(); anchor.remove(); setTimeout(() => URL.revokeObjectURL(url),1000);
    notice.value = '文件已交给浏览器下载。请妥善保管，已下载的副本无法通过撤权收回。';
  } catch (cause) { if (active(generation)) { key = crypto.randomUUID(); failed(cause); } }
  finally { if (active(generation)) busy.value = false; }
}
async function openGrants() {
  if (!admin.value || busy.value) return;
  const {generation,signal} = reset(); busy.value = true; error.value = ''; notice.value = '';
  try {
    const [users,existing] = await Promise.all([listChartRecipients(signal),listExportGrants(props.chart.id,signal)]);
    if (active(generation)) { recipients.value = users; grants.value = existing; manageOpen.value = true; }
  } catch (cause) { if (active(generation)) failed(cause); }
  finally { if (active(generation)) busy.value = false; }
}
function closeGrants() { reset(); manageOpen.value = false; recipients.value = []; grants.value = []; selectedUser.value = undefined; busy.value = false; }
async function updateGrant(enabled: boolean) {
  if (!admin.value || !selectedUser.value || busy.value) return;
  const {generation,signal} = reset(); busy.value = true; error.value = ''; notice.value = '';
  try {
    await setExportGrant(props.chart.id,selectedUser.value,props.chart.version,enabled,signal); if (!active(generation)) return;
    job.value = undefined; key = crypto.randomUUID();
    const [existing,permission] = await Promise.all([listExportGrants(props.chart.id,signal),exportPermission(props.chart.id,signal)]);
    if (active(generation)) { grants.value = existing; allowed.value = permission.allowed; notice.value = enabled ? '导出授权已更新；用户仍须具有图表查看权。' : '导出授权已撤销，旧文件不能再下载。'; }
  } catch (cause) { if (active(generation)) failed(cause); }
  finally { if (active(generation)) busy.value = false; }
}
watch(() => [props.chart.id,props.chart.version,props.taskId,session.user?.id], () => { key = crypto.randomUUID(); void check(); }, {immediate:true});
onBeforeUnmount(reset);
</script>
<template>
  <section class="panel" aria-label="受控导出">
    <div class="panel-heading"><h2>受控导出</h2><el-button v-if="admin" :disabled="busy || checking" @click="openGrants">管理导出授权</el-button></div>
    <p class="scope-note">仅导出本次已完成的图表查询，不会重新执行 SQL。文件 15 分钟后失效，每次下载重新检查权限。CSV 文本已作公式防护，请勿移除保护后执行其中内容。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false"/>
    <p v-if="checking">正在检查独立导出权限…</p><p v-else-if="!allowed">查看、拥有或管理图表不自动获得导出权，请联系管理员单独授权。</p>
    <p v-if="truncated">当前结果已截断，不能导出为完整数据。请缩小查询范围后重新刷新。</p>
    <p v-else-if="!taskId">等待本次图表刷新成功后才能创建导出。</p>
    <div class="query-actions"><el-button :disabled="!allowed || !taskId || truncated || checking || busy" @click="start">{{ job && ['FAILED','INTERRUPTED'].includes(job.status) ? '重试导出' : job?.status === 'SUCCEEDED' ? '重新生成 CSV' : '创建 CSV 导出' }}</el-button><el-button v-if="!allowed && !checking" :disabled="busy" @click="check">重新检查导出权限</el-button><el-button v-if="job?.status === 'SUCCEEDED'" :loading="busy" @click="download">下载 CSV</el-button></div>
    <div v-if="job" aria-live="polite"><p>{{ exportLabels[job.status] }} · 有效期至 {{ job.expiresAt }}</p><p v-if="job.errorCode">失败原因：{{ job.errorCode }}</p><p v-if="exportPending(job.status)">正在自动检查导出状态。</p></div>
    <p v-if="notice" role="status">{{ notice }}</p>
    <div v-if="manageOpen && admin" class="chart-form" aria-label="导出授权管理">
      <p>授权只允许导出当前有权查看的图表，不授予查看权或自由查询权。新版本、撤权等安全变更会使旧导出失效。</p>
      <p>当前已授权用户：{{ grants.filter(g => g.enabled).map(g => recipients.find(u => u.id === g.userId)?.username ?? `#${g.userId}`).join('、') || '无' }}</p>
      <label>导出用户<select v-model="selectedUser" aria-label="导出用户" :disabled="busy"><option :value="undefined">请选择用户</option><option v-for="user in recipients" :key="user.id" :value="user.id">{{ user.displayName || user.username }} (#{{ user.id }})</option></select></label>
      <div class="query-actions"><el-button :disabled="busy || !selectedUser" @click="updateGrant(true)">授予导出权</el-button><el-button :disabled="busy || !selectedUser" type="danger" @click="updateGrant(false)">撤销导出权</el-button><el-button :disabled="busy" @click="closeGrants">关闭导出管理</el-button></div>
    </div>
  </section>
</template>
