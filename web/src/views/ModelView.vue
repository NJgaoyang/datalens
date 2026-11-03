<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref } from 'vue';
import { errorMessage } from '../api/http';
import { loadModelConfig, modelDefaults, modelPayload, saveModelConfig, type ModelConfig } from '../api/model';
const form = reactive(modelDefaults());
const original = ref<ModelConfig>();
const apiKey = ref(''), error = ref(''), success = ref('');
const loading = ref(false), busy = ref(false), needsReload = ref(false);
let active: AbortController | undefined; let alive = true;
async function reload() {
  if (busy.value) return;
  active?.abort(); const controller = new AbortController(); active = controller;
  loading.value = true; error.value = ''; success.value = ''; apiKey.value = ''; original.value = undefined;
  Object.assign(form, modelDefaults());
  try { const config = await loadModelConfig(controller.signal); if (alive && !controller.signal.aborted) { original.value = config; Object.assign(form, config); needsReload.value = false; } }
  catch (cause) { if (alive && !controller.signal.aborted) error.value = errorMessage(cause); }
  finally { if (alive && !controller.signal.aborted) loading.value = false; }
}
function reset() { if (original.value) Object.assign(form, original.value); apiKey.value = ''; error.value = ''; success.value = ''; }
async function save() {
  if (busy.value || loading.value || !original.value || needsReload.value) return;
  error.value = ''; success.value = '';
  let payload;
  try { payload = modelPayload(form, apiKey.value, original.value); }
  catch (cause) { error.value = errorMessage(cause); apiKey.value = ''; return; }
  busy.value = true;
  try { const config = await saveModelConfig(payload); if (alive) { original.value = config; Object.assign(form, config); success.value = '模型配置已保存。保存配置不会调用模型服务。'; } }
  catch (cause) { if (alive) { error.value = errorMessage(cause); needsReload.value = true; } }
  finally { apiKey.value = ''; payload.apiKey = null; busy.value = false; }
}
onMounted(reload);
onUnmounted(() => { alive = false; active?.abort(); apiKey.value = ''; });
</script>
<template><section class="page">
  <div class="page-heading"><div><div class="eyebrow">ADMINISTRATION / MODEL</div><h1>模型配置</h1><p class="subtitle">仅超管可配置。模型不可用不影响已保存图表刷新；此页面不发送提示词或测试请求。</p></div><el-button :disabled="busy" :loading="loading" @click="reload">重新加载配置</el-button></div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" class="form-alert"/>
  <el-alert v-if="success" :title="success" type="success" :closable="false" class="form-alert"/>
  <el-alert v-if="needsReload" title="保存未确认或配置已变更。请重新加载服务器配置，确认最新状态后再修改；不会自动重试。" type="warning" :closable="false" class="form-alert"/>
  <div v-if="original" class="panel" v-loading="loading">
    <div class="panel-heading"><h2>提供方与安全预算</h2><span>版本 {{ original.version }} · {{ original.credentialConfigured ? '凭据已配置' : '未配置凭据' }}</span></div>
    <p class="scope-note">接口地址必须与服务器允许列表中的完整 HTTPS 地址匹配。API Key 不回显、不写入浏览器存储；留空保留现有凭据，更换地址必须提供新凭据。</p>
    <el-form label-position="top" @submit.prevent="save"><fieldset class="source-form" :disabled="busy || needsReload">
      <el-form-item label="启用模型"><el-switch v-model="form.enabled" aria-label="启用模型" :disabled="busy || needsReload"/></el-form-item>
      <el-form-item label="提供方"><el-input model-value="OPENAI_COMPATIBLE" aria-label="提供方" disabled/></el-form-item>
      <el-form-item label="完整 HTTPS 接口地址"><el-input v-model="form.endpoint" aria-label="完整 HTTPS 接口地址" placeholder="https://<获准的公网IPv4>/v1/chat/completions" autocomplete="off"/></el-form-item>
      <el-form-item label="模型标识"><el-input v-model="form.modelId" aria-label="模型标识" autocomplete="off"/></el-form-item>
      <el-form-item label="API Key（留空保留现有凭据）"><el-input v-model="apiKey" aria-label="模型 API Key" type="password" maxlength="4096" autocomplete="new-password"/></el-form-item>
      <el-form-item label="超时秒数"><el-input-number v-model="form.timeoutSeconds" aria-label="超时秒数" :min="1" :max="60" :precision="0"/></el-form-item>
      <el-form-item label="输入字符上限"><el-input-number v-model="form.maxInputChars" aria-label="输入字符上限" :min="256" :max="24000" :precision="0"/></el-form-item>
      <el-form-item label="输出 Token 上限"><el-input-number v-model="form.maxOutputTokens" aria-label="输出 Token 上限" :min="128" :max="4096" :precision="0"/></el-form-item>
      <el-form-item label="最多修复次数"><el-input-number v-model="form.maxRepairs" aria-label="最多修复次数" :min="0" :max="1" :precision="0"/></el-form-item>
      <el-form-item label="每日 Token 预算"><el-input-number v-model="form.dailyTokenBudget" aria-label="每日 Token 预算" :min="1024" :max="10000000" :precision="0"/></el-form-item>
      <div class="dialog-actions"><el-button :disabled="busy || needsReload" @click="reset">放弃修改</el-button><el-button type="primary" native-type="submit" :loading="busy" :disabled="needsReload">保存模型配置</el-button></div>
    </fieldset></el-form>
  </div>
</section></template>
