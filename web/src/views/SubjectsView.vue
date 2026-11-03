<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { session } from '../api/auth';
import { errorMessage } from '../api/http';
import { listSources, listSubjects, metadata, saveSubject, fieldKey, parseFields, parseUserIds, type Source, type Subject, type Column } from '../api/datasources';
const admin = computed(() => session.user?.role === 'SUPER_ADMIN');
const subjects = ref<Subject[]>([]), sources = ref<Source[]>([]), columns = ref<Column[]>([]), snapshot = ref<Column[]>([]);
const loading = ref(false), busy = ref(false), dialog = ref(false), error = ref(''), selected = ref(''), editing = ref<string>();
const form = reactive({ name: '', sourceId: '', fields: '', deniedFields: '', userIds: '', shareScope: 'NONE' as 'NONE'|'AUTHORIZED_USERS'|'ALL_AUTHENTICATED' });
let active: AbortController|undefined; let snapshotRequest: AbortController|undefined; let alive = true;
async function reload() {
  active?.abort(); const controller = new AbortController(); active = controller; loading.value = true; error.value = ''; columns.value = []; selected.value = '';
  try {
    const [rows, connections] = await Promise.all([listSubjects(admin.value, controller.signal), admin.value ? listSources(controller.signal) : Promise.resolve([])]);
    if (!controller.signal.aborted) { subjects.value = rows; sources.value = connections; }
  } catch (cause) { if (!controller.signal.aborted) { error.value = errorMessage(cause); subjects.value = []; sources.value = []; } }
  finally { if (!controller.signal.aborted) loading.value = false; }
}
async function readSnapshot() {
  snapshotRequest?.abort(); snapshot.value = []; if (!form.sourceId) return;
  const controller = new AbortController(); snapshotRequest = controller;
  try { const rows = await metadata(form.sourceId, true, controller.signal); if (!controller.signal.aborted) snapshot.value = rows; }
  catch (cause) { if (!controller.signal.aborted) error.value = errorMessage(cause); }
}
function open(subject?: Subject) {
  editing.value = subject?.id; snapshot.value = []; error.value = '';
  Object.assign(form, { name: subject?.name ?? '', sourceId: subject?.sourceId ?? '', fields: subject?.fields.map(fieldKey).join('\n') ?? '', deniedFields: subject?.deniedFields.map(fieldKey).join('\n') ?? '', userIds: subject?.userIds.join(', ') ?? '', shareScope: subject?.shareScope ?? 'NONE' });
  dialog.value = true; void readSnapshot();
}
function sourceChanged() { form.fields = ''; form.deniedFields = ''; error.value = ''; void readSnapshot(); }
async function save() {
  if (busy.value) return; busy.value = true; error.value = '';
  try {
    if (!form.name.trim() || !form.sourceId) throw new Error('请填写主题名称并选择数据源');
    const fields = parseFields(form.fields), deniedFields = parseFields(form.deniedFields), userIds = parseUserIds(form.userIds);
    await saveSubject({ name: form.name.trim(), sourceId: form.sourceId, fields, deniedFields, userIds, shareScope: form.shareScope }, editing.value);
    if (alive) { dialog.value = false; ElMessage.success('主题权限已保存'); await reload(); }
  } catch (cause) { if (alive) error.value = errorMessage(cause); }
  finally { busy.value = false; }
}
async function inspect(subject: Subject) {
  if (busy.value) return; busy.value = true; error.value = ''; selected.value = ''; columns.value = [];
  try { const rows = await metadata(subject.id); if (alive) { columns.value = rows; selected.value = subject.name; } }
  catch (cause) { if (alive) error.value = errorMessage(cause); }
  finally { busy.value = false; }
}
onMounted(reload); onUnmounted(() => { alive = false; active?.abort(); snapshotRequest?.abort(); });
</script>
<template><section class="page">
  <div class="page-heading"><div><div class="eyebrow">WORKSPACE / BUSINESS SUBJECTS</div><h1>业务主题</h1><p class="subtitle">{{ admin ? '明确配置可用字段、禁止字段、授权用户与结果共享范围。禁止字段优先。' : '仅展示当前账号获准访问的主题和字段。权限由服务端实时验证。' }}</p></div><el-button v-if="admin" type="primary" :disabled="busy || loading" @click="open()">新增主题</el-button></div>
  <el-alert v-if="error && !dialog" :title="error" type="error" :closable="false" class="form-alert"/>
  <div class="panel"><div class="panel-heading"><h2>{{ admin ? '主题权限' : '我可访问的主题' }}</h2><el-button :loading="loading" :disabled="busy" @click="reload">刷新列表</el-button></div>
    <el-table v-loading="loading" :data="subjects" row-key="id" empty-text="暂无可访问主题">
      <el-table-column prop="name" label="主题" min-width="160"/><el-table-column label="字段数" width="100"><template #default="{row}">{{ row.fields.length }}</template></el-table-column>
      <el-table-column label="结果共享范围" min-width="150"><template #default="{row}">{{ row.shareScope === 'NONE' ? '不可共享' : '仅授权用户' }}</template></el-table-column>
      <el-table-column label="操作" min-width="200"><template #default="{row}"><el-button :disabled="busy || loading" size="small" @click="inspect(row)">查看可用字段</el-button><el-button v-if="admin" :disabled="busy || loading" size="small" @click="open(row)">编辑权限</el-button></template></el-table-column>
    </el-table>
  </div>
  <div v-if="selected" class="panel metadata-panel"><h2>{{ selected }} · 可用字段</h2><el-table :data="columns" empty-text="此主题暂无可用字段"><el-table-column prop="tableName" label="表"/><el-table-column prop="columnName" label="字段"/><el-table-column prop="typeName" label="类型"/><el-table-column label="允许空值"><template #default="{row}">{{ row.nullable ? '是' : '否' }}</template></el-table-column></el-table></div>
  <el-dialog v-if="admin" v-model="dialog" :title="editing ? '编辑主题权限' : '新增主题'" width="min(700px, 94vw)" :close-on-click-modal="!busy" :close-on-press-escape="!busy" :show-close="!busy" @closed="snapshotRequest?.abort()">
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="form-alert"/>
    <el-form label-position="top" @submit.prevent="save"><fieldset :disabled="busy" class="source-form">
      <el-form-item label="主题名称"><el-input v-model="form.name" aria-label="主题名称" maxlength="120"/></el-form-item>
      <el-form-item label="数据源"><el-select v-model="form.sourceId" aria-label="数据源" :disabled="busy" @change="sourceChanged"><el-option v-for="source in sources" :key="source.id" :label="`${source.name}${source.enabled ? '' : '（已禁用）'}`" :value="source.id" :disabled="!source.enabled"/></el-select></el-form-item>
      <div class="snapshot-preview"><strong>最新采集快照</strong><p v-if="!snapshot.length">暂无可选字段，请先成功采集数据源。</p><ul v-else><li v-for="column in snapshot" :key="fieldKey(column)">{{ fieldKey(column) }} · {{ column.typeName }}</li></ul></div>
      <el-form-item label="允许字段（每行 table.column）"><el-input v-model="form.fields" aria-label="允许字段" type="textarea" :rows="4"/></el-form-item>
      <el-form-item label="禁止字段（优先于允许字段）"><el-input v-model="form.deniedFields" aria-label="禁止字段" type="textarea" :rows="3"/></el-form-item>
      <el-form-item label="授权用户 ID（逗号或换行分隔）"><el-input v-model="form.userIds" aria-label="授权用户 ID" placeholder="例如：2, 3"/><p class="scope-note">请在用户管理确认账号 ID。空列表不授权普通用户。</p></el-form-item>
      <el-form-item label="结果共享范围"><el-select v-model="form.shareScope" aria-label="结果共享范围" :disabled="busy"><el-option label="不可共享" value="NONE"/><el-option label="仅授权用户" value="AUTHORIZED_USERS"/><el-option label="允许共享给全体有效用户" value="ALL_AUTHENTICATED"/></el-select></el-form-item>
      <p class="scope-note">允许字段必须存在于最新成功采集快照，禁止字段始终优先。此处仅定义访问范围，不执行查询或创建共享资产。</p>
      <div class="dialog-actions"><el-button :disabled="busy" @click="dialog = false">取消</el-button><el-button type="primary" native-type="submit" :loading="busy">保存主题</el-button></div>
    </fieldset></el-form>
  </el-dialog>
</section></template>
