<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router';
import { session } from '../api/auth';
import { ApiError, errorMessage } from '../api/http';
import { listSubjects, type Subject } from '../api/datasources';
import { createConversation, describeTimeRule, isTurnPending, listConversations, readConversation, readTurn, readTurnResult, saveTurnChart, submitTurn, turnStatusLabels, type Conversation, type ConversationSummary, type ConversationTurn } from '../api/conversations';
import type { QueryResult } from '../api/queries';
import { chartOptions } from '../charts/render';
import { shouldSubmitQuestion, titleFromQuestion } from '../conversation/presentation';
import AnalysisResult from '../components/AnalysisResult.vue';

const route = useRoute(), router = useRouter();
const id = computed(() => typeof route.params.id === 'string' ? route.params.id : '');
const conversations = ref<ConversationSummary[]>([]), subjects = ref<Subject[]>([]), conversation = ref<Conversation>();
const subjectId = ref(''), question = ref(''), busy = ref(false), loading = ref(false), error = ref(''), needsReload = ref(false);
const results = ref<Record<string, QueryResult>>({}), resultErrors = ref<Record<string, string>>({}), saveNames = ref<Record<string, string>>({});
const previewLoading = ref(''), saving = ref(''), saveTarget = ref('');
const historyOpen = ref(window.matchMedia('(min-width: 900px)').matches), historySearch = ref(''), composing = ref(false);
const history = computed(() => conversations.value.filter(item => item.title.toLocaleLowerCase().includes(historySearch.value.trim().toLocaleLowerCase())));
const latest = computed(() => conversation.value?.turns.at(-1));
const pending = computed(() => conversation.value?.turns.some(turn => isTurnPending(turn.status)) ?? false);
const blocked = computed(() => busy.value || loading.value || pending.value || needsReload.value);
const selectedSubject = computed(() => subjects.value.find(item => item.id === (conversation.value?.subjectId || subjectId.value)));
const saveTurn = computed(() => conversation.value?.turns.find(turn => turn.id === saveTarget.value));
const chartLabels = { TABLE: '数据表格', KPI: '指标卡', BAR: '柱状图', LINE: '折线图', PIE: '饼图' };
interface FirstAttempt { actor: number|undefined; subjectId: string; question: string; title: string; createKey: string; turnKey: string; summary?: ConversationSummary }
// Component memory only: an unchanged retry reuses both admission keys and any created conversation.
const firstAttempt = ref<FirstAttempt>();
const recoveryId = computed(() => firstAttempt.value?.subjectId === subjectId.value && firstAttempt.value?.question === question.value.trim() ? firstAttempt.value?.summary?.id : undefined);
let generation = 0, controller: AbortController|undefined, timer: ReturnType<typeof setTimeout>|undefined;
let turnKey = crypto.randomUUID();
function reset() { generation++; controller?.abort(); clearTimeout(timer); controller = new AbortController(); return { epoch: generation, signal: controller.signal, actor: session.user?.id, conversationId: id.value }; }
type Context = ReturnType<typeof reset>;
function active(context: Context) { return context.epoch === generation && context.actor === session.user?.id && context.conversationId === id.value && !context.signal.aborted; }
function clearPrivateState() {
  conversation.value = undefined; conversations.value = []; subjects.value = []; results.value = {}; resultErrors.value = {}; saveNames.value = {};
  question.value = ''; subjectId.value = ''; previewLoading.value = ''; saving.value = ''; saveTarget.value = ''; firstAttempt.value = undefined;
  busy.value = false; loading.value = false; needsReload.value = false; historySearch.value = ''; composing.value = false;
}
function failure(cause: unknown) {
  error.value = errorMessage(cause);
  if (cause instanceof ApiError && [401, 403, 404].includes(cause.status ?? 0)) { reset(); clearPrivateState(); }
  if (cause instanceof ApiError && cause.status === 409) { needsReload.value = true; results.value = {}; saveNames.value = {}; saveTarget.value = ''; }
}
function updateTurn(turn: ConversationTurn) {
  if (!conversation.value) return;
  const index = conversation.value.turns.findIndex(item => item.id === turn.id);
  if (index < 0) conversation.value.turns.push(turn); else conversation.value.turns[index] = turn;
}
async function preview(turn: ConversationTurn, context?: Context) {
  if (!conversation.value || !controller || !turn.spec || !['READY', 'NO_DATA'].includes(turn.status)) return;
  const current = context ?? { epoch: generation, signal: controller.signal, actor: session.user?.id, conversationId: id.value };
  previewLoading.value = turn.id; delete results.value[turn.id]; delete resultErrors.value[turn.id];
  try {
    const data = await readTurnResult(conversation.value.id, turn.id, current.signal);
    if (!active(current)) return;
    chartOptions(turn.spec, data); results.value[turn.id] = data;
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
  const draft = !id.value ? { question: question.value, subjectId: subjectId.value, attempt: firstAttempt.value } : undefined;
  const context = reset(); clearPrivateState(); loading.value = true; error.value = '';
  if (draft) question.value = draft.question;
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
    } else {
      subjectId.value = available.some(item => item.id === draft?.subjectId) ? draft!.subjectId : available.length === 1 ? available[0]!.id : '';
      if (draft?.attempt?.actor === context.actor && draft?.attempt?.subjectId === subjectId.value) firstAttempt.value = draft.attempt;
    }
  } catch (cause) { if (active(context)) failure(cause); }
  finally { if (active(context)) loading.value = false; }
}
async function ask() {
  const text = question.value.trim();
  if (blocked.value || saving.value || !text || (id.value ? !conversation.value : !subjects.value.some(item => item.id === subjectId.value))) return;
  const context = reset(); busy.value = true; error.value = ''; results.value = {}; resultErrors.value = {}; previewLoading.value = ''; saveTarget.value = '';
  try {
    if (!context.conversationId) {
      if (!firstAttempt.value || firstAttempt.value.actor !== context.actor || firstAttempt.value.subjectId !== subjectId.value || firstAttempt.value.question !== text) {
        firstAttempt.value = { actor: context.actor, subjectId: subjectId.value, question: text, title: titleFromQuestion(text), createKey: crypto.randomUUID(), turnKey: crypto.randomUUID() };
      }
      const attempt = firstAttempt.value;
      if (!attempt.summary) {
        const created = await createConversation(attempt.subjectId, attempt.title, attempt.createKey, context.signal);
        if (!active(context)) return;
        attempt.summary = created;
      }
      await submitTurn(attempt.summary.id, attempt.question, attempt.summary.version, attempt.turnKey, context.signal);
      if (!active(context)) return;
      // Navigate only after admission. A failed POST keeps the question and keys for an explicit retry.
      question.value = ''; await router.push(`/ask/${encodeURIComponent(attempt.summary.id)}`);
    } else {
      const value = await submitTurn(conversation.value!.id, text, conversation.value!.version, turnKey, context.signal);
      if (!active(context)) return;
      question.value = ''; turnKey = crypto.randomUUID(); await follow(value, context);
    }
  } catch (cause) { if (active(context)) failure(cause); }
  finally { if (active(context)) busy.value = false; }
}
function openSave(turn: ConversationTurn) {
  if (blocked.value || saving.value || turn.chartId || !results.value[turn.id] || !['READY','NO_DATA'].includes(turn.status)) return;
  saveNames.value[turn.id] ||= conversation.value?.title || titleFromQuestion(turn.question); saveTarget.value = turn.id; error.value = '';
}
function closeSave() { if (!saving.value) saveTarget.value = ''; }
async function save(turn: ConversationTurn) {
  if (blocked.value || saving.value || !conversation.value || !controller || turn.chartId || !['READY', 'NO_DATA'].includes(turn.status) || !results.value[turn.id] || !saveNames.value[turn.id]?.trim()) return;
  const context = { epoch: generation, signal: controller.signal, actor: session.user?.id, conversationId: id.value };
  saving.value = turn.id; error.value = '';
  try {
    const saved = await saveTurnChart(conversation.value.id, turn.id, saveNames.value[turn.id]!.trim(), context.signal);
    if (!active(context)) return;
    updateTurn({ ...turn, chartId: saved.id }); saveTarget.value = '';
  } catch (cause) { if (active(context)) failure(cause); }
  finally { if (active(context)) saving.value = ''; }
}
function keydown(event: KeyboardEvent) { if (!composing.value && shouldSubmitQuestion(event)) { event.preventDefault(); void ask(); } }
function lastMonth() { if (!blocked.value && !saving.value) question.value = '改成上个月'; }
watch(question, () => { turnKey = crypto.randomUUID(); }, { flush: 'sync' });
watch(() => route.fullPath, () => { void load(); }, { immediate: true, flush: 'sync' });
watch(() => session.user?.id, () => { reset(); clearPrivateState(); error.value = ''; }, { flush: 'sync' });
onBeforeRouteLeave(() => { reset(); clearPrivateState(); });
onBeforeUnmount(() => { reset(); clearPrivateState(); });
</script>

<template>
  <section class="ask-workspace" :class="{ 'history-collapsed': !historyOpen }">
    <aside v-show="historyOpen" id="conversation-history" class="ask-history" aria-label="我的会话">
      <div class="ask-history-heading"><strong>分析记录</strong><button type="button" aria-label="收起历史会话" @click="historyOpen = false">‹</button></div>
      <RouterLink to="/ask" class="ask-new">＋ 新建分析</RouterLink>
      <label class="history-search"><span class="sr-only">搜索我的会话</span><input v-model="historySearch" type="search" placeholder="搜索分析记录" maxlength="120"></label>
      <div class="ask-history-caption"><span>我的会话</span><button type="button" :disabled="loading || busy || !!saving" @click="load">重新加载</button></div>
      <p v-if="loading && !conversations.length" class="ask-empty-history" role="status">正在读取会话…</p>
      <p v-else-if="!history.length" class="ask-empty-history">{{ historySearch ? '没有匹配的分析记录' : '暂无分析记录' }}</p>
      <nav><RouterLink v-for="item in history" :key="item.id" :to="`/ask/${encodeURIComponent(item.id)}`" :aria-current="item.id === id ? 'page' : undefined" class="ask-history-link" :title="item.title"><span>{{ item.title }}</span></RouterLink></nav>
      <div class="ask-history-footer"><span>会话仅自己可见</span><RouterLink to="/subjects">查看可用数据</RouterLink></div>
    </aside>
    <div class="ask-main">
      <header class="ask-header"><div><button type="button" :aria-label="historyOpen ? '收起历史会话' : '展开历史会话'" aria-controls="conversation-history" :aria-expanded="historyOpen" @click="historyOpen = !historyOpen"><svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="4" width="18" height="16" rx="2"/><path d="M9 4v16"/></svg></button><h1>{{ conversation?.title || '智能问数' }}</h1></div><span v-if="conversation" class="ask-space-label">{{ selectedSubject?.name || '当前授权主题' }}</span></header>
      <div v-if="error && !saveTarget" class="ask-notice"><el-alert :title="error" type="error" :closable="false"/><RouterLink v-if="recoveryId" :to="`/ask/${encodeURIComponent(recoveryId)}`">查看已创建的会话</RouterLink></div>
      <div class="ask-scroll">
        <div v-if="!id" class="ask-intro"><div class="ask-intro-mark" aria-hidden="true"><svg viewBox="0 0 32 32"><path d="M6 7h20v14H13l-7 5V7m5 5h10m-10 4h6"/></svg></div><h2>从一个问题开始</h2><p>选择分析空间，描述你想了解的指标和时间范围。</p><p class="ask-intro-hint">结果可以继续追问，确认后保存为动态图表。</p><div v-if="!loading && !subjects.length" class="ask-no-sources"><strong>暂无可用分析空间</strong><p>请联系管理员配置数据授权和已发布的业务口径。</p><RouterLink v-if="session.user?.role === 'SUPER_ADMIN'" to="/subjects">配置业务主题 →</RouterLink></div></div>
        <p v-else-if="loading && !conversation" class="ask-loading" role="status">正在读取会话…</p>
        <div v-else-if="conversation && !conversation.turns.length" class="ask-intro"><h2>开始这次分析</h2><p>请输入具体的业务指标和时间范围。含糊的问题会先请求澄清。</p></div>
        <ol v-if="conversation" class="ask-turns" aria-label="会话记录">
          <li v-for="turn in conversation.turns" :key="turn.id" class="ask-turn" :data-turn-id="turn.id">
            <div class="turn-question"><span>你 · 问题 {{ turn.sequence }}</span><h3>{{ turn.question }}</h3></div>
            <div class="turn-status" aria-live="polite"><strong>DataLens</strong><span :class="{ pending: isTurnPending(turn.status) }">{{ turnStatusLabels[turn.status] }}</span></div>
            <p v-if="isTurnPending(turn.status)" class="turn-hint">正在处理本轮问题。离开页面不会自动保存，也不代表数据库执行已取消。</p>
            <el-alert v-if="turn.status === 'CLARIFICATION_REQUIRED'" :title="turn.clarification || '请补充具体指标和时间范围后继续提问。'" type="info" :closable="false"/>
            <el-alert v-if="turn.status === 'FAILED' || turn.status === 'INTERRUPTED'" :title="`本轮未获得可用结果${turn.errorCode ? `：${turn.errorCode}` : ''}`" type="error" :closable="false"/>
            <el-alert v-if="turn.status === 'NO_DATA'" title="查询成功，但没有匹配数据。不会据此生成趋势、总额或原因说明。" type="info" :closable="false"/>
            <el-alert v-if="turn.truncated && !results[turn.id]" title="结果已截断，仅代表返回的部分数据；不计算总额或占比。" type="warning" :closable="false"/>
            <el-alert v-if="resultErrors[turn.id]" :title="resultErrors[turn.id]!" type="error" :closable="false"/>
            <AnalysisResult v-if="results[turn.id]" :turn="turn" :result="results[turn.id]!"/>
            <el-button v-else-if="['READY','NO_DATA'].includes(turn.status)" class="turn-preview" :loading="previewLoading === turn.id" :disabled="blocked || !!previewLoading" @click="preview(turn)">查看结果预览</el-button>
            <div v-if="turn.chartId" class="turn-actions"><span class="turn-saved">已保存为私有图表</span><RouterLink :to="`/charts/${encodeURIComponent(turn.chartId)}`">打开已保存图表 →</RouterLink></div>
            <div v-else-if="['READY','NO_DATA'].includes(turn.status) && results[turn.id]" class="turn-actions"><span>当前为预览，尚未保存</span><el-button :disabled="blocked || !!saving" @click="openSave(turn)">保存图表</el-button></div>
          </li>
        </ol>
      </div>
      <div v-if="!id || conversation" class="ask-compose-dock">
        <p v-if="needsReload" class="composer-warning" role="status">会话可能已在其他页面更新，或查询状态读取失败。请先重新加载后继续。<button type="button" :disabled="busy || loading || !!saving" @click="load">重新加载会话</button></p>
        <form class="ask-composer" @submit.prevent="ask">
          <div v-if="!id" class="composer-source"><label for="ask-subject">分析空间</label><select id="ask-subject" v-model="subjectId" aria-label="分析空间" :disabled="loading || busy || !!saving || !subjects.length" required><option value="">{{ loading ? '正在读取…' : subjects.length ? '请选择有权访问的空间' : '暂无可用分析空间' }}</option><option v-for="subject in subjects" :key="subject.id" :value="subject.id">{{ subject.name }}</option></select></div>
          <label class="sr-only" for="ask-question">问数问题</label><textarea id="ask-question" v-model="question" maxlength="4000" rows="3" :disabled="blocked || !!saving || (!id && !subjects.length)" required :placeholder="latest?.status === 'CLARIFICATION_REQUIRED' ? '补充指标或时间范围…' : conversation?.turns.length ? '继续追问，例如：改成上个月' : '描述你想了解的数据，例如：最近一个月的订单金额'" @keydown="keydown" @compositionstart="composing = true" @compositionend="composing = false"/>
          <div class="composer-toolbar"><span>{{ busy || pending ? '正在处理，请勿重复提交' : 'Enter 发送 · Shift + Enter 换行' }}</span><div><el-button v-if="latest?.metricDescription" size="small" :disabled="blocked || !!saving" @click="lastMonth">改成上个月</el-button><el-button native-type="submit" type="primary" :loading="busy || pending" :disabled="blocked || !!saving || !question.trim() || (!id && !subjectId)">{{ id ? '提交问题' : '开始分析' }}</el-button></div></div>
        </form>
        <p class="composer-footnote">当前问数基于已发布的业务口径。请勿输入密码、密钥或不适合发送给模型的敏感信息。</p>
      </div>
    </div>
    <el-dialog v-if="saveTurn" :model-value="true" title="保存图表" width="min(520px, 92vw)" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving" @update:model-value="value => { if (!value) closeSave(); }">
      <form class="save-chart-form" @submit.prevent="save(saveTurn!)">
        <el-alert v-if="error" :title="error" type="error" :closable="false"/>
        <label>图表名称<el-input v-model="saveNames[saveTurn.id]" aria-label="图表名称" maxlength="120" :disabled="!!saving" autofocus placeholder="为这次分析命名"/></label>
        <dl><dt>保存类型</dt><dd>{{ saveTurn.spec ? chartLabels[saveTurn.spec.type] : '未提供' }}</dd><dt>时间规则</dt><dd>{{ describeTimeRule(saveTurn.timeRule) }}</dd><dt>指标口径</dt><dd>{{ saveTurn.metricDescription || '未提供' }}</dd></dl>
        <p class="save-private-note">默认仅自己可见。保存查询定义和图表配置，打开图表时重新查询；共享范围在保存后单独设置。</p>
        <div class="dialog-actions"><el-button :disabled="!!saving" @click="closeSave">取消</el-button><el-button native-type="submit" type="primary" :loading="!!saving" :disabled="blocked || !!saving || !saveNames[saveTurn.id]?.trim()">确认保存私有图表</el-button></div>
      </form>
    </el-dialog>
  </section>
</template>

<style scoped>
.sr-only{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}.ask-workspace{height:100%;display:grid;grid-template-columns:224px minmax(0,1fr);min-height:0;position:relative}.ask-workspace.history-collapsed{grid-template-columns:minmax(0,1fr)}.ask-history{display:flex;flex-direction:column;min-height:0;padding:18px 12px 12px;background:#fafbfc;border-right:1px solid #e5e8ed}.ask-history-heading{display:flex;align-items:center;justify-content:space-between;padding:0 6px 16px;font-size:13px}.ask-history-heading button{font-size:23px;border:0;background:none;color:#8490a0;cursor:pointer}.ask-new{display:block;padding:11px 12px;border:1px solid #dce2eb;border-radius:5px;background:white;font-size:13px;color:#3565a4}.history-search input{margin:16px 0;width:100%;background:transparent;border:1px solid transparent;border-radius:4px;padding:8px 10px;font-size:12px;color:#536173}.history-search input:focus{outline:1px solid #9bb4d7;background:white}.ask-history-caption{display:flex;justify-content:space-between;align-items:center;padding:0 8px 10px;font-size:11px;color:#9099a5}.ask-history-caption button{border:0;background:none;font-size:11px;color:#7c8a9d;cursor:pointer}.ask-history-caption button:disabled{opacity:.5;cursor:default}.ask-history nav{flex:1;min-height:0;overflow:auto}.ask-history-link{display:block;padding:11px 10px;margin-bottom:4px;border-radius:4px;color:#596879;font-size:13px}.ask-history-link span{display:block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.ask-history-link:hover{background:#f0f3f7}.ask-history-link[aria-current=page]{background:#eaf0f8;color:#2b5d9c}.ask-empty-history{padding:18px 8px;font-size:12px;color:#9aa3ae}.ask-history-footer{display:grid;gap:12px;padding:16px 8px 4px;border-top:1px solid #e9edf2;font-size:11px;color:#9aa3ae}.ask-history-footer a{color:#637b9a}.ask-main{display:flex;flex-direction:column;min-width:0;min-height:0;background:#fff}.ask-header{display:flex;justify-content:space-between;align-items:center;gap:20px;padding:16px 26px;flex-shrink:0}.ask-header>div{display:flex;align-items:center;gap:13px;min-width:0}.ask-header h1{font-size:16px;font-weight:550;overflow:hidden;white-space:nowrap;text-overflow:ellipsis}.ask-header button{display:grid;place-items:center;flex:0 0 28px;height:28px;border:0;background:none;color:#7d8a9a;cursor:pointer}.ask-header svg{width:18px;height:18px;stroke:currentColor;fill:none;stroke-width:1.5}.ask-space-label{font-size:12px;color:#8c97a5;white-space:nowrap}.ask-scroll{flex:1;min-height:0;overflow:auto;scroll-behavior:auto;padding:0 30px 24px;display:flex;flex-direction:column}.ask-notice{padding:0 30px 10px}.ask-notice>a{display:inline-block;font-size:12px;color:#3568bd;margin-top:8px}.ask-intro{width:100%;max-width:760px;margin:auto;display:flex;flex-direction:column;align-items:center;text-align:center;padding:36px 12px;color:#485b72}.ask-intro-mark{width:52px;height:52px;margin-bottom:20px;display:grid;place-items:center;border:1px solid #e6ebf1;border-radius:12px;background:#fafbfc;color:#647f9e}.ask-intro-mark svg{width:30px;height:30px;stroke:currentColor;fill:none;stroke-width:1.4;stroke-linecap:round;stroke-linejoin:round}.ask-intro h2{font-size:25px;font-weight:550;letter-spacing:.2px;margin-bottom:16px;color:#2e4056}.ask-intro>p{font-size:13px;line-height:1.9;color:#7f8c9c}.ask-intro .ask-intro-hint{font-size:12px;margin-top:7px;color:#a0a8b3}.ask-no-sources{margin-top:28px;padding:18px 24px;border:1px solid #e5e9ef;border-radius:6px;font-size:12px;line-height:1.9;color:#7b8797}.ask-no-sources strong{font-weight:500;color:#63748b}.ask-no-sources a{color:#3568bd}.ask-loading{padding:24px;font-size:13px;color:#8996a5}.ask-turns{list-style:none;padding:8px 0 0;margin:0 auto;width:100%;max-width:1000px;display:grid;gap:36px}.ask-turn{display:flex;flex-direction:column;gap:14px;min-width:0}.turn-question{align-self:flex-end;max-width:86%;background:#f1f4f9;border:1px solid #edf0f5;padding:14px 18px;border-radius:8px 8px 2px 8px;overflow-wrap:anywhere}.turn-question span{display:block;font-size:10px;color:#909bad;margin-bottom:7px}.turn-question h3{font-size:14px;font-weight:450;line-height:1.8;white-space:pre-wrap}.turn-status{display:flex;align-items:center;gap:12px;font-size:12px;padding-top:8px}.turn-status strong{color:#50647d;font-weight:600}.turn-status>span{font-size:11px;color:#8c98a6}.turn-status .pending{color:#3568bd}.turn-hint{font-size:12px;line-height:1.8;color:#8b96a4}.turn-preview{align-self:flex-start}.turn-actions{display:flex;align-items:center;justify-content:flex-end;gap:14px;font-size:12px;color:#8995a5}.turn-actions a{color:#3568bd}.turn-actions .turn-saved{color:#53816a}.ask-compose-dock{width:100%;max-width:1060px;padding:12px 30px 20px;margin:0 auto;flex-shrink:0}.ask-composer{border:1px solid #dce3ed;border-radius:8px;background:#fff;box-shadow:0 3px 16px #20395805}.ask-composer:focus-within{border-color:#9eb5d5}.composer-source{display:flex;align-items:center;gap:10px;padding:11px 16px;border-bottom:1px solid #edf0f4;font-size:12px}.composer-source label{color:#8a96a5;flex-shrink:0}.composer-source select{border:0;background:transparent;color:#566b85;max-width:80%;font-size:12px;padding:3px;min-width:140px}.ask-composer textarea{display:block;width:100%;resize:vertical;min-height:80px;max-height:220px;border:0;outline:none!important;background:transparent;line-height:1.8;padding:14px 16px 5px;color:#34465c;font-size:14px}.ask-composer textarea::placeholder{color:#a4adba}.ask-composer textarea:disabled{color:#8b98a8}.composer-toolbar{display:flex;justify-content:space-between;align-items:center;gap:12px;padding:10px 14px}.composer-toolbar>span{font-size:10px;color:#a5adb9}.composer-toolbar>div{display:flex;align-items:center;gap:8px}.composer-footnote{font-size:10px;text-align:center;color:#a0a9b6;line-height:1.6;margin-top:10px}.composer-warning{font-size:12px;color:#9e7533;margin-bottom:12px;line-height:1.8}.composer-warning button{border:0;background:none;color:#3568bd;cursor:pointer}.save-chart-form{display:grid;gap:20px}.save-chart-form>label{display:grid;gap:9px;font-size:13px}.save-chart-form dl{display:grid;grid-template-columns:65px minmax(0,1fr);gap:12px 16px;margin:0;font-size:12px;line-height:1.8}.save-chart-form dt{color:#8b96a4}.save-chart-form dd{margin:0;color:#596b80;overflow-wrap:anywhere}.save-private-note{padding:13px 15px;background:#f6f8fb;border-radius:4px;font-size:12px;color:#7d8ca0;line-height:1.8}.save-chart-form .dialog-actions{margin-top:0}@media(max-width:1100px){.ask-workspace{grid-template-columns:200px minmax(0,1fr)}.ask-header{padding:14px 18px}.ask-scroll{padding:0 20px 20px}.ask-compose-dock{padding:10px 20px 16px}}@media(max-width:899px){.ask-workspace{grid-template-columns:minmax(0,1fr)}.ask-history{position:absolute;left:0;top:0;bottom:0;width:224px;z-index:10;box-shadow:8px 0 20px #25324712}.ask-space-label{display:none}}@media(max-width:700px){.ask-scroll{padding:0 12px 16px}.ask-compose-dock{padding:8px 12px 12px}.ask-header{padding:12px}.ask-header h1{font-size:15px}.ask-intro{padding:22px 6px}.ask-intro h2{font-size:21px}.composer-toolbar>span{display:none}.composer-toolbar{justify-content:flex-end}.composer-footnote{font-size:9px}.turn-question{max-width:96%}.ask-notice{padding:0 12px 10px}.composer-toolbar .el-button{padding-left:10px;padding-right:10px}}
</style>
