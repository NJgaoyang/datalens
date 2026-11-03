import { expect, test, type Page } from '@playwright/test';
const envelope = (data: unknown, code = 'OK', message = '成功') => ({ code, message, data, requestId: 'datasources-ui' });
const source = { id: 'source-1', name: '销售仓库', host: '10.1.2.3', port: 9030, catalog: 'default_catalog', database: 'sales', username: 'reader', enabled: true, tables: ['orders'], version: 1, harvestStatus: 'SUCCEEDED', lastHarvestAt: '2026-11-03T01:00:00Z', lastErrorCode: null };
const field = { tableName: 'orders', columnName: 'total' };
const subject = { id: 'subject-1', name: '销售分析', sourceId: source.id, fields: [field], deniedFields: [], userIds: [2], shareScope: 'NONE' };
async function fixtures(page: Page, admin: boolean) {
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname, method = route.request().method();
    if (path === '/api/v1/auth/me') return route.fulfill({ json: envelope({ id: admin ? 1 : 2, username: admin ? 'admin' : 'alice', role: admin ? 'SUPER_ADMIN' : 'USER', enabled: true }) });
    if (path === '/api/v1/auth/csrf') return route.fulfill({ json: envelope({ token: 'csrf', headerName: 'X-CSRF-TOKEN' }) });
    if (method !== 'GET') expect(route.request().headers()['x-csrf-token']).toBe('csrf');
    if (path === '/api/v1/admin/sources') return route.fulfill({ json: envelope(method === 'GET' ? [source] : source) });
    if (path === '/api/v1/admin/sources/source-1') return route.fulfill({ json: envelope(source) });
    if (path.endsWith('/metadata')) return route.fulfill({ json: envelope([{ ...field, sourceId: source.id, typeName: 'DECIMAL', ordinalPosition: 1, nullable: false }]) });
    if (path.endsWith('/subjects')) return route.fulfill({ json: envelope(method === 'GET' ? [{ ...subject, userIds: admin ? [2] : [] }] : subject) });
    if (path.endsWith('/subjects/subject-1')) return route.fulfill({ json: envelope(subject) });
    return route.fulfill({ status: 404, json: envelope(null, 'NOT_FOUND') });
  });
}
test('ordinary users see filtered fields without source connection or ACL controls', async ({ page }) => {
  await fixtures(page, false); const paths: string[] = []; page.on('request', request => paths.push(new URL(request.url()).pathname));
  await page.goto('/subjects');
  await expect(page.getByRole('heading', { name: '业务主题', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '新增主题' })).toHaveCount(0);
  await expect(page.getByRole('link', { name: '数据源管理' })).toHaveCount(0);
  await page.getByRole('button', { name: '查看可用字段' }).click();
  await expect(page.getByRole('cell', { name: 'total', exact: true })).toBeVisible();
  expect(paths.filter(path => path.startsWith('/api/v1/admin/'))).toEqual([]);
  await page.goto('/sources'); await expect(page).toHaveURL(/\/workspace$/);
});
test('admin edits source, retains password by blank update, cancel clears secrets', async ({ page }) => {
  await fixtures(page, true); await page.goto('/sources');
  await page.getByRole('button', { name: '编辑', exact: true }).click();
  await expect(page.getByLabel('数据源密码', { exact: true })).toHaveValue('');
  await page.getByLabel('数据源密码', { exact: true }).fill('temporary-secret');
  await page.getByRole('button', { name: '取消', exact: true }).click();
  await page.getByRole('button', { name: '编辑', exact: true }).click();
  await expect(page.getByLabel('数据源密码', { exact: true })).toHaveValue('');
  const request = page.waitForRequest(req => req.method() === 'PUT');
  await page.getByRole('button', { name: '保存数据源' }).click();
  expect((await request).postDataJSON()).toEqual({ name: source.name, host: source.host, port: source.port, catalog: source.catalog, database: source.database, username: source.username, enabled: true, password: '', tables: ['orders'] });
  await expect(page.getByRole('dialog')).toHaveCount(0);
  expect(await page.evaluate(() => ({ local: { ...localStorage }, session: { ...sessionStorage } }))).toEqual({ local: {}, session: {} });
});
test('connection failure remains visible without invented success', async ({ page }) => {
  await fixtures(page, true);
  await page.route('**/api/v1/admin/sources/source-1/test', route => route.fulfill({ status: 503, json: envelope(null, 'SOURCE_CONNECTION_FAILED', '数据源连接失败') }));
  await page.goto('/sources'); await page.getByRole('button', { name: '测试连接' }).click();
  await expect(page.getByText(/数据源连接失败/)).toBeVisible();
  await expect(page.getByText('连接测试成功', { exact: true })).toHaveCount(0);
});
test('admin saves exact allow and deny fields and user scope', async ({ page }) => {
  await fixtures(page, true); await page.goto('/subjects');
  await page.getByRole('button', { name: '编辑权限' }).click();
  await expect(page.getByText('orders.total · DECIMAL')).toBeVisible();
  await page.getByLabel('允许字段', { exact: true }).fill('orders.total\norders.email');
  await page.getByLabel('禁止字段', { exact: true }).fill('orders.email');
  await page.getByLabel('授权用户 ID', { exact: true }).fill('2, 3');
  const request = page.waitForRequest(req => req.method() === 'PUT');
  await page.getByRole('button', { name: '保存主题' }).click();
  expect((await request).postDataJSON()).toMatchObject({ fields: [field, { tableName: 'orders', columnName: 'email' }], deniedFields: [{ tableName: 'orders', columnName: 'email' }], userIds: [2, 3], shareScope: 'NONE' });
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await page.screenshot({ path: 'test-results/subjects.png', fullPage: true });
});
test('source list authorization expiration removes private screen', async ({ page }) => {
  await fixtures(page, true); await page.route('**/api/v1/admin/sources', route => route.fulfill({ status: 401, json: envelope(null, 'UNAUTHORIZED', '登录已过期') }));
  await page.goto('/sources'); await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByRole('heading', { name: '数据源管理' })).toHaveCount(0);
});
test('new source validates configuration and submits only once while pending', async ({ page }) => {
  await fixtures(page, true); let submitted = 0;
  await page.route('**/api/v1/admin/sources', async route => {
    if (route.request().method() === 'GET') return route.fulfill({ json: envelope([]) });
    submitted++; await new Promise(resolve => setTimeout(resolve, 300));
    return route.fulfill({ json: envelope(source) });
  });
  await page.goto('/sources'); await page.getByRole('button', { name: '新增数据源' }).click();
  await page.getByRole('button', { name: '保存数据源' }).click();
  await expect(page.getByText('新数据源必须填写密码')).toBeVisible();
  expect(submitted).toBe(0);
  await page.getByLabel('名称', { exact: true }).fill('销售仓库');
  await page.getByLabel('IPv4 地址', { exact: true }).fill('10.1.2.3');
  await page.getByLabel('数据库', { exact: true }).fill('sales');
  await page.getByLabel('只读账号', { exact: true }).fill('reader');
  await page.getByLabel('授权表', { exact: true }).fill('orders');
  await page.getByLabel('数据源密码', { exact: true }).fill('source-password');
  await page.getByRole('button', { name: '保存数据源' }).dblclick();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  expect(submitted).toBe(1);
});
test('permission revocation clears previously visible metadata', async ({ page }) => {
  await fixtures(page, false); await page.goto('/subjects');
  await page.getByRole('button', { name: '查看可用字段' }).click();
  await expect(page.getByRole('cell', { name: 'total', exact: true })).toBeVisible();
  await page.route('**/api/v1/subjects/subject-1/metadata', route => route.fulfill({ status: 403, json: envelope(null, 'FORBIDDEN', '无权访问该主题') }));
  await page.getByRole('button', { name: '查看可用字段' }).click();
  await expect(page.getByText(/无权访问该主题/)).toBeVisible();
  await expect(page.getByRole('cell', { name: 'total', exact: true })).toHaveCount(0);
});
