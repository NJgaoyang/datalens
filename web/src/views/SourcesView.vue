<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { errorMessage } from '../api/http';
import { listSources, saveSource, sourceAction, metadata, type Source, type SourceInput, type Column } from '../api/datasources';
const sources = ref<Source[]>([]), columns = ref<Column[]>([]);
const loading = ref(false), busy = ref(false), dialog = ref(false), error = ref(''), selected = ref(''), editing = ref<string>();
const tableText = ref('');
const defaults = (): SourceInput => ({ name: '', host: '', port: 9030, catalog: 'default_catalog', database: '', username: '', password: '', enabled: true, tables: [] });
const form = reactive(defaults()); let active: AbortController|undefined; let alive = true;
async function reload() {
  active?.abort(); const controller = new AbortController(); active = controller; loading.value = true; error.value = '';
  columns.value = []; selected.value = '';
  try { const rows = await listSources(controller.signal); if (!controller.signal.aborted) sources.value = rows; }
  catch (cause) { if (!controller.signal.aborted) { sources.value = []; error.value = errorMessage(cause); } }
  finally { if (!controller.signal.aborted) loading.value = false; }
}
function open(source?: Source) { editing.value = source?.id; Object.assign(form, defaults(), source ? { name: source.name, host: source.host, port: source.port, catalog: source.catalog, database: source.database, username: source.username, enabled: source.enabled } : {}); tableText.value = source?.tables.join('\n') ?? ''; error.value = ''; dialog.value = true; }
async function save() {
  if (busy.value) return;
  error.value = ''; busy.value = true;
  try {
    if (!editing.value && !form.password) throw new Error('新数据源必须填写密码');
    await saveSource({ ...form, tables: tableText.value.split(/[\s,]+/).filter(Boolean) }, editing.value);
    if (alive) { dialog.value = false; ElMessage.success('数据源已保存'); await reload(); }
  } catch (cause) { if (alive) error.value = errorMessage(cause); }
  finally { form.password = ''; busy.value = false; }
}
async function action(source: Source, kind: 'test'|'harvest'|'metadata') {
  if (busy.value) return; busy.value = true; error.value = ''; columns.value = []; selected.value = '';
  try {
    if (kind === 'metadata') { const rows = await metadata(source.id, true); if (alive) { columns.value = rows; selected.value = source.name; } }
    else { await sourceAction(source.id, kind); if (alive) { ElMessage.success(kind === 'test' ? '连接测试成功' : '采集请求已完成，请查看状态'); await reload(); } }
  } catch (cause) { if (alive) { error.value = errorMessage(cause); if (kind === 'harvest') { const message = error.value; await reload(); error.value = message; } } }
  finally { busy.value = false; }
}
onMounted(reload); onUnmounted(() => { alive = false; active?.abort(); form.password = ''; });
</script>
<template><section class="page">
  <div class="page-heading"><div><div class="eyebrow">ADMINISTRATION / DATA SOURCES</div><h1>数据源管理</h1><p class="subtitle">使用独立只读账号连接 StarRocks，仅采集明确授权的表。地址须在服务器允许列表中。</p></div><el-button type="primary" :disabled="busy || loading" @click="open()">新增数据源</el-button></div>
  <el-alert v-if="error && !dialog" :title="error" type="error" :closable="false" class="form-alert" />
  <div class="panel"><div class="panel-heading"><h2>已配置连接</h2><el-button :loading="loading" :disabled="busy" @click="reload">刷新列表</el-button></div>
    <el-table v-loading="loading" :data="sources" empty-text="尚未配置数据源" row-key="id">
      <el-table-column prop="name" label="名称" min-width="140" /><el-table-column label="地址 / 数据库" min-width="190"><template #default="{row}">{{ row.host }}:{{ row.port }}<br>{{ row.catalog }}.{{ row.database }}</template></el-table-column>
      <el-table-column label="状态" width="90"><template #default="{row}">{{ row.enabled ? '启用' : '禁用' }}</template></el-table-column>
      <el-table-column label="采集状态" min-width="170"><template #default="{row}">{{ row.harvestStatus }}<br>{{ row.lastHarvestAt || '尚无采集时间' }}<br><span v-if="row.lastErrorCode">{{ row.lastErrorCode }}</span></template></el-table-column>
      <el-table-column label="操作" min-width="300"><template #default="{row}"><el-button size="small" :disabled="busy || loading" @click="open(row)">编辑</el-button><el-button size="small" :disabled="busy || loading || !row.enabled" @click="action(row, 'test')">测试连接</el-button><el-button size="small" :disabled="busy || loading || !row.enabled" @click="action(row, 'harvest')">采集</el-button><el-button size="small" :disabled="busy || loading" @click="action(row, 'metadata')">字段</el-button></template></el-table-column>
    </el-table><p class="scope-note">连接强制验证 TLS：请配置受信任 CA 和包含目标 IP SAN 的服务端证书，并使用专用只读账号。凭据不在页面回显或浏览器存储中保存。网络超时并不表示服务端操作已取消，请刷新确认状态后再重试。</p>
  </div>
  <div v-if="selected" class="panel metadata-panel"><h2>{{ selected }} · 已采集字段</h2><el-table :data="columns" empty-text="暂无采集快照"><el-table-column prop="tableName" label="表"/><el-table-column prop="columnName" label="字段"/><el-table-column prop="typeName" label="类型"/><el-table-column prop="ordinalPosition" label="顺序"/></el-table></div>
  <el-dialog v-model="dialog" :title="editing ? '编辑数据源' : '新增数据源'" width="min(620px, 94vw)" :close-on-click-modal="!busy" :close-on-press-escape="!busy" :show-close="!busy" @closed="form.password = ''">
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="form-alert"/>
    <el-alert v-if="editing" title="保存后会清除元数据快照，需要重新采集。修改地址、端口、Catalog、数据库、账号或授权表范围，还会撤销关联主题的字段与用户授权，需重新明确授权。仅修改名称、密码或启停状态会保留主题授权。" type="warning" :closable="false" class="form-alert"/>
    <el-form label-position="top" @submit.prevent="save"><fieldset :disabled="busy" class="source-form">
      <el-form-item label="名称"><el-input v-model="form.name" aria-label="名称" maxlength="120"/></el-form-item>
      <el-form-item label="IPv4 地址"><el-input v-model="form.host" aria-label="IPv4 地址" placeholder="服务器允许列表中的 IPv4 地址"/></el-form-item>
      <el-form-item label="端口"><el-input-number v-model="form.port" aria-label="端口" :min="1" :max="65535"/></el-form-item>
      <el-form-item label="Catalog"><el-input v-model="form.catalog" aria-label="Catalog"/></el-form-item><el-form-item label="数据库"><el-input v-model="form.database" aria-label="数据库"/></el-form-item>
      <el-form-item label="只读账号"><el-input v-model="form.username" aria-label="只读账号" autocomplete="off"/></el-form-item>
      <el-form-item :label="editing ? '密码（留空保留现有凭据）' : '密码'"><el-input v-model="form.password" aria-label="数据源密码" type="password" autocomplete="new-password"/></el-form-item>
      <el-form-item label="授权表（每行一个，不接受通配符）"><el-input v-model="tableText" aria-label="授权表" type="textarea" :rows="3"/></el-form-item>
      <el-form-item label="启用数据源"><el-switch v-model="form.enabled" aria-label="启用数据源"/></el-form-item>
      <div class="dialog-actions"><el-button :disabled="busy" @click="dialog = false">取消</el-button><el-button type="primary" native-type="submit" :loading="busy">保存数据源</el-button></div>
    </fieldset></el-form>
  </el-dialog>
</section></template>
