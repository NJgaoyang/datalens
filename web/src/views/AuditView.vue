<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue';
import { session } from '../api/auth';
import { listAuditEvents, type AuditEvent } from '../api/audit';
import { errorMessage } from '../api/http';
const items = ref<AuditEvent[]>([]), total = ref(0), page = ref(1), busy = ref(false), error = ref('');
let controller: AbortController|undefined;
async function load() {
  controller?.abort(); const current = new AbortController(); controller = current; items.value = []; total.value = 0; error.value = ''; busy.value = true;
  if (session.user?.role !== 'SUPER_ADMIN') { busy.value = false; return; }
  try { const result = await listAuditEvents(page.value,current.signal); if (!current.signal.aborted) { items.value = result.items; total.value = result.total; } }
  catch (cause) { if (!current.signal.aborted) error.value = errorMessage(cause); }
  finally { if (!current.signal.aborted) busy.value = false; }
}
watch(() => session.user?.id, () => { page.value = 1; void load(); }, {immediate:true});
onBeforeUnmount(() => controller?.abort());
</script>
<template><section class="page"><div class="page-heading"><div><div class="eyebrow">SYSTEM / AUDIT</div><h1>查询与操作日志</h1><p class="subtitle">仅超管可查看。记录实际操作人、资源和结果，不显示查询正文、参数或凭据。</p></div><el-button :loading="busy" @click="load">刷新日志</el-button></div>
<el-alert v-if="error" :title="error" type="error" :closable="false"/><div class="panel"><p v-if="busy">正在读取日志…</p><p v-else-if="!items.length">暂无日志。</p>
<div class="audit-table-scroll"><table v-if="items.length" class="audit-table"><thead><tr><th>时间</th><th>实际用户</th><th>操作</th><th>资源</th><th>结果</th><th>请求编号</th></tr></thead><tbody><tr v-for="item in items" :key="item.id"><td>{{ item.occurredAt }}</td><td>{{ item.actorId == null ? '系统' : `#${item.actorId}` }}</td><td>{{ item.action }}</td><td>{{ item.resourceType }} · {{ item.resourceId ?? '—' }}</td><td>{{ item.outcome }}</td><td>{{ item.requestId }}</td></tr></tbody></table></div>
<el-pagination v-if="total > 20" v-model:current-page="page" :page-size="20" :total="total" layout="prev, pager, next" @current-change="load"/>
</div></section></template>
