<script setup lang="ts">
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { login, session } from '../api/auth';
import { errorMessage } from '../api/http';
const router = useRouter();
const username = ref(''); const password = ref(''); const loading = ref(false); const error = ref(session.error);
async function submit() {
  if (loading.value) return;
  if (!username.value.trim() || !password.value) { error.value = '请输入用户名和密码'; return; }
  loading.value = true; error.value = '';
  try { await login(username.value.trim(), password.value); await router.replace('/'); }
  catch (cause) { error.value = errorMessage(cause); }
  finally { password.value = ''; loading.value = false; }
}
</script>
<template>
  <main class="login-page"><section class="panel login-panel">
    <div class="eyebrow">DATALENS / WORKSPACE</div><h1>登录 DataLens</h1><p class="subtitle">使用管理员为你创建的账号登录。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="form-alert" />
    <el-form label-position="top" @submit.prevent="submit">
      <el-form-item label="用户名"><el-input v-model="username" aria-label="用户名" autocomplete="username" maxlength="64" :disabled="loading" /></el-form-item>
      <el-form-item label="密码"><el-input v-model="password" aria-label="密码" type="password" autocomplete="current-password" show-password :disabled="loading" /></el-form-item>
      <el-button type="primary" native-type="submit" :loading="loading" class="full-width">登录</el-button>
    </el-form>
    <p class="scope-note">不开放自行注册。忘记密码请联系管理员。</p>
  </section></main>
</template>
