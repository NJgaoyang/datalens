import { expect, test, type Page } from '@playwright/test';
const config = { version: 1, enabled: false, provider: 'OPENAI_COMPATIBLE', endpoint: 'https://api.example.com/v1/chat/completions', modelId: 'fixture-model', timeoutSeconds: 30, maxInputChars: 12000, maxOutputTokens: 2000, maxRepairs: 1, dailyTokenBudget: 100000, credentialConfigured: true };
const envelope = (data: unknown, code = 'OK', message = '成功') => ({ code, message, data, requestId: 'model-ui' });
async function fixtures(page: Page, admin = true) {
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/v1/auth/me') return route.fulfill({ json: envelope({ id: 1, username: 'fixture', role: admin ? 'SUPER_ADMIN' : 'USER', enabled: true }) });
    if (path === '/api/v1/auth/csrf') return route.fulfill({ json: envelope({ token: 'csrf', headerName: 'X-CSRF-TOKEN' }) });
    if (path === '/api/v1/admin/model') return route.fulfill({ json: envelope(config) });
    return route.fulfill({ status: 404, json: envelope(null, 'NOT_FOUND') });
  });
}
test('ordinary user cannot navigate to model configuration or request admin API', async ({ page }) => {
  await fixtures(page, false); const paths: string[] = []; page.on('request', req => paths.push(new URL(req.url()).pathname));
  await page.goto('/model'); await expect(page).toHaveURL(/\/$/);
  await expect(page.getByRole('link', { name: '模型配置' })).toHaveCount(0);
  expect(paths).not.toContain('/api/v1/admin/model');
});
test('admin clears credentials on discard and save, submits once with CSRF and version', async ({ page }) => {
  await fixtures(page); let saved = 0;
  await page.route('**/api/v1/admin/model', async route => {
    if (route.request().method() === 'GET') return route.fulfill({ json: envelope(config) });
    saved++; expect(route.request().headers()['x-csrf-token']).toBe('csrf');
    expect(route.request().postDataJSON()).toMatchObject({ expectedVersion: 1, apiKey: null });
    expect(route.request().postDataJSON()).not.toHaveProperty('credentialConfigured');
    await new Promise(resolve => setTimeout(resolve, 200)); return route.fulfill({ json: envelope({ ...config, version: 2 }) });
  });
  await page.goto('/model'); await expect(page.getByText('版本 1 · 凭据已配置')).toBeVisible();
  await page.getByLabel('模型 API Key', { exact: true }).fill('fixture-only'); await page.getByRole('button', { name: '放弃修改' }).click();
  await expect(page.getByLabel('模型 API Key', { exact: true })).toHaveValue('');
  await page.getByRole('button', { name: '保存模型配置' }).dblclick();
  await expect(page.getByText('版本 2 · 凭据已配置')).toBeVisible(); expect(saved).toBe(1);
  expect(await page.evaluate(() => ({ local: { ...localStorage }, session: { ...sessionStorage } }))).toEqual({ local: {}, session: {} });
  await page.screenshot({ path: 'test-results/model-config.png', fullPage: true });
});
test('endpoint changes require fresh key and conflict requires reload before another save', async ({ page }) => {
  await fixtures(page); let saves = 0;
  await page.route('**/api/v1/admin/model', route => {
    if (route.request().method() === 'GET') return route.fulfill({ json: envelope(config) });
    saves++; return route.fulfill({ status: 409, json: envelope(null, 'CONFLICT', '配置已被其他管理员更新') });
  });
  await page.goto('/model'); await page.getByLabel('完整 HTTPS 接口地址', { exact: true }).fill('https://other.example.com/chat');
  await page.getByRole('button', { name: '保存模型配置' }).click(); await expect(page.getByText('更换模型接口地址必须提供新的 API Key')).toBeVisible(); expect(saves).toBe(0);
  await page.getByLabel('模型 API Key', { exact: true }).fill('fixture-only'); await page.getByRole('button', { name: '保存模型配置' }).click();
  await expect(page.getByText(/配置已被其他管理员更新/)).toBeVisible(); await expect(page.getByRole('button', { name: '保存模型配置' })).toBeDisabled();
  await expect(page.getByLabel('模型 API Key', { exact: true })).toHaveValue(''); expect(saves).toBe(1);
  await page.getByRole('button', { name: '重新加载配置' }).click(); await expect(page.getByRole('button', { name: '保存模型配置' })).toBeEnabled();
  await expect(page.getByLabel('完整 HTTPS 接口地址', { exact: true })).toHaveValue(config.endpoint);
});
test('expired session removes model configuration', async ({ page }) => {
  await fixtures(page); await page.route('**/api/v1/admin/model', route => route.fulfill({ status: 401, json: envelope(null, 'UNAUTHORIZED', '登录已过期') }));
  await page.goto('/model'); await expect(page).toHaveURL(/\/login$/); await expect(page.getByRole('heading', { name: '模型配置' })).toHaveCount(0);
});
test('unconfigured defaults remain disabled and navigation clears an unsaved key', async ({ page }) => {
  await fixtures(page);
  await page.route('**/api/v1/admin/model', route => route.fulfill({ json: envelope({ ...config, version: 0, endpoint: '', modelId: '', credentialConfigured: false }) }));
  await page.goto('/model'); await expect(page.getByText('版本 0 · 未配置凭据')).toBeVisible();
  await expect(page.getByRole('switch', { name: '启用模型' })).not.toBeChecked();
  await page.getByLabel('模型 API Key', { exact: true }).fill('fixture-only');
  await page.getByRole('link', { name: '业务主题', exact: true }).click();
  await page.goBack(); await expect(page.getByLabel('模型 API Key', { exact: true })).toHaveValue('');
});
test('failed reload hides stale configuration and cannot submit it', async ({ page }) => {
  await fixtures(page); await page.goto('/model'); await expect(page.getByText('版本 1 · 凭据已配置')).toBeVisible();
  await page.route('**/api/v1/admin/model', route => route.fulfill({ status: 403, json: envelope(null, 'FORBIDDEN', '无权读取模型配置') }));
  await page.getByRole('button', { name: '重新加载配置' }).click();
  await expect(page.getByText(/无权读取模型配置/)).toBeVisible();
  await expect(page.getByRole('button', { name: '保存模型配置' })).toHaveCount(0);
  await expect(page.getByLabel('模型 API Key', { exact: true })).toHaveCount(0);
});
