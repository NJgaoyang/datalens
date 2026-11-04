<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { useRoute, useRouter } from 'vue-router';
import { session, logout } from './api/auth';
import { errorMessage } from './api/http';
import { listConversations, type ConversationSummary } from './api/conversations';
import { clearHistoryVersion } from './conversation/historySignal';
import './workspace.css';

const route = useRoute(), router = useRouter();
const isAdmin = computed(() => session.user?.role === 'SUPER_ADMIN');
const asking = computed(() => route.path === '/ask' || route.path.startsWith('/ask/'));
const nav = [
  { label: '智能问数', path: '/ask', icon: 'M4 5.5h16v12H8l-4 3v-15Zm4 4h8m-8 4h5' },
  { label: '我的图表', path: '/charts/mine', icon: 'M4 19V5m0 14h16M8 16v-4m5 4V7m5 9v-6' },
  { label: '图表中心', path: '/charts/shared', icon: 'M4 4h7v7H4zm9 0h7v7h-7zM4 13h7v7H4zm9 0h7v7h-7z' },
  { label: '数据源', path: '/sources', icon: 'M4 6c0-4 16-4 16 0s-16 4-16 0Zm0 0v12c0 4 16 4 16 0V6M4 12c0 4 16 4 16 0' },
  { label: '分析空间', path: '/subjects', icon: 'm12 2 9 5-9 5-9-5 9-5Zm-9 10 9 5 9-5M3 17l9 5 9-5' },
];
const management = [
  { label: '数据源管理', path: '/sources' },
  { label: '业务主题', path: '/subjects' },
  { label: '业务语义与指标', path: '/semantics' },
  { label: '用户管理', path: '/settings' },
  { label: '模型配置', path: '/model' },
  { label: 'SQL 工作区', path: '/queries' },
  { label: '图表管理', path: '/charts/admin' },
  { label: '查询与操作日志', path: '/audit' },
  { label: '系统状态', path: '/system' },
];
const histories = ref<ConversationSummary[]>([]);
const historyError = ref(''), historyLoading = ref(false), historyOpen = ref(true), showAllHistory = ref(false);
const managementOpen = ref(false), query = ref(''), searching = ref(false), busy = ref(false);
const accountMenu = ref<HTMLDetailsElement>(), helpMenu = ref<HTMLDetailsElement>();
let historyController: AbortController | undefined;
const matchedHistory = computed(() => histories.value.filter(item => item.title.toLocaleLowerCase().includes(query.value.trim().toLocaleLowerCase())));
const shownHistory = computed(() => showAllHistory.value ? histories.value : histories.value.slice(0, 7));
function isActive(path: string) {
  if (path === '/ask') return asking.value;
  if (path === '/charts/shared') return ['/charts/shared', '/charts/public', '/charts/admin'].includes(route.path);
  if (path === '/charts/mine') return route.path === '/charts/mine' || route.path.startsWith('/charts/') && !['/charts/shared', '/charts/public', '/charts/admin'].includes(route.path);
  return route.path === path;
}
async function refreshHistory() {
  if (!session.user) return;
  const actor = session.user.id;
  historyController?.abort();
  const controller = new AbortController(); historyController = controller;
  histories.value = []; historyError.value = ''; historyLoading.value = true;
  try {
    const value = await listConversations(controller.signal);
    if (!controller.signal.aborted && session.user?.id === actor) histories.value = value;
  } catch (cause) {
    if (!controller.signal.aborted && session.user?.id === actor) historyError.value = errorMessage(cause);
  } finally {
    if (!controller.signal.aborted && session.user?.id === actor) historyLoading.value = false;
  }
}
watch(() => [session.user?.id, route.fullPath], () => {
  if (!session.user) { historyController?.abort(); histories.value = []; historyError.value = ''; query.value = ''; return; }
  void refreshHistory();
}, { immediate: true });
watch(() => route.path, () => {
  managementOpen.value = isAdmin.value && Boolean(route.meta.admin);
  if (accountMenu.value) accountMenu.value.open = false;
  if (helpMenu.value) helpMenu.value.open = false;
  searching.value = false;
}, { immediate: true });
watch(clearHistoryVersion, () => {
  historyController?.abort();
  histories.value = []; historyError.value = ''; historyLoading.value = false;
  searching.value = false; query.value = '';
});
onBeforeUnmount(() => historyController?.abort());
async function signOut() {
  if (busy.value) return;
  busy.value = true;
  try { await logout(); await router.replace('/login'); }
  catch (cause) { ElMessage.error(errorMessage(cause)); if (!session.user) await router.replace('/login'); }
  finally { busy.value = false; }
}
function searchCharts() {
  const value = query.value.trim();
  if (!value) return;
  searching.value = false;
  void router.push({ path: '/charts/mine', query: { search: value } });
}
function dismissSearch() { searching.value = false; query.value = ''; }
</script>
<template>
  <RouterView v-if="route.meta.public" />
  <div v-else-if="session.user" class="lens-shell">
    <a class="skip-link" href="#main-content">跳到主要内容</a>
    <header class="lens-topbar">
      <RouterLink to="/ask" class="lens-brand" aria-label="DataLens 智能问数首页">
        <span class="lens-mark" aria-hidden="true">D</span><strong>DataLens</strong>
      </RouterLink>
      <div class="lens-product"><span class="lens-product-divider"></span><span>{{ asking ? '智能问数' : route.meta.title }}</span></div>
      <div class="lens-top-actions">
        <form class="lens-search" role="search" @submit.prevent="searchCharts">
          <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="10.7" cy="10.7" r="6.5"/><path d="m16 16 5 5"/></svg>
          <input v-model="query" aria-label="搜索图表、分析记录" placeholder="搜索图表、分析记录…" type="search" maxlength="100" autocomplete="off" @focus="searching = true" @keydown.esc="dismissSearch">
          <div v-if="searching && query.trim()" class="lens-search-menu" aria-label="搜索结果">
            <p class="lens-search-label">匹配的会话</p>
            <RouterLink v-for="item in matchedHistory.slice(0, 6)" :key="item.id" :to="'/ask/' + encodeURIComponent(item.id)" @click="dismissSearch">{{ item.title }}</RouterLink>
            <p v-if="!matchedHistory.length" class="lens-search-empty">暂无匹配会话</p>
            <button type="submit">在我的图表中搜索“{{ query.trim() }}”</button>
          </div>
        </form>
        <details ref="helpMenu" class="lens-help"><summary aria-label="使用帮助" title="使用帮助"><svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M9.5 9a2.5 2.5 0 0 1 5 0c0 2-2.5 2.5-2.5 4m0 3h.01"/></svg></summary><div class="lens-help-menu"><strong>如何使用智能问数</strong><p>先选择有权限的分析空间，再用自然语言描述指标与时间范围；查询成功后可保存图表。</p><p>只有已获授权的业务口径才能查询，未授权的数据不会展示。</p><RouterLink to="/subjects" @click="helpMenu && (helpMenu.open = false)">查看我的分析空间</RouterLink></div></details>
        <details ref="accountMenu" class="lens-account"><summary aria-label="账号菜单"><span class="lens-avatar">{{ session.user.username.slice(0, 1).toUpperCase() }}</span><span class="lens-user-name">{{ session.user.username }}</span><span class="lens-chevron">⌄</span></summary><div class="lens-account-menu"><RouterLink to="/account/password">修改密码</RouterLink><button type="button" :disabled="busy" @click="signOut">{{ busy ? '正在退出…' : '退出登录' }}</button></div></details>
      </div>
    </header>
    <div class="lens-body">
      <aside class="lens-sidebar" aria-label="应用导航">
        <RouterLink to="/ask" class="lens-new" aria-label="新建分析"><span aria-hidden="true">＋</span>新建问数</RouterLink>
        <nav class="lens-nav" aria-label="主要功能">
          <RouterLink v-for="item in nav.filter(item => item.path !== '/sources' || isAdmin)" :key="item.path" :to="item.path" :class="['lens-nav-link', { selected: isActive(item.path) }]" :aria-current="isActive(item.path) ? 'page' : undefined">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path :d="item.icon"/></svg><span>{{ item.label }}</span>
          </RouterLink>
        </nav>
        <div class="lens-sidebar-history">
          <div class="lens-section-label"><span>最近会话</span><button type="button" :aria-label="historyOpen ? '收起历史会话' : '展开历史会话'" :aria-expanded="historyOpen" aria-controls="conversation-history" @click="historyOpen = !historyOpen">{{ historyOpen ? '⌃' : '⌄' }}</button></div>
          <aside v-show="historyOpen" id="conversation-history" aria-label="我的会话">
            <p v-if="historyLoading" class="lens-sidebar-muted" role="status">正在读取会话…</p>
            <p v-else-if="historyError" class="lens-sidebar-muted">会话记录暂不可用 <button type="button" @click="refreshHistory">重试</button></p>
            <p v-else-if="!histories.length" class="lens-sidebar-muted">暂无会话，开始你的第一次分析</p>
            <nav v-else aria-label="最近会话">
              <RouterLink v-for="item in shownHistory" :key="item.id" :class="['lens-history-link', { selected: route.path === '/ask/' + item.id }]" :to="'/ask/' + encodeURIComponent(item.id)" :title="item.title"><span class="lens-history-arrow" aria-hidden="true">›</span><span class="lens-history-text">{{ item.title }}</span></RouterLink>
              <button v-if="histories.length > 7" class="lens-history-more" type="button" @click="showAllHistory = !showAllHistory">{{ showAllHistory ? '收起记录' : '查看全部记录' }}</button>
            </nav>
          </aside>
        </div>
        <button v-if="isAdmin" type="button" class="lens-admin-link" aria-label="系统管理" :aria-expanded="managementOpen" aria-controls="management-navigation" @click="managementOpen = !managementOpen"><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="3"/><path d="m10 2-.7 2.5-2 .9L5 4.8 3 8l1.8 1.8v4.4L3 16l2 3.2 2.3-.6 2 .9L10 22h4l.7-2.5 2-.9 2.3.6 2-3.2-1.8-1.8V9.8L21 8l-2-3.2-2.3.6-2-.9L14 2h-4Z"/></svg>系统管理</button>
        <aside v-if="isAdmin && managementOpen" id="management-navigation" class="lens-management" aria-label="系统管理导航"><div class="lens-management-heading"><strong>系统管理</strong><button type="button" aria-label="收起系统管理" @click="managementOpen = false">×</button></div><RouterLink v-for="item in management" :key="item.path" :to="item.path">{{ item.label }}</RouterLink></aside>
      </aside>
      <main id="main-content" class="lens-content" tabindex="-1"><RouterView /></main>
    </div>
  </div>
  <main v-else class="lens-boot" role="status">正在验证登录状态…</main>
</template>