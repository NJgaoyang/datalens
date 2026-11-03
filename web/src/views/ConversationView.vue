<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router';
import { session } from '../api/auth';
import { ApiError, errorMessage } from '../api/http';
import { listSubjects, type Subject } from '../api/datasources';
import { createConversation, describeTimeRule, isTurnPending, listConversations, readConversation, readTurn, readTurnResult, saveTurnChart, submitTurn, turnStatusLabels, type Conversation, type ConversationSummary, type ConversationTurn } from '../api/conversations';
import type { QueryResult } from '../api/queries';
import { chartOptions } from '../charts/render';
import ChartRenderer from '../components/ChartRenderer.vue';

const route = useRoute(), router = useRouter();
const id = computed(() => typeof route.params.id === 'string' ? route.params.id : '');
const conversations = ref<ConversationSummary[]>([]), subjects = ref<Subject[]>([]), conversation = ref<Conversation>();
const subjectId = ref(''), title = ref(''), question = ref(''), busy = ref(false), loading = ref(false), error = ref(''), needsReload = ref(false);
const results = ref<Record<string, QueryResult>>({}), resultErrors = ref<Record<string, string>>({}), saveNames = ref<Record<string, string>>({}), previewLoading = ref(''), saving = ref('');
const latest = computed(() => conversation.value?.turns.at(-1));
const pending = computed(() => conversation.value?.turns.some(turn => isTurnPending(turn.status)) ?? false);
const blocked = computed(() => busy.value || loading.value || pending.value || needsReload.value);
let generation = 0, controller: AbortController|undefined, timer: ReturnType<typeof setTimeout>|undefined;
let createKey = crypto.randomUUID(), turnKey = crypto.randomUUID();
function reset() { generation++; controller?.abort(); clearTimeout(timer); controller = new AbortController(); return { epoch: generation, signal: controller.signal, actor: session.user?.id, conversationId: id.value }; }
type Context = ReturnType<typeof reset>;
function active(context: Context) { return context.epoch === generation && context.actor === session.user?.id && context.conversationId === id.value && !context.signal.aborted; }
function clearPrivateState() { conversation.value = undefined; conversations.value = []; subjects.value = []; results.value = {}; resultErrors.value = {}; saveNames.value = {}; question.value = ''; subjectId.value = ''; title.value = ''; previewLoading.value = ''; saving.value = ''; busy.value = false; loading.value = false; needsReload.value = false; }
function failure(cause: unknown) {
  error.value = errorMessage(cause);
  if (cause instanceof ApiError && [401, 403, 404].includes(cause.status ?? 0)) { reset(); clearPrivateState(); }
  if (cause instanceof ApiError && cause.status === 409) { needsReload.value = true; results.value = {}; saveNames.value = {}; }
}
function updateTurn(turn: ConversationTurn) {
  if (!conversation.value) return;
  const index = conversation.value.turns.findIndex(item => item.id === turn.id);
  if (index < 0) conversation.value.turns.push(turn); else conversation.value.turns[index] = turn;
}
async function preview(turn: ConversationTurn, context?: Context) {
  if (!conversation.value || !turn.spec || !['READY', 'NO_DATA'].includes(turn.status)) return;
  const current = context ?? { epoch: generation, signal: controller!.signal, actor: session.user?.id, conversationId: id.value };
  previewLoading.value = turn.id; delete results.value[turn.id]; delete resultErrors.value[turn.id];
  try {
    const data = await readTurnResult(conversation.value.id, turn.id, current.signal);
    if (!active(current)) return;
    chartOptions(turn.spec, data);
    results.value[turn.id] = data;
    saveNames.value[turn.id] = saveNames.value[turn.id] || conversation.value.title;
  } catch (cause) {
    if (!active(current)) return;
    if (cause instanceof ApiError && [401, 403, 404].includes(cause.status ?? 0)) failure(cause);
    else resultErrors.value[turn.id] = errorMessage(cause);
  } finally { if (active(current) && previewLoading.value === turn.id) previewLoading.value = ''; }
}
async function follow(turn: ConversationTurn, context: Context) {
  if (!active(context)) return;
  updateTurn(turn);
  if (isTurnPending(turn.status)) {
    timer = setTimeout(async () => {
      if (!active(context)) return;
      try { await follow(await readTurn(context.conversationId, turn.id, context.signal), context); }
      catch (cause) { if (active(context)) { failure(cause); needsReload.value = true; } }
    }, 700);
    return;
  }
  const current = await readConversation(context.conversationId, context.signal);
  if (!active(context)) return;
  conversation.value = current;
  const completed = current.turns.find(item => item.id === turn.id);
  if (completed && ['READY', 'NO_DATA'].includes(completed.status)) await preview(completed, context);
}
async function load() {
  const context = reset(); clearPrivateState(); loading.value = true; error.value = '';
  try {
    const [list, available] = await Promise.all([listConversations(context.signal), listSubjects(false, context.signal)]);
    if (!active(context)) return;
    conversations.value = list; subjects.value = available;
    if (context.conversationId) {
      const value = await readConversation(context.conversationId, context.signal);
      if (!active(context)) return;
      conversation.value = value;
      const running = value.turns.find(turn => isTurnPending(turn.status));
      if (running) await follow(running, context);
      else if (value.turns.at(-1) && ['READY', 'NO_DATA'].includes(value.turns.at(-1)!.status)) await preview(value.turns.at(-1)!, context);
    }
  } catch (cause) { if (active(context)) failure(cause); }
  finally { if (active(context)) loading.value = false; }
}
async function create() {
  if (busy.value || loading.value || !subjectId.value || !title.value.trim()) return;
  const context = reset(); busy.value = true; error.value = '';
  try {
    const value = await createConversation(subjectId.value, title.value.trim(), createKey, context.signal);
    if (!active(context)) return;
    createKey = crypto.randomUUID(); await router.push(`/ask/${encodeURIComponent(value.id)}`);
  } catch (cause) { if (active(context)) failure(cause); }
  finally { if (active(context)) busy.value = false; }
}
async function ask() {
  if (blocked.value || !conversation.value || !question.value.trim()) return;
  const context = reset(); busy.value = true; error.value = ''; results.value = {}; resultErrors.value = {}; previewLoading.value = ''; saving.value = '';
  try {
    const value = await submitTurn(conversation.value.id, question.value.trim(), conversation.value.version, turnKey, context.signal);
    if (!active(context)) return;
    question.value = ''; turnKey = crypto.randomUUID();
    await follow(value, context);
  } catch (cause) { if (active(context)) failure(cause); }
  finally { if (active(context)) busy.value = false; }
}
async function save(turn: ConversationTurn) {
  if (blocked.value || saving.value || !conversation.value || turn.chartId || !['READY', 'NO_DATA'].includes(turn.status) || !results.value[turn.id] || !saveNames.value[turn.id]?.trim()) return;
  const context = { epoch: generation, signal: controller!.signal, actor: session.user?.id, conversationId: id.value };
  saving.value = turn.id; error.value = '';
  try {
    const saved = await saveTurnChart(conversation.value.id, turn.id, saveNames.value[turn.id]!.trim(), context.signal);
    if (!active(context)) return;
    updateTurn({ ...turn, chartId: saved.id });
  } catch (cause) { if (active(context)) failure(cause); }
  finally { if (active(context)) saving.value = ''; }
}
function lastMonth() { if (!blocked.value) question.value = '改成上个月'; }
watch([subjectId, title], () => { createKey = crypto.randomUUID(); });
watch(question, () => { turnKey = crypto.randomUUID(); }, { flush: 'sync' });
watch(() => route.fullPath, () => { void load(); }, { immediate: true, flush: 'sync' });
watch(() => session.user?.id, () => { reset(); clearPrivateState(); error.value = ''; }, { flush: 'sync' });
onBeforeRouteLeave(() => { reset(); clearPrivateState(); });
onBeforeUnmount(() => { reset(); clearPrivateState(); });
</script>

<template>
  <section class="page conversation-page">
    <div class="page-heading"><div><div class="eyebrow">WORKSPACE / ASK</div><h1>智能问数</h1><p class="subtitle">基于已审核指标进行问数。先查看真实查询预览，再明确确认保存为私有图表。</p></div><RouterLink v-if="id" to="/ask" class="el-button">新建会话</RouterLink></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="form-alert"/>
    <div class="conversation-layout">
      <aside class="panel conversation-history" aria-label="我的会话">
        <div class="panel-heading"><h2>我的会话</h2><el-button size="small" :disabled="loading || busy || !!saving" @click="load">重新加载</el-button></div>
        <p class="scope-note">仅当前账号可见。打开历史会话不会重新执行 SQL。</p>
        <p v-if="loading && !conversations.length" role="status">正在读取会话…</p><p v-else-if="!conversations.length" class="scope-note">暂无会话</p>
        <nav><RouterLink v-for="item in conversations" :key="item.id" :to="`/ask/${encodeURIComponent(item.id)}`" :aria-current="item.id === id ? 'page' : undefined" class="conversation-link"><strong>{{ item.title }}</strong><span>版本 {{ item.version }}</span></RouterLink></nav>
      </aside>
      <div class="conversation-main">
        <form v-if="!id" class="panel chart-form" @submit.prevent="create"><h2>新建问数会话</h2><label>业务主题<select v-model="subjectId" aria-label="会话业务主题" required :disabled="loading || busy"><option value="">请选择有权访问的主题</option><option v-for="subject in subjects" :key="subject.id" :value="subject.id">{{ subject.name }}</option></select></label><label>会话标题<input v-model="title" aria-label="会话标题" maxlength="120" required :disabled="loading || busy" placeholder="例如：月度销售分析"></label><p class="scope-note">使用已发布且仍有效的业务语义。请勿在问题中输入密码、密钥或不适合发给模型提供商的敏感信息。</p><el-button native-type="submit" type="primary" :loading="busy" :disabled="loading || !subjectId || !title.trim()">创建会话</el-button></form>
        <template v-if="conversation">
          <div class="panel conversation-heading"><h2>{{ conversation.title }}</h2><p class="scope-note">主题：{{ subjects.find(subject => subject.id === conversation!.subjectId)?.name || '当前授权主题' }} · 会话版本 {{ conversation.version }}</p></div>
          <p v-if="!conversation.turns.length" class="panel scope-note">请输入想了解的业务指标。含糊的问题会先请求澄清，不会猜测数据。</p>
          <ol class="conversation-turns" aria-label="会话记录">
            <li v-for="turn in conversation.turns" :key="turn.id" class="panel conversation-turn" :data-turn-id="turn.id">
              <div class="turn-question"><span>问题 {{ turn.sequence }}</span><h3>{{ turn.question }}</h3></div>
              <div class="turn-status" aria-live="polite"><strong>{{ turnStatusLabels[turn.status] }}</strong><span>请求时间：{{ turn.createdAt }}</span></div>
              <p v-if="isTurnPending(turn.status)" class="scope-note">正在自动检查状态。离开页面不会把尚未完成的查询显示为成功，也不会自动保存图表。</p>
              <el-alert v-if="turn.status === 'CLARIFICATION_REQUIRED'" :title="turn.clarification || '请补充具体指标和时间范围后继续提问。'" type="info" :closable="false"/>
              <el-alert v-if="turn.status === 'FAILED' || turn.status === 'INTERRUPTED'" :title="`本轮未获得可用结果${turn.errorCode ? `：${turn.errorCode}` : ''}`" type="error" :closable="false"/>
              <div v-if="turn.metricDescription || turn.timeRule" class="turn-basis"><p><strong>指标口径：</strong>{{ turn.metricDescription || '未提供' }}</p><p>{{ describeTimeRule(turn.timeRule) }}</p><p>查询完成时间：{{ turn.queryTime || '尚未完成' }}</p><p v-if="turn.taskId">查询任务：{{ turn.taskId }}</p></div>
              <el-alert v-if="turn.status === 'NO_DATA'" title="查询成功，但没有匹配数据。不会据此生成趋势、总额或原因说明。" type="info" :closable="false"/>
              <el-alert v-if="turn.truncated && !results[turn.id]" title="结果已截断，仅代表返回的部分数据；不计算总额或占比。" type="warning" :closable="false"/>
              <el-alert v-if="resultErrors[turn.id]" :title="resultErrors[turn.id]!" type="error" :closable="false"/>
              <ChartRenderer v-if="results[turn.id] && turn.spec" :spec="turn.spec" :result="results[turn.id]!"/>
              <el-button v-else-if="['READY','NO_DATA'].includes(turn.status)" :loading="previewLoading === turn.id" :disabled="blocked || !!previewLoading" @click="preview(turn)">查看结果预览</el-button>
              <p v-if="results[turn.id]" class="scope-note">本次预览返回 {{ results[turn.id]!.rows.length }} 行，上限 {{ results[turn.id]!.rowLimit }} 行。仅显示查询返回数据，不生成数据结论。</p>
              <p v-if="turn.chartId" class="turn-saved">已保存为私有图表 · <RouterLink :to="`/charts/${encodeURIComponent(turn.chartId)}`">打开已保存图表</RouterLink></p>
              <form v-else-if="['READY', 'NO_DATA'].includes(turn.status) && results[turn.id]" class="chart-form turn-save" @submit.prevent="save(turn)"><label>保存图表名称<input v-model="saveNames[turn.id]" :aria-label="`第 ${turn.sequence} 轮图表名称`" maxlength="120" required :disabled="blocked || !!saving"></label><p class="scope-note">确认后保存当前已预览的指标口径、时间规则和图表规格，默认私有。此操作不会共享或导出。</p><el-button native-type="submit" type="success" :loading="saving === turn.id" :disabled="blocked || !!saving || !saveNames[turn.id]?.trim()">确认保存私有图表</el-button></form>
            </li>
          </ol>
          <form class="panel chart-form conversation-composer" @submit.prevent="ask"><label>{{ latest?.status === 'CLARIFICATION_REQUIRED' ? '补充问题' : '继续提问' }}<textarea v-model="question" aria-label="问数问题" maxlength="4000" rows="3" :disabled="blocked || !!saving" required placeholder="输入具体业务指标，或追问“改成上个月”"/></label><p v-if="busy" role="status" class="scope-note">正在理解问题并准备结果预览。请勿重复提交；此操作不会自动保存。</p><p v-if="needsReload" class="scope-note" role="status">会话可能已在其他页面更新，或查询状态读取失败。请先重新加载后继续。</p><div class="query-actions"><el-button native-type="submit" type="primary" :loading="busy || pending" :disabled="blocked || !!saving || !question.trim()">提交问题</el-button><el-button v-if="latest?.metricDescription" :disabled="blocked || !!saving" @click="lastMonth">改成上个月</el-button></div><p class="scope-note">追问“改成上个月”保留已选指标，按业务时区使用上一个完整自然月。若缺少已审核日期规则，系统会先澄清。</p></form>
        </template>
        <p v-else-if="id && loading" class="panel" role="status">正在读取会话…</p>
      </div>
    </div>
  </section>
</template>

<style scoped>
.conversation-layout{display:grid;grid-template-columns:240px minmax(0,1fr);gap:20px;align-items:start}.conversation-history{padding:18px;position:sticky;top:18px}.conversation-history .scope-note{margin:12px 0 18px}.conversation-history nav{display:grid;gap:8px}.conversation-link{display:grid;gap:6px;padding:12px;border:1px solid #e3e7ed;border-radius:6px;overflow-wrap:anywhere}.conversation-link[aria-current=page]{background:#edf4fc;border-color:#93b6df}.conversation-link span{color:#718093;font-size:11px}.conversation-main{min-width:0;display:grid;gap:18px}.conversation-main h2{font-size:17px}.conversation-main .chart-form,.conversation-main .scope-note{margin-bottom:0}.conversation-heading .scope-note{margin-top:10px}.conversation-turns{display:grid;gap:18px;list-style:none;margin:0;padding:0}.conversation-turn{display:grid;gap:18px;overflow-wrap:anywhere}.turn-question{display:grid;gap:8px}.turn-question span{font-size:11px;color:#718093}.turn-question h3{font-size:17px;line-height:1.6;white-space:pre-wrap}.turn-status{display:flex;flex-wrap:wrap;justify-content:space-between;gap:10px;font-size:13px}.turn-status span{font-size:11px;color:#718093}.turn-basis{display:grid;gap:8px;padding:14px;background:#f6f8fb;border-radius:5px;font-size:12px;line-height:1.8}.turn-save{border-top:1px solid #e3e7ed;padding-top:18px}.turn-save .scope-note,.conversation-turn>.scope-note,.conversation-composer>.scope-note{margin-top:0}.turn-save .el-button,.conversation-main>.chart-form>.el-button{justify-self:start}.turn-saved{color:#237251;font-size:13px}.turn-saved a{color:#2865ad;text-decoration:underline}.conversation-composer .query-actions{margin-top:0;flex-wrap:wrap}@media(max-width:1150px){.conversation-layout{grid-template-columns:1fr}.conversation-history{position:static}.conversation-history nav{grid-template-columns:repeat(auto-fill,minmax(160px,1fr));max-height:210px;overflow:auto}}
</style>
