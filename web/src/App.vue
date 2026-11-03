<script setup lang="ts">
import { useRoute } from 'vue-router';
const route = useRoute();
const navigation = [
  { path: '/', label: '工程概览', symbol: '◫' },
  { path: '/ask', label: '智能问数', symbol: '?' },
  { path: '/charts/mine', label: '我的图表', symbol: '▥' },
  { path: '/charts/shared', label: '共享给我', symbol: '↗' },
  { path: '/charts/public', label: '全员共享', symbol: '◎' },
];
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar" aria-label="主导航">
      <RouterLink class="brand" to="/" aria-label="DataLens 首页">
        <span class="brand-mark">D</span><strong>DataLens</strong>
      </RouterLink>
      <div class="nav-caption">工作空间</div>
      <nav>
        <RouterLink v-for="item in navigation" :key="item.path" :to="item.path" class="nav-item" :class="{ active: route.path === item.path }" :aria-current="route.path === item.path ? 'page' : undefined">
          <span class="nav-symbol" aria-hidden="true">{{ item.symbol }}</span>{{ item.label }}
        </RouterLink>
      </nav>
      <div class="sidebar-bottom">
        <RouterLink to="/settings" class="nav-item" :class="{ active: route.path === '/settings' }"><span class="nav-symbol" aria-hidden="true">⚙</span>系统管理</RouterLink>
        <p>独立部署 · 只读分析</p>
      </div>
    </aside>
    <div class="workspace">
      <header class="topbar">
        <span>{{ route.meta.title }}</span>
        <span class="stage-label">M00 · 工程基础</span>
      </header>
      <main id="main-content"><RouterView /></main>
    </div>
  </div>
</template>
