<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue';
import { ApiError } from '../api/http';
import { loadReadiness, loadSystemInfo } from '../api/system';
import type { SystemInfo } from '../api/system';

const info = ref<SystemInfo | null>(null);
const readiness = ref<boolean | null>(null);
const error = ref('');
const requestId = ref('');
const loading = ref(false);
let active: AbortController | undefined;

async function reload() {
  active?.abort();
  const controller = new AbortController();
  active = controller;
  loading.value = true;
  error.value = '';
  requestId.value = '';
  info.value = null;
  readiness.value = null;
  const [system, health] = await Promise.allSettled([
    loadSystemInfo(controller.signal), loadReadiness(controller.signal),
  ]);
  if (controller.signal.aborted) return;
  if (system.status === 'fulfilled') info.value = system.value;
  else {
    error.value = system.reason instanceof Error ? system.reason.message : '系统信息加载失败';
    requestId.value = system.reason instanceof ApiError ? (system.reason.requestId ?? '') : '';
  }
  readiness.value = health.status === 'fulfilled' ? health.value : false;
  loading.value = false;
}

onMounted(reload);
onUnmounted(() => active?.abort());

const stages = [
  ['01', '工程基础', 'M00', '代码结构、依赖版本、MySQL 迁移与自动化测试'],
  ['02', '动态图表平台', 'M01—M05', '登录、只读查询、保存刷新、指定共享与超管管理'],
  ['03', '智能问数', 'M06—M08', '业务语义、模型适配、多轮对话与确认保存'],
  ['04', '上线验收', 'M09—M10', '工作台整合、受控导出、正确性与安全验收'],
];
</script>

<template>
  <section class="page">
    <div class="page-heading">
      <div><div class="eyebrow">DATALENS / FOUNDATION</div><h1>从可靠的基础开始</h1><p class="subtitle">独立智能问数与动态图表平台。已提供受控查询、动态图表、共享、多轮问数和工作台。此页只展示真实系统状态。</p></div>
      <el-button :loading="loading" @click="reload">刷新连接状态</el-button>
    </div>

    <el-alert v-if="error" class="connection-alert" type="error" :closable="false" show-icon title="后端尚未就绪">
      <p>{{ error }}</p><p v-if="requestId">请求编号：{{ requestId }}</p>
    </el-alert>

    <div class="status-grid" aria-live="polite">
      <section class="status-card"><div class="card-label">应用接口</div><strong>{{ loading ? '检查中' : info ? '已连接' : '未连接' }}</strong><p>{{ info ? `${info.name} · ${info.version}` : '读取真实后端状态，不使用本地模拟结果' }}</p></section>
      <section class="status-card"><div class="card-label">基础依赖</div><strong>{{ loading ? '检查中' : readiness === true ? '已就绪' : '未就绪' }}</strong><p>MySQL 8.4 / Redis 实际健康检查</p></section>
      <section class="status-card"><div class="card-label">分析与模型</div><strong>按配置启用</strong><p>外部服务状态不由此健康检查推断，请在相应管理页验证连接。</p></section>
    </div>

    <div class="content-grid">
      <section class="panel"><div class="panel-heading"><h2>开发路径</h2><span>分模块交付，逐项验收</span></div>
        <div v-for="stage in stages" :key="stage[0]" class="roadmap-row">
          <span class="step-number">{{ stage[0] }}</span><div><div class="step-heading"><h3>{{ stage[1] }}</h3><span class="module-tag">{{ stage[2] }}</span></div><p>{{ stage[3] }}</p></div>
        </div>
      </section>
      <section class="panel principles"><div class="panel-heading"><h2>已经确定的边界</h2></div>
        <h3>数据更新，口径不漂移</h3><p>保存查询版本和图表规格；打开时重新取数，不重复让模型决定计算方式。</p>
        <h3>共享图表，不共享数据库</h3><p>私有、指定用户、全员共享；每次读取、刷新、导出都由服务端鉴权。</p>
        <h3>先有权限，再有智能</h3><p>默认拒绝未开放的接口。使用服务端会话认证与超管授权；没有默认密码或自行注册入口。</p>
      </section>
    </div>
    <p class="scope-note">真实 StarRocks、模型服务和生产环境需独立验收；系统接口健康不代表分析数据与外部模型已验证。</p>
  </section>
</template>

