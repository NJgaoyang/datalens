<script setup lang="ts">
import { useRoute } from 'vue-router';
const route = useRoute();
</script>

<template>
  <section class="page">
    <div class="eyebrow">DATALENS / {{ route.meta.stage }}</div>
    <h1>{{ route.meta.title }}</h1>
    <p class="subtitle">{{ route.meta.description }}</p>
    <section class="panel planned-panel">
      <el-empty description="该模块尚未实现">
        <p>本轮仅交付 M00 工程基础，后续按开发计划逐模块实现并验收。</p>
        <RouterLink to="/"><el-button>返回工程概览</el-button></RouterLink>
      </el-empty>
    </section>
  </section>
</template>
