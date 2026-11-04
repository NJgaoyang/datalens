<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { useRoute, useRouter } from 'vue-router';
import { session, logout } from './api/auth';
import { errorMessage } from './api/http';
import './workspace.css';

const route = useRoute(), router = useRouter();
const busy = ref(false), managementOpen = ref(false);
const accountMenu = ref<HTMLDetailsElement>();
const isAdmin = computed(() => session.user?.role === 'SUPER_ADMIN');
const navigation = [
  { path: '/ask', label: '智能问数', short: '问数', icon: 'M4 4h16v12H9l-5 4V4m4 4h8m-8 4h5', active: () => route.path.startsWith('/ask') },
  { path: '/charts/mine', label: '图表中心', short: '图表', icon: 'M4 20V4m0 16h16M8 16v-4m5 4V7m5 9v-6', active: () => route.path.startsWith('/charts') || route.path === '/workspace' },
  { path: '/queries', label: 'SQL 工作区', short: 'SQL', icon: 'm8 6-5 6 5 6m8-12 5 6-5 6m-3-14-2 16', active: () => route.path === '/queries' },
];
const management = [
  { path: '/sources', label: '数据源管理' }, { path: '/subjects', label: '业务主题' },
  { path: '/semantics', label: '业务语义与指标' }, { path: '/model', label: '模型配置' },
  { path: '/settings', label: '用户管理' }, { path: '/charts/admin', label: '全部图表' },
  { path: '/audit', label: '查询与操作日志' }, { path: '/system', label: '系统状态' },
];
watch(() => route.path, () => {
  managementOpen.value = isAdmin.value && (Boolean(route.meta.admin) || ['/subjects', '/semantics'].includes(route.path));
  if (accountMenu.value) accountMenu.value.open = false;
}, { immediate: true });
watch(() => session.user?.id, () => { managementOpen.value = false; if (accountMenu.value) accountMenu.value.open = false; });
async function signOut() {
  if (busy.value) return;
  busy.value = true;
  try { await logout(); await router.replace('/login'); }
  catch (cause) { ElMessage.error(errorMessage(cause)); if (!session.user) await router.replace('/login'); }
  finally { busy.value = false; }
}
</script>

<template>
  <RouterView v-if="route.meta.public" />
  <div v-else-if="session.user" class="lens-shell">
    <a class="skip-link" href="#main-content">跳到主要内容</a>
    <aside class="lens-rail" aria-label="主导航">
      <RouterLink to="/ask" class="lens-logo" aria-label="DataLens 首页">D<span /></RouterLink>
      <nav class="lens-primary-nav">
        <RouterLink v-for="item in navigation" :key="item.path" :to="item.path" class="lens-rail-item" :class="{ active: item.active() }" :aria-label="item.label" :aria-current="item.active() ? 'page' : undefined" :title="item.label">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path :d="item.icon" /></svg><span>{{ item.short }}</span>
        </RouterLink>
      </nav>
      <button v-if="isAdmin" class="lens-rail-item lens-settings" :class="{ active: managementOpen }" type="button" aria-label="系统管理" aria-controls="management-navigation" :aria-expanded="managementOpen" @click="managementOpen = !managementOpen">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m10 3-.7 2.5-2 .9L5 5.8 3 9l1.8 1.8v2.4L3 15l2 3.2 2.3-.6 2 .9L10 21h4l.7-2.5 2-.9 2.3.6 2-3.2-1.8-1.8v-2.4L21 9l-2-3.2-2.3.6-2-.9L14 3h-4Z"/><circle cx="12" cy="12" r="3" /></svg><span>管理</span>
      </button>
    </aside>
    <aside v-if="isAdmin && managementOpen" id="management-navigation" class="lens-management" aria-label="系统管理导航">
      <div class="lens-management-heading"><strong>系统管理</strong><button type="button" aria-label="收起系统管理" @click="managementOpen = false">×</button></div>
      <RouterLink v-for="item in management" :key="item.path" :to="item.path" :aria-current="route.path === item.path ? 'page' : undefined">{{ item.label }}</RouterLink>
    </aside>
    <div class="lens-workspace">
      <header class="lens-topbar">
        <div class="lens-breadcrumb"><strong>DataLens</strong><span>/</span><span>{{ route.meta.title }}</span></div>
        <div class="lens-topbar-right"><span class="lens-readonly">只读分析</span>
          <details ref="accountMenu" class="lens-account" @keydown.esc="accountMenu && (accountMenu.open = false)">
            <summary aria-label="账号菜单"><span class="lens-avatar">{{ session.user.username.slice(0, 1).toUpperCase() }}</span><span>{{ session.user.username }}</span><span aria-hidden="true">⌄</span></summary>
            <div class="lens-account-menu"><RouterLink to="/account/password">修改密码</RouterLink><button type="button" :disabled="busy" @click="signOut">{{ busy ? '正在退出…' : '退出登录' }}</button></div>
          </details>
        </div>
      </header>
      <main id="main-content" class="lens-content" tabindex="-1"><RouterView /></main>
    </div>
  </div>
</template>
