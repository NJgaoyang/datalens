<script setup lang="ts">
import { ref } from 'vue';
import { ElMessage } from 'element-plus';
import { session, logout } from './api/auth';
import { errorMessage } from './api/http';
import { useRouter, useRoute } from 'vue-router';
const route = useRoute();
const router = useRouter(); const busy = ref(false);
async function signOut() {
  if (busy.value) return;
  busy.value = true;
  try { await logout(); await router.replace('/login'); }
  catch (error) { ElMessage.error(errorMessage(error)); if (!session.user) await router.replace('/login'); }
  finally { busy.value = false; }
}
const navigation = [
  { path: '/workspace', label: '工作台', symbol: '◫' },
  { path: '/subjects', label: '业务主题', symbol: '▦' },
  { path: '/semantics', label: '业务语义与指标', symbol: '≡' },
  { path: '/queries', label: '安全 SQL 查询', symbol: '⌘' },
  { path: '/ask', label: '智能问数', symbol: '?' },
  { path: '/charts/mine', label: '我的图表', symbol: '▥' },
  { path: '/charts/shared', label: '共享给我', symbol: '↗' },
  { path: '/charts/public', label: '全员共享', symbol: '◎' },
];
</script>

<template>
  <RouterView v-if="route.meta.public" />
  <div v-else-if="session.user || route.meta.anonymous" class="app-shell">
    <aside class="sidebar" aria-label="主导航">
      <RouterLink class="brand" :to="session.user ? '/workspace' : '/'" aria-label="DataLens 首页">
        <span class="brand-mark">D</span><strong>DataLens</strong>
      </RouterLink>
      <div class="nav-caption">工作空间</div>
      <nav>
        <RouterLink v-for="item in navigation" :key="item.path" :to="item.path" class="nav-item" :class="{ active: route.path === item.path }" :aria-current="route.path === item.path ? 'page' : undefined">
          <span class="nav-symbol" aria-hidden="true">{{ item.symbol }}</span>{{ item.label }}
        </RouterLink>
      </nav>
      <div class="sidebar-bottom">
        <div v-if="session.user?.role === 'SUPER_ADMIN'" class="nav-caption">系统管理</div>
        <RouterLink v-if="session.user?.role === 'SUPER_ADMIN'" to="/charts/admin" class="nav-item"><span class="nav-symbol" aria-hidden="true">▥</span>全部图表</RouterLink>
        <RouterLink v-if="session.user?.role === 'SUPER_ADMIN'" to="/audit" class="nav-item"><span class="nav-symbol" aria-hidden="true">≡</span>查询与操作日志</RouterLink>
        <RouterLink to="/" class="nav-item"><span class="nav-symbol" aria-hidden="true">◫</span>系统状态</RouterLink>
        <RouterLink v-if="session.user?.role === 'SUPER_ADMIN'" to="/settings" class="nav-item" :class="{ active: route.path === '/settings' }"><span class="nav-symbol" aria-hidden="true">⚙</span>用户管理</RouterLink>
        <RouterLink v-if="session.user?.role === 'SUPER_ADMIN'" to="/sources" class="nav-item" :class="{ active: route.path === '/sources' }"><span class="nav-symbol" aria-hidden="true">▤</span>数据源管理</RouterLink>
        <RouterLink v-if="session.user?.role === 'SUPER_ADMIN'" to="/model" class="nav-item" :class="{ active: route.path === '/model' }"><span class="nav-symbol" aria-hidden="true">◇</span>模型配置</RouterLink>
        <p>独立部署 · 只读分析</p>
      </div>
    </aside>
    <div class="workspace">
      <header class="topbar">
        <span>{{ route.meta.title }}</span>
        <div v-if="session.user" class="account-actions"><span>{{ session.user.username }}</span><RouterLink to="/account/password">修改密码</RouterLink><el-button size="small" :loading="busy" @click="signOut">退出登录</el-button></div><RouterLink v-else to="/login">登录</RouterLink>
      </header>
      <main id="main-content"><RouterView /></main>
    </div>
  </div>
</template>

