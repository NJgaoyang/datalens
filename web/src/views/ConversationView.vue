<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router';
import { session } from '../api/auth';
import { ApiError, errorMessage } from '../api/http';
import { listSubjects, metadata, type Column, type Subject } from '../api/datasources';
import { createConversation, describeTimeRule, isTurnPending, readConversation, readTurn, readTurnResult, saveTurnChart, submitTurn, turnStatusLabels, type Conversation, type ConversationSummary, type ConversationTurn } from '../api/conversations';
import type { QueryResult } from '../api/queries';
import { chartOptions } from '../charts/render';
import { shouldSubmitQuestion, titleFromQuestion } from '../conversation/presentation';
import AnalysisResult from '../components/AnalysisResult.vue';
import { clearHistoryImmediately } from '../conversation/historySignal';

const route = useRoute(), router = useRouter();
const id = computed(() => typeof route.params.id === 'string' ? route.params.id : '');
const subjects = ref<Subject[]>([]), conversation = ref<Conversation>();
const subjectId = ref(''), question = ref(''), busy = ref(false), loading = ref(false), error = ref(''), needsReload = ref(false);
const results = ref<Record<string, QueryResult>>({}), resultErrors = ref<Record<string, string>>({}), saveNames = ref<Record<string, string>>({});
const previewLoading = ref(''), saving = ref(''), saveTarget = ref('');
const composing = ref(false), showExamples = ref(false), understandingOpen = ref(true);
const columns = ref<Column[]>([]), metadataLoading = ref(false), metadataError = ref('');
const exampleQuestions = [
  '最近 7 天的业务指标趋势',
  '本月各地区的指标对比',
  '最近 30 天的变化情况',
  '上个月的指标排名',
];
const scopeId = computed(() => conversation.value?.subjectId || subjectId.value);
const availableTables = computed(() => [...new Set(columns.value.map(item => item.tableName))].slice(0, 6));
let metadataController: AbortController|undefined;
const pending = computed(() => conversation.value?.turns.some(turn => isTurnPending(turn.status)) ?? false);
const blocked = computed(() => busy.value || loading.value || pending.value || needsReload.value);
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
  conversation.value = undefined; subjects.value = []; results.value = {}; resultErrors.value = {}; saveNames.value = {};
  question.value = ''; subjectId.value = ''; previewLoading.value = ''; saving.value = ''; saveTarget.value = ''; firstAttempt.value = undefined;
  busy.value = false; loading.value = false; needsReload.value = false; composing.value = false;
}
function failure(cause: unknown) {
  error.value = errorMessage(cause);
  if (cause instanceof ApiError && [401, 403, 404].includes(cause.status ?? 0)) {
    if (cause.status === 403 || cause.status === 404) clearHistoryImmediately();
    reset(); clearPrivateState();
  }
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
    const available = await listSubjects(false, context.signal);
    if (!active(context)) return;
    subjects.value = available;
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
watch(question, () => { turnKey = crypto.randomUUID(); }, { flush: 'sync' });
watch(() => route.fullPath, () => { void load(); }, { immediate: true, flush: 'sync' });
watch(() => session.user?.id, () => { reset(); clearPrivateState(); error.value = ''; }, { flush: 'sync' });
watch(scopeId, async (nextId) => {
  metadataController?.abort();
  columns.value = []; metadataError.value = ''; metadataLoading.value = false;
  if (!nextId || !session.user) return;
  const controller = new AbortController(), actor = session.user.id;
  metadataController = controller; metadataLoading.value = true;
  try {
    const data = await metadata(nextId, false, controller.signal);
    if (!controller.signal.aborted && actor === session.user?.id && nextId === scopeId.value) columns.value = data;
  } catch (cause) {
    if (!controller.signal.aborted && actor === session.user?.id && nextId === scopeId.value) metadataError.value = errorMessage(cause);
  } finally {
    if (!controller.signal.aborted && actor === session.user?.id && nextId === scopeId.value) metadataLoading.value = false;
  }
}, { immediate: true });
function example(text: string) { if (!blocked.value && !saving.value) question.value = text; }
onBeforeRouteLeave(() => { metadataController?.abort(); reset(); clearPrivateState(); });
onBeforeUnmount(() => { metadataController?.abort(); reset(); clearPrivateState(); });
</script>

<template>
  <section class="consumer-ask">
    <div class="ask-main">
      <div class="ask-content-head">
        <div class="ask-title"><span class="ask-title-dot"></span><h1>智能问数</h1><span v-if="conversation" class="ask-conversation-name">/ {{ conversation.title }}</span></div>
        <button type="button" class="ask-reload" aria-label="重新加载" :disabled="loading || busy || !!saving" @click="load"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 8a8 8 0 1 0 1 7m-1-7V3m0 5h-5"/></svg>刷新</button>
      </div>
      <div v-if="error && !saveTarget" class="ask-global-error"><el-alert :title="error" type="error" :closable="false" show-icon /><RouterLink v-if="recoveryId" :to="'/ask/' + encodeURIComponent(recoveryId)">查看已创建的会话</RouterLink></div>
      <div class="ask-thread">
        <div v-if="!id" class="ask-welcome">
          <div class="ask-welcome-badge">D<span>·</span></div>
          <p class="ask-welcome-kicker">DATALENS · AI DATA ASSISTANT</p>
          <h2>你好，今天想了解什么数据？</h2>
          <p class="ask-welcome-desc">用自然语言提出业务问题，快速获得可核对的分析结果。</p>
          <div class="ask-steps"><span><i>1</i> 选择分析空间</span><b>→</b><span><i>2</i> 描述你的问题</span><b>→</b><span><i>3</i> 查看并保存结果</span></div>
          <div v-if="!loading && !subjects.length" class="ask-setup-guide">
            <div class="ask-setup-icon" aria-hidden="true">!</div><div><strong>暂无可用分析空间</strong><p>请先关联 StarRocks 数据源、配置字段权限，并发布业务指标；完成后即可在这里提问。</p>
              <div v-if="session.user?.role === 'SUPER_ADMIN'" class="ask-setup-links"><RouterLink to="/sources">配置数据源</RouterLink><RouterLink to="/subjects">创建分析空间</RouterLink><RouterLink to="/semantics">发布业务指标</RouterLink></div>
              <p v-else>请联系系统管理员为你的账号开通分析空间。</p>
            </div>
          </div>
          <div class="ask-inspiration">
            <div class="ask-inspiration-head"><strong>试试这样提问</strong><span>示例问法 · 需使用已授权的业务口径</span></div>
            <div class="ask-inspiration-grid">
              <button v-for="(item,index) in exampleQuestions" :key="item" type="button" :disabled="blocked || !!saving" @click="example(item)"><span class="ask-example-icon" :class="'example-' + index">{{ ['↗','▥','⌁','≡'][index] }}</span><span>{{ item }}</span><span class="ask-example-arrow">↗</span></button>
            </div>
          </div>
          <div v-if="!loading && subjects.length" class="ask-welcome-footer"><span class="ask-status-point"></span>当前可用 {{ subjects.length }} 个分析空间 · 从下方输入问题开始</div>
        </div>
        <p v-else-if="loading && !conversation" class="ask-loading" role="status">正在读取会话与授权范围…</p>
        <div v-else-if="conversation && !conversation.turns.length" class="ask-welcome"><div class="ask-welcome-badge">D<span>·</span></div><h2>开始这次数据分析</h2><p class="ask-welcome-desc">请描述指标和时间范围，系统将在当前授权空间内分析。</p></div>
        <ol v-if="conversation" class="ask-turns" aria-label="会话记录">
          <li v-for="turn in conversation.turns" :key="turn.id" class="ask-turn" :data-turn-id="turn.id">
            <div class="ask-user-message"><div class="ask-user-bubble"><span class="ask-user-glyph" aria-hidden="true">♙</span>{{ turn.question }}</div><span class="ask-turn-time">{{ turn.createdAt }}</span></div>
            <div class="ask-assistant-answer">
              <div class="ask-assistant-avatar" aria-hidden="true">D<span>·</span></div>
              <div class="ask-answer-content">
                <p class="ask-answer-intro">{{ ['READY','NO_DATA'].includes(turn.status) ? '已为你完成数据分析。' : '正在处理你的数据问题。' }}</p>
                <p class="ask-answer-status" :class="{ pending: isTurnPending(turn.status) }">{{ turnStatusLabels[turn.status] }}</p>
                <p v-if="isTurnPending(turn.status)" class="ask-answer-subtext" role="status">正在检查已发布指标与访问权限。离开页面不会自动取消服务器上的查询。</p>
                <el-alert v-if="turn.status === 'CLARIFICATION_REQUIRED'" :title="turn.clarification || '请补充具体指标和时间范围后继续提问。'" type="info" :closable="false" />
                <el-alert v-if="turn.status === 'FAILED' || turn.status === 'INTERRUPTED'" :title="'本轮未获得可用结果' + (turn.errorCode ? '：' + turn.errorCode : '')" type="error" :closable="false" />
                <el-alert v-if="turn.status === 'NO_DATA'" title="查询成功，但没有匹配数据。不会据此生成趋势、总额或原因说明。" type="info" :closable="false" />
                <div v-if="turn.spec && ['READY','NO_DATA'].includes(turn.status)" class="ask-understanding">
                  <button class="ask-understanding-title" type="button" :aria-expanded="understandingOpen" @click="understandingOpen = !understandingOpen"><span class="ask-verified" aria-hidden="true">✓</span><strong>分析理解</strong><span class="ask-collapse-icon">{{ understandingOpen ? '⌃' : '⌄' }}</span></button>
                  <div v-show="understandingOpen" class="ask-understanding-chips"><span><i>▥</i> 指标：{{ turn.metricDescription || '已发布业务口径' }}</span><span><i>▢</i> 时间：<span>{{ describeTimeRule(turn.timeRule) }}</span></span><span><i>◇</i> 维度：{{ turn.spec.dimension || '整体汇总' }}</span></div>
                </div>
                <el-alert v-if="turn.truncated && !results[turn.id]" title="结果已截断，仅代表返回的部分数据；不计算总额或占比。" type="warning" :closable="false" />
                <el-alert v-if="resultErrors[turn.id]" :title="resultErrors[turn.id]!" type="error" :closable="false" />
                <AnalysisResult v-if="results[turn.id]" :turn="turn" :result="results[turn.id]!">
                  <template #actions>
                    <el-button v-if="!turn.chartId" plain class="ask-chart-action" aria-label="保存图表" :disabled="blocked || !!saving" @click="openSave(turn)">☆ 保存图表</el-button>
                    <RouterLink v-else class="ask-chart-action-link" :to="'/charts/' + encodeURIComponent(turn.chartId)">打开图表 · 分享 / 导出</RouterLink>
                  </template>
                </AnalysisResult>
                <el-button v-else-if="['READY','NO_DATA'].includes(turn.status)" class="ask-preview-button" :loading="previewLoading === turn.id" :disabled="blocked || !!previewLoading" @click="preview(turn)">查看结果预览</el-button>
                <div v-if="turn.chartId" class="ask-saved-message"><span class="ask-status-point"></span>已保存为私有图表 <RouterLink :to="'/charts/' + encodeURIComponent(turn.chartId)">打开已保存图表 →</RouterLink></div>
                <div v-if="['READY','NO_DATA'].includes(turn.status) && results[turn.id]" class="ask-follow-ups">
                  <strong>你可以继续问</strong>
                  <div class="ask-follow-up-list"><button v-for="item in ['改成上个月','按天查看趋势','只看指定地区','对比上一周期']" :key="item" type="button" :disabled="blocked || !!saving" @click="example(item)">{{ item }}</button></div>
                  <p>推荐问法仅填入输入框；若当前指标不支持该条件，系统会请求澄清，不会擅自改写 SQL。</p>
                </div>
              </div>
            </div>
          </li>
        </ol>
      </div>
      <div v-if="!id || conversation" class="ask-dock">
        <div v-if="!id" class="ask-mobile-scope"><label for="mobile-ask-subject">分析空间</label><select id="mobile-ask-subject" v-model="subjectId" aria-label="移动端分析空间" :disabled="loading || busy || !!saving || !subjects.length"><option value="">{{ subjects.length ? '请选择授权的分析空间' : '暂无可用分析空间' }}</option><option v-for="subject in subjects" :key="subject.id" :value="subject.id">{{ subject.name }}</option></select></div>
        <p v-if="needsReload" class="ask-reload-warning">会话状态已变化，请重新加载后继续。<button type="button" @click="load">重新加载会话</button></p>
        <form class="ask-compose" @submit.prevent="ask">
          <label class="sr-only" for="ask-question">问数问题</label>
          <textarea id="ask-question" v-model="question" maxlength="4000" rows="2" :disabled="blocked || !!saving || (!id && !subjects.length)" required :placeholder="conversation?.turns.length ? '继续追问，例如：改成上个月' : subjects.length ? '描述你的业务问题，例如：最近 30 天各地区销售额趋势…' : '请先配置分析空间，再开始提问'" @keydown="keydown" @compositionstart="composing = true" @compositionend="composing = false"/>
          <div class="ask-compose-footer"><span>{{ busy || pending ? '正在处理，请勿重复提交' : 'Enter 发送 · Shift + Enter 换行' }}</span><el-button native-type="submit" type="primary" :loading="busy || pending" :disabled="blocked || !!saving || !question.trim() || (!id && !subjectId)">{{ id ? '提交问题' : '开始分析' }}<span aria-hidden="true"> ↗</span></el-button></div>
        </form>
        <p class="ask-compose-disclaimer">回答只基于授权业务数据。图表保存后重新查询最新数据；请勿输入密码或密钥。</p>
      </div>
    </div>
    <aside class="ask-right" aria-label="数据与分析辅助">
      <section class="ask-side-card"><h2><span class="ask-side-symbol">▤</span> 分析空间</h2><label class="sr-only" for="ask-subject">分析空间</label><select id="ask-subject" v-model="subjectId" aria-label="分析空间" :disabled="loading || !!id || busy || !!saving" :title="id ? '当前会话的数据范围不可修改，请新建问数切换空间' : undefined"><option value="">{{ loading ? '正在加载…' : subjects.length ? '请选择分析空间' : '尚未配置分析空间' }}</option><option v-for="subject in subjects" :key="subject.id" :value="subject.id">{{ subject.name }}</option></select><p v-if="id" class="ask-side-note">会话已绑定当前数据范围，新建问数可更换。</p></section>
      <section class="ask-side-card"><div class="ask-side-heading"><h2><span class="ask-side-symbol">▦</span> 可用数据</h2><RouterLink to="/subjects">查看全部 ›</RouterLink></div>
        <p v-if="!scopeId" class="ask-side-placeholder">选择分析空间后，可查看当前获准访问的数据。</p>
        <p v-else-if="metadataLoading" class="ask-side-placeholder">正在读取授权元数据…</p>
        <div v-else-if="metadataError" class="ask-side-placeholder"><p>暂时无法读取数据范围：{{ metadataError }}</p><RouterLink to="/subjects">查看分析空间权限</RouterLink></div>
        <p v-else-if="!availableTables.length" class="ask-side-placeholder">该空间暂时没有可用的字段，请联系管理员完成采集和授权。</p>
        <ul v-else class="ask-table-list"><li v-for="(table,index) in availableTables" :key="table"><span class="ask-table-symbol" :class="'table-' + index">▤</span><div><strong>{{ table }}</strong><span>{{ columns.filter(col => col.tableName === table).length }} 个授权字段</span></div><RouterLink to="/subjects" :aria-label="'查看 ' + table + ' 的授权字段'">›</RouterLink></li></ul>
      </section>
      <section class="ask-side-card ask-guide"><div class="ask-side-heading"><h2><span class="ask-side-symbol">ⓘ</span> 使用提示</h2></div><ul><li>用自然语言描述指标和时间范围。</li><li>只能查询管理员已授权的数据。</li><li>支持结果表格、图表与多轮追问。</li><li>保存图表后，可按权限分享或导出。</li></ul><button type="button" @click="showExamples = !showExamples">{{ showExamples ? '收起示例问题' : '查看示例问题 ›' }}</button><div v-if="showExamples" class="ask-side-examples"><button v-for="item in exampleQuestions.slice(0, 3)" :key="item" type="button" :disabled="blocked || !!saving" @click="example(item)">{{ item }}</button></div></section>
      <RouterLink v-if="!subjects.length && !loading && session.user?.role === 'SUPER_ADMIN'" class="ask-side-setup" to="/sources">＋ 配置第一个数据源</RouterLink>
    </aside>
    <el-dialog v-if="saveTurn" :model-value="true" title="保存图表" width="min(520px, 92vw)" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving" @update:model-value="closeSave">
      <form class="save-chart-form" @submit.prevent="save(saveTurn!)"><el-alert v-if="error" :title="error" type="error" :closable="false" /><label>图表名称<el-input v-model="saveNames[saveTurn.id]" aria-label="图表名称" maxlength="120" :disabled="!!saving" autofocus placeholder="为这次分析命名"/></label><dl><dt>保存类型</dt><dd>{{ saveTurn.spec ? chartLabels[saveTurn.spec.type] : '未提供' }}</dd><dt>时间规则</dt><dd>{{ describeTimeRule(saveTurn.timeRule) }}</dd><dt>指标口径</dt><dd>{{ saveTurn.metricDescription || '未提供' }}</dd></dl><p class="save-private-note">默认仅自己可见。保存查询定义和图表配置，打开图表时重新查询；共享和导出权限在图表详情中单独设置。</p><div class="dialog-actions"><el-button :disabled="!!saving" @click="closeSave">取消</el-button><el-button native-type="submit" type="primary" :loading="!!saving" :disabled="blocked || !!saving || !saveNames[saveTurn.id]?.trim()">确认保存私有图表</el-button></div></form>
    </el-dialog>
  </section>
</template>
<style scoped>
.sr-only{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}
.consumer-ask{display:grid;grid-template-columns:minmax(0,1fr) 320px;min-height:0;height:100%;color:#263654;background:#fff}
.ask-main{min-width:0;min-height:0;display:flex;flex-direction:column;padding:0 30px}
.ask-content-head{display:flex;justify-content:space-between;align-items:center;gap:15px;padding:19px 4px 8px;flex-shrink:0}.ask-title{display:flex;align-items:center;gap:10px;min-width:0}.ask-title-dot{width:8px;height:8px;background:#2165eb;border-radius:50%}.ask-title h1{font-size:15px;font-weight:620;white-space:nowrap}.ask-conversation-name{min-width:0;max-width:48vw;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#95a2b5;font-size:12px}.ask-reload{display:flex;align-items:center;gap:4px;border:0;background:none;font-size:11px;color:#8797ad;cursor:pointer;padding:6px}.ask-reload svg{width:15px;height:15px;fill:none;stroke:currentColor;stroke-width:1.7;stroke-linecap:round;stroke-linejoin:round}.ask-reload:hover{color:#235fe2}.ask-global-error{display:grid;gap:6px;flex-shrink:0}.ask-global-error>a{color:#195dd9;font-size:12px}
.ask-thread{flex:1;min-height:0;overflow-y:auto;padding:2px 2px 22px;scrollbar-width:thin}
.ask-welcome{display:flex;flex-direction:column;align-items:center;justify-content:center;min-height:100%;max-width:820px;margin:0 auto;padding:30px 15px 40px;text-align:center}
.ask-welcome-badge,.ask-assistant-avatar{display:grid;place-items:center;background:linear-gradient(145deg,#3c7ff8,#145ad8);color:#fff;font-weight:780;box-shadow:0 6px 18px #1c64eb1d;position:relative}.ask-welcome-badge{width:58px;height:58px;border-radius:18px;font-size:36px;letter-spacing:-3px}.ask-welcome-badge span,.ask-assistant-avatar span{font-size:14px;position:absolute;bottom:4px;right:8px}.ask-welcome-kicker{font-size:10px;letter-spacing:1.8px;color:#5280d6;font-weight:650;margin-top:25px}.ask-welcome h2{font-size:29px;font-weight:640;letter-spacing:-.7px;color:#1f3354;margin-top:12px}.ask-welcome-desc{font-size:13px;color:#7f8ea4;line-height:1.9;margin-top:14px}.ask-steps{display:flex;align-items:center;justify-content:center;flex-wrap:wrap;gap:14px;margin-top:28px;font-size:12px;color:#5c6e88}.ask-steps span{display:flex;align-items:center;gap:7px}.ask-steps i{font-style:normal;background:#eaf2ff;color:#2c6fea;border-radius:50%;width:20px;height:20px;display:grid;place-items:center;font-size:11px}.ask-steps>b{font-weight:400;color:#b3c2d6}
.ask-setup-guide{width:100%;text-align:left;margin-top:26px;padding:20px 22px;background:#f5f8fe;border:1px solid #dde8fa;border-radius:12px;display:flex;gap:15px;align-items:flex-start}.ask-setup-icon{width:25px;height:25px;flex-shrink:0;border-radius:50%;background:#e4edff;display:grid;place-items:center;color:#1e61d4;font-weight:700}.ask-setup-guide strong{font-size:14px}.ask-setup-guide p{font-size:12px;color:#78889d;line-height:1.8;margin-top:6px}.ask-setup-links{display:flex;gap:13px;flex-wrap:wrap;margin-top:14px}.ask-setup-links a{font-size:12px;color:#1e62d8;border:1px solid #bed2fa;border-radius:6px;background:white;padding:7px 10px}.ask-setup-links a:hover{background:#eaf3ff}
.ask-inspiration{width:100%;margin-top:32px;text-align:left}.ask-inspiration-head{display:flex;align-items:baseline;gap:14px}.ask-inspiration-head strong{font-size:14px;color:#354b6c}.ask-inspiration-head span{font-size:11px;color:#a1aab9}.ask-inspiration-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px;margin-top:15px}.ask-inspiration-grid button{min-width:0;display:flex;align-items:center;gap:12px;border:1px solid #e3e9f3;border-radius:10px;background:#fff;text-align:left;padding:14px;min-height:62px;cursor:pointer;color:#45607e;font-size:12px}.ask-inspiration-grid button:hover:not(:disabled){border-color:#8badf1;background:#f8fbff}.ask-inspiration-grid button:disabled{opacity:.6;cursor:default}.ask-example-icon{display:grid;place-items:center;width:32px;height:32px;flex:0 0 32px;border-radius:9px;background:#e9f1ff;color:#276be7;font-weight:700;font-size:18px}.example-1{background:#eef8f2;color:#20a276}.example-2{background:#f5f0ff;color:#8966d9}.example-3{background:#fff4e8;color:#db973b}.ask-example-arrow{margin-left:auto;color:#aec1da;font-size:16px}.ask-welcome-footer{display:flex;gap:8px;align-items:center;margin-top:30px;color:#8f9cb0;font-size:11px}.ask-status-point{width:7px;height:7px;background:#22b881;border-radius:50%;display:inline-block;flex-shrink:0}.ask-loading{font-size:13px;color:#8996aa;padding:35px}
.ask-turns{list-style:none;margin:0;padding:8px 0 14px;display:grid;gap:28px;max-width:1100px}.ask-turn{display:flex;flex-direction:column;gap:20px;min-width:0}.ask-user-message{display:flex;flex-direction:column;align-items:flex-end;gap:6px}.ask-user-bubble{display:flex;gap:11px;align-items:center;background:#eaf2ff;border-radius:28px;padding:13px 18px;max-width:88%;font-size:13px;color:#1e365b;overflow-wrap:anywhere;line-height:1.6}.ask-user-glyph{font-size:20px;color:#556d94}.ask-turn-time{font-size:10px;color:#9babbe;padding-right:6px}
.ask-assistant-answer{display:flex;gap:16px;min-width:0}.ask-assistant-avatar{width:34px;height:34px;flex:0 0 34px;border-radius:50%;font-size:21px;letter-spacing:-2px}.ask-assistant-avatar span{right:5px;bottom:1px;font-size:9px}.ask-answer-content{min-width:0;flex:1;display:grid;gap:14px}.ask-answer-intro{font-size:15px;color:#273d60;font-weight:560;padding-top:5px}.ask-answer-status{font-size:11px;color:#8999ae;margin-top:-10px}.ask-answer-status.pending{color:#2162d7}.ask-answer-subtext{font-size:12px;color:#8190a6;line-height:1.8}.ask-understanding{border:1px solid #dce6f5;background:#fafcff;border-radius:10px;padding:0 16px}.ask-understanding-title{display:flex;align-items:center;gap:10px;min-height:43px;width:100%;border:0;background:none;text-align:left;cursor:pointer;font-size:13px;color:#2d4464}.ask-verified{display:grid;place-items:center;width:19px;height:19px;border:1.5px solid #24bb86;border-radius:50%;color:#13ad78;font-size:13px;font-weight:700}.ask-collapse-icon{margin-left:auto;font-size:19px;color:#8fa1b9}.ask-understanding-chips{display:flex;flex-wrap:wrap;gap:9px 12px;padding:0 0 13px}.ask-understanding-chips>span{display:inline-flex;align-items:center;gap:6px;padding:8px 10px;border-radius:7px;border:1px solid #e0e8f6;background:#fff;font-size:11px;color:#586b84;max-width:100%;overflow-wrap:anywhere}.ask-understanding-chips i{font-style:normal;color:#256ce7;font-size:15px}.ask-preview-button{justify-self:start}.ask-saved-message{font-size:12px;color:#63816f;display:flex;align-items:center;gap:7px}.ask-saved-message>a{color:#2365df;margin-left:5px}.ask-follow-ups{display:grid;gap:11px;padding-top:2px}.ask-follow-ups>strong{font-size:13px;color:#344863}.ask-follow-up-list{display:flex;gap:10px;flex-wrap:wrap}.ask-follow-up-list button{border:1px solid #dce5f1;background:#fff;color:#415978;border-radius:22px;padding:9px 13px;font-size:11px;cursor:pointer}.ask-follow-up-list button:hover:not(:disabled){border-color:#93b5f5;color:#2264df;background:#f7faff}.ask-follow-ups>p{font-size:10px;color:#acb6c3;line-height:1.6}
.ask-mobile-scope{display:none}.ask-dock{flex-shrink:0;padding:8px 0 17px}.ask-compose{border:1px solid #a7c2f5;border-radius:10px;background:white;box-shadow:0 2px 13px #25467c08}.ask-compose:focus-within{border-color:#3779f4;box-shadow:0 0 0 3px #2a6be30e}.ask-compose textarea{border:0;outline:none;resize:vertical;max-height:170px;min-height:58px;width:100%;padding:13px 15px 1px;line-height:1.7;font-size:13px;color:#2d4162;background:transparent;display:block}.ask-compose textarea::placeholder{color:#9eabc1}.ask-compose textarea:disabled{color:#a4aec0}.ask-compose-footer{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:7px 9px}.ask-compose-footer>span{font-size:10px;color:#99a7ba}.ask-compose-footer .el-button{min-width:106px;height:36px;border-radius:7px;font-size:12px}.ask-compose-disclaimer{margin-top:9px;font-size:10px;text-align:center;color:#a5b0bd;line-height:1.5}.ask-reload-warning{margin:0 0 9px;font-size:12px;color:#a46d2a}.ask-reload-warning button{border:0;background:none;color:#185fd7;cursor:pointer}
.ask-right{min-width:0;border-left:1px solid #f0f2f7;padding:29px 16px 18px 12px;overflow:auto;background:#fff;display:flex;flex-direction:column;gap:18px;scrollbar-width:thin}
.ask-side-card{background:linear-gradient(160deg,#fbfcff,#f9fbff);border:1px solid #dfe7f3;border-radius:12px;padding:17px 18px;box-shadow:0 3px 12px #234a7f08}.ask-side-card h2{display:flex;align-items:center;gap:10px;font-size:15px;color:#263a59;font-weight:630}.ask-side-symbol{color:#2165e6;font-size:19px;min-width:20px;text-align:center}.ask-side-card select{margin-top:18px;display:block;width:100%;height:40px;padding:0 12px;border:1px solid #d5e0ee;border-radius:8px;color:#425979;background:#fff;font-size:12px}.ask-side-card select:disabled{background:#f6f8fc;cursor:default}.ask-side-note{margin-top:9px;font-size:10px;color:#94a0b1;line-height:1.6}.ask-side-heading{display:flex;justify-content:space-between;align-items:center;gap:8px}.ask-side-heading>a{font-size:11px;white-space:nowrap;color:#2564df}.ask-side-placeholder{font-size:12px;color:#8c9bac;line-height:1.8;padding-top:15px}.ask-side-placeholder a{color:#2564df}.ask-table-list{list-style:none;padding:8px 0 0;margin:0}.ask-table-list li{display:flex;align-items:center;gap:11px;border-bottom:1px solid #ecf0f7;padding:11px 0;min-height:51px}.ask-table-list li:last-child{border-bottom:0}.ask-table-symbol{display:grid;place-items:center;width:31px;height:31px;background:#edf3ff;border-radius:8px;color:#316be7;font-size:18px;flex:0 0 31px}.ask-table-symbol.table-1{background:#f1f7ff;color:#426eae}.ask-table-symbol.table-2{background:#edf8f4;color:#209d7c}.ask-table-symbol.table-3{background:#fbf3ff;color:#9366bb}.ask-table-list li>div{min-width:0;display:grid;gap:4px;flex:1}.ask-table-list li strong{font-size:12px;color:#4a5d76;overflow:hidden;white-space:nowrap;text-overflow:ellipsis;font-weight:560}.ask-table-list li>div span{font-size:10px;color:#9ca9b9}.ask-table-list li>a{font-size:20px;color:#a8b5c8}
.ask-guide ul{padding-left:16px;margin:13px 0 16px;display:grid;gap:10px}.ask-guide li{font-size:11px;line-height:1.7;color:#60728a}.ask-guide>button{background:transparent;border:0;color:#2064e6;font-size:12px;cursor:pointer;padding:5px 0}.ask-side-examples{display:grid;gap:8px;border-top:1px solid #e6ecf4;padding-top:10px;margin-top:5px}.ask-side-examples button{border:0;background:#f0f5ff;padding:9px;border-radius:6px;text-align:left;font-size:11px;color:#3365af;cursor:pointer}.ask-side-setup{display:block;padding:12px 16px;border:1px dashed #91b5ef;border-radius:9px;text-align:center;color:#2466df;font-size:12px}
.save-chart-form{display:grid;gap:20px}.save-chart-form>label{display:grid;gap:9px;font-size:13px}.save-chart-form dl{display:grid;grid-template-columns:65px minmax(0,1fr);gap:12px 16px;margin:0;font-size:12px;line-height:1.8}.save-chart-form dt{color:#8b96a4}.save-chart-form dd{margin:0;color:#596b80;overflow-wrap:anywhere}.save-private-note{padding:13px 15px;background:#f6f8fb;border-radius:7px;font-size:12px;color:#7d8ca0;line-height:1.8}
@media(max-width:1420px){.consumer-ask{grid-template-columns:minmax(0,1fr) 286px}.ask-main{padding:0 23px}.ask-right{padding:23px 10px}}
@media(max-width:1100px){.consumer-ask{grid-template-columns:minmax(0,1fr) 245px}.ask-right{padding:16px 8px;gap:12px}.ask-side-card{padding:13px}.ask-main{padding:0 16px}.ask-inspiration-grid{grid-template-columns:1fr 1fr}.ask-follow-up-list{gap:7px}}
@media(max-width:920px){.ask-mobile-scope{display:flex;align-items:center;gap:10px;font-size:12px;color:#63758e;padding:6px 1px 12px}.ask-mobile-scope select{min-width:0;max-width:320px;flex:1;padding:8px 11px;border:1px solid #d6e4f7;border-radius:7px;background:#fff;color:#425a7a}.consumer-ask{grid-template-columns:1fr}.ask-right{display:none}.ask-main{padding:0 20px}.ask-welcome{max-width:700px}.ask-thread{padding-bottom:15px}}
@media(max-width:620px){.ask-main{padding:0 10px}.ask-content-head{padding:14px 2px 7px}.ask-welcome{padding:25px 3px}.ask-welcome h2{font-size:23px}.ask-welcome-desc{font-size:12px}.ask-steps{gap:7px;font-size:10px}.ask-steps i{width:17px;height:17px}.ask-inspiration-grid{grid-template-columns:1fr}.ask-setup-guide{padding:14px}.ask-answer-content{gap:11px}.ask-assistant-answer{gap:8px}.ask-user-bubble{max-width:100%;font-size:12px}.ask-understanding-chips>span{width:100%;font-size:10px}.ask-compose-footer>span{display:none}.ask-compose-footer{justify-content:flex-end}.ask-follow-up-list button{padding:7px 10px}}
</style>
