import { expect, test, type Page } from '@playwright/test';
const user = { id: 2, username: 'alice', role: 'USER', enabled: true };
const admin = { id: 1, username: 'admin', role: 'SUPER_ADMIN', enabled: true };
const envelope = (data: unknown, code = 'OK', message = '成功') => ({ code, message, data, requestId: 'identity-ui' });
async function fixtures(page: Page, role: 'USER' | 'SUPER_ADMIN' = 'USER', initiallyLoggedIn = false) {
  let loggedIn = initiallyLoggedIn; let csrfReads = 0;
  const current = role === 'USER' ? user : admin;
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname;
    const method = route.request().method();
    if (path === '/api/v1/auth/csrf') { csrfReads++; return route.fulfill({ json: envelope({ token: `csrf-${csrfReads}`, headerName: 'X-CSRF-TOKEN' }) }); }
    if (method !== 'GET') expect(route.request().headers()['x-csrf-token']).toBe(`csrf-${csrfReads}`);
    if (path === '/api/v1/auth/login') { loggedIn = true; return route.fulfill({ json: envelope(current) }); }
    if (path === '/api/v1/auth/logout' || path === '/api/v1/auth/password') { loggedIn = false; return route.fulfill({ json: envelope(null) }); }
    if (path === '/api/v1/system/info') return route.fulfill({ json: envelope({ name: 'DataLens', version: '0.1.0', stage: 'M01', enabledModules: ['foundation', 'identity'] }) });
    if (!loggedIn) return route.fulfill({ status: 401, json: envelope(null, 'UNAUTHORIZED', '请登录') });
    if (path === '/api/v1/auth/me') return route.fulfill({ json: envelope(current) });
    if (path === '/api/v1/admin/users') return route.fulfill({ json: envelope(method === 'GET' ? { items: [admin, user], total: 2, page: 1, size: 20 } : user) });
    if (path.startsWith('/api/v1/admin/users/')) return route.fulfill({ json: envelope(null) });
    return route.fulfill({ status: 404, json: envelope(null, 'NOT_FOUND') });
  });
  await page.route('**/actuator/health/readiness', route => route.fulfill({ json: { status: 'UP' } }));
}

test('anonymous redirect, login, no persisted credentials, logout and browser back', async ({ page }) => {
  await fixtures(page);
  await page.goto('/settings');
  await expect(page).toHaveURL(/\/login$/);
  await page.getByLabel('用户名', { exact: true }).fill('alice');
  await page.getByLabel('密码', { exact: true }).fill('example-password');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByRole('heading', { name: '从可靠的基础开始' })).toBeVisible();
  await expect(page.getByRole('link', { name: '用户管理' })).toHaveCount(0);
  expect(await page.evaluate(() => ({ local: { ...localStorage }, session: { ...sessionStorage } }))).toEqual({ local: {}, session: {} });
  await page.goto('/settings');
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByRole('heading', { name: '用户管理' })).toHaveCount(0);
  await page.getByRole('button', { name: '退出登录' }).click();
  await expect(page).toHaveURL(/\/login$/);
  await page.goBack();
  await expect(page.getByRole('heading', { name: '用户管理' })).toHaveCount(0);
});

test('super admin creates users, cancel clears password, disable and reset', async ({ page }) => {
  await fixtures(page, 'SUPER_ADMIN', true);
  await page.goto('/settings');
  await expect(page.getByRole('heading', { name: '用户管理' })).toBeVisible();
  await page.getByRole('button', { name: '创建用户' }).click();
  await page.getByLabel('用户名', { exact: true }).fill('bob');
  await page.getByLabel('新密码', { exact: true }).fill('example-password');
  await page.getByRole('button', { name: '取消', exact: true }).click();
  await page.getByRole('button', { name: '创建用户' }).click();
  await expect(page.getByLabel('新密码', { exact: true })).toHaveValue('');
  await page.getByLabel('用户名', { exact: true }).fill('bob');
  await page.getByLabel('新密码', { exact: true }).fill('example-password');
  await page.getByRole('button', { name: '创建', exact: true }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  const alice = page.getByRole('row').filter({ hasText: 'alice' });
  await alice.getByRole('button', { name: '禁用', exact: true }).click();
  await page.getByRole('button', { name: '确认', exact: true }).click();
  await alice.getByRole('button', { name: '重置密码' }).click();
  await page.getByLabel('新密码', { exact: true }).fill('replacement-password');
  await page.getByRole('button', { name: '确认重置' }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
});

test('password validation and successful change returns to login', async ({ page }) => {
  await fixtures(page, 'USER', true);
  await page.goto('/account/password');
  await page.getByLabel('当前密码', { exact: true }).fill('example-password');
  await page.getByLabel('新密码', { exact: true }).fill('replacement-password');
  await page.getByLabel('确认新密码', { exact: true }).fill('different-password');
  await page.getByRole('button', { name: '保存新密码' }).click();
  await expect(page.getByText('两次输入的新密码不一致')).toBeVisible();
  await page.getByLabel('确认新密码', { exact: true }).fill('replacement-password');
  await page.getByRole('button', { name: '保存新密码' }).click();
  await expect(page).toHaveURL(/\/login$/);
  expect(await page.evaluate(() => ({ local: { ...localStorage }, session: { ...sessionStorage } }))).toEqual({ local: {}, session: {} });
  await expect(page.getByLabel('密码', { exact: true })).toHaveValue('');
  await page.goto('/account/password');
  await expect(page).toHaveURL(/\/login$/);
});

test('session expiration while reading admin data redirects to login', async ({ page }) => {
  await fixtures(page, 'SUPER_ADMIN', true);
  await page.route('**/api/v1/admin/users?*', route => route.fulfill({ status: 401, json: envelope(null, 'UNAUTHORIZED', '登录已过期') }));
  await page.goto('/settings');
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByText('登录已过期，请重新登录')).toBeVisible();
});

test('incorrect credentials show failure and clear the password', async ({ page }) => {
  await fixtures(page);
  await page.route('**/api/v1/auth/login', route => route.fulfill({ status: 401, json: envelope(null, 'UNAUTHORIZED', '用户名或密码错误') }));
  await page.goto('/login');
  await page.getByLabel('用户名', { exact: true }).fill('alice');
  await page.getByLabel('密码', { exact: true }).fill('incorrect-password');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByText(/用户名或密码错误/)).toBeVisible();
  await expect(page.getByLabel('密码', { exact: true })).toHaveValue('');
  await expect(page).toHaveURL(/\/login$/);
});

test('foundation status page remains accessible anonymously', async ({ page }) => {
  await fixtures(page);
  await page.goto('/');
  await expect(page.getByRole('heading', { name: '从可靠的基础开始' })).toBeVisible();
  await expect(page.getByRole('link', { name: '登录', exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: '用户管理' })).toHaveCount(0);
});
