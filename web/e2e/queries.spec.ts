import { expect, test, type Page } from '@playwright/test';
const envelope = (data: unknown, code = 'OK', message = '成功') => ({ code, message, data, requestId: 'query-ui' });
const task = (status = 'QUEUED') => ({ id: 'task-1', subjectId: 'subject-1', status, errorCode: null, createdAt: '2026-11-03T12:00:00Z', finishedAt: null, durationMs: 20, truncated: false });
async function setup(page: Page) {
  await page.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/auth/me')) return route.fulfill({ json: envelope({ id: 2, username: 'alice', role: 'USER', enabled: true }) });
    if (path.endsWith('/auth/csrf')) return route.fulfill({ json: envelope({ token: 'csrf', headerName: 'X-CSRF-TOKEN' }) });
    if (path === '/api/v1/subjects') return route.fulfill({ json: envelope([{ id: 'subject-1', name: '销售分析', fields: [], deniedFields: [], userIds: [], shareScope: 'NONE' }]) });
    if (path.endsWith('/metadata')) return route.fulfill({ json: envelope([{ tableName: 'orders', columnName: 'total', typeName: 'DECIMAL' }]) });
    if (path === '/api/v1/query-tasks') return route.fulfill({ json: envelope(task()) });
    if (path === '/api/v1/query-tasks/task-1') return route.fulfill({ json: envelope(task('SUCCEEDED')) });
    if (path.endsWith('/result')) return route.fulfill({ json: envelope({ columns: [{ name: 'total', jdbcType: 'DECIMAL' }], rows: [['9007199254740993.01']], rowLimit: 1000, truncated: true }) });
    return route.fulfill({ status: 404, json: envelope(null, 'NOT_FOUND') });
  });
  await page.goto('/queries');
  await expect(page.getByRole('combobox', { name: '查询业务主题', exact: true })).toBeEnabled({ timeout: 5000 });
  await page.getByText('选择获准访问的主题', { exact: true }).click({ timeout: 5000 });
  await page.getByRole('option', { name: '销售分析', exact: true }).click({ timeout: 5000 });
  await expect(page.getByText('orders.total', { exact: true })).toBeVisible();
  await page.getByLabel('只读 SQL', { exact: true }).fill('SELECT orders.total FROM orders');
}
test('subject-scoped asynchronous query preserves precision and visibly warns about truncation', async ({ page }) => {
  await setup(page); const request = page.waitForRequest(req => req.method() === 'POST' && req.url().endsWith('/query-tasks'));
  await page.getByRole('button', { name: '执行查询', exact: true }).click();
  const sent = await request; expect(sent.postDataJSON()).toMatchObject({ subjectId: 'subject-1', sql: 'SELECT orders.total FROM orders', parameters: [] }); expect(sent.postDataJSON().userId).toBeUndefined(); expect(sent.headers()['x-csrf-token']).toBe('csrf');
  await expect(page.getByRole('heading', { name: '查询成功', exact: true })).toBeVisible(); await expect(page.getByText('9007199254740993.01', { exact: true })).toBeVisible(); await expect(page.getByText(/结果已截断/)).toBeVisible();
  await page.screenshot({ path: 'test-results/query-results.png', fullPage: true });
});
test('cancellation uncertainty never claims a stopped database query', async ({ page }) => {
  await setup(page); await page.route('**/api/v1/query-tasks/task-1', route => route.fulfill({ json: envelope(task('RUNNING')) }));
  await page.route('**/api/v1/query-tasks/task-1/cancel', route => route.fulfill({ json: envelope(task('CANCEL_UNCONFIRMED')) }));
  await page.getByRole('button', { name: '执行查询', exact: true }).click(); await page.getByRole('button', { name: '取消查询', exact: true }).click();
  await expect(page.getByRole('heading', { name: '取消未确认', exact: true })).toBeVisible(); await expect(page.getByText(/数据库端是否停止尚未确认/)).toBeVisible(); await expect(page.getByRole('heading', { name: '已取消', exact: true })).toHaveCount(0);
});
test('permission revocation clears prior results rather than retaining a successful snapshot', async ({ page }) => {
  await setup(page); await page.getByRole('button', { name: '执行查询', exact: true }).click(); await expect(page.getByText('9007199254740993.01', { exact: true })).toBeVisible();
  await page.route('**/api/v1/query-tasks/task-1/result', route => route.fulfill({ status: 403, json: envelope(null, 'FORBIDDEN', '主题权限已撤销') }));
  await page.getByRole('button', { name: '重新检查状态' }).click(); await expect(page.getByText(/主题权限已撤销/)).toBeVisible(); await expect(page.getByText('9007199254740993.01', { exact: true })).toHaveCount(0);
});
test('unknown submission outcome retries the same idempotent input and prevents new work', async ({ page }) => {
  await setup(page); const requests: unknown[] = [];
  await page.route('**/api/v1/query-tasks', route => { requests.push(route.request().postDataJSON()); return requests.length === 1 ? route.abort() : route.fulfill({ json: envelope(task('SUCCEEDED')) }); });
  await page.getByRole('button', { name: '执行查询', exact: true }).click(); await expect(page.getByText(/提交结果未知/)).toBeVisible(); await expect(page.getByLabel('只读 SQL', { exact: true })).toBeDisabled();
  await page.getByRole('button', { name: '重试确认原任务' }).click(); await expect(page.getByText('9007199254740993.01', { exact: true })).toBeVisible(); expect(requests).toHaveLength(2); expect(requests[0]).toEqual(requests[1]);
});
test('leaving the page cancels polling and late responses cannot resurrect old results', async ({ page }) => {
  await setup(page); let polls = 0;
  await page.route('**/api/v1/charts?*', route => route.fulfill({ json: envelope({ items: [], total: 0, page: 1, size: 20 }) }));
  await page.route('**/api/v1/query-tasks/task-1', async route => { polls++; await new Promise(resolve => setTimeout(resolve, 400)); await route.fulfill({ json: envelope(task('RUNNING')) }); });
  await page.getByRole('button', { name: '执行查询', exact: true }).click(); await expect(page.getByRole('heading', { name: '排队中', exact: true })).toBeVisible();
  await page.getByRole('link', { name: '图表中心', exact: true }).click(); await expect(page).toHaveURL(/\/charts\/mine$/);
  const before = polls; await page.waitForTimeout(1800); expect(polls).toBe(before); await expect(page.getByRole('heading', { name: '排队中', exact: true })).toHaveCount(0);
});
test('duplicate clicks produce one submission and server validation stays visible', async ({ page }) => {
  await setup(page); let submissions = 0;
  await page.route('**/api/v1/query-tasks', async route => { submissions++; await new Promise(resolve => setTimeout(resolve, 250)); await route.fulfill({ status: 400, json: envelope(null, 'SQL_REJECTED', 'SQL 超出主题授权范围') }); });
  await page.getByRole('button', { name: '执行查询', exact: true }).dblclick(); await expect(page.getByText(/SQL 超出主题授权范围/)).toBeVisible(); expect(submissions).toBe(1); await expect(page.getByRole('heading', { name: '查询成功', exact: true })).toHaveCount(0);
});
test('large results are paginated without coercing nulls or treating empty data as an error', async ({ page }) => {
  await setup(page);
  await page.route('**/api/v1/query-tasks/task-1/result', route => route.fulfill({ json: envelope({ columns: [{ name: 'total', jdbcType: 'DECIMAL' }], rows: Array.from({ length: 75 }, (_, i) => [i === 0 ? null : `value-${i}`]), rowLimit: 1000, truncated: false }) }));
  await page.getByRole('button', { name: '执行查询', exact: true }).click(); await expect(page.getByText('NULL', { exact: true })).toBeVisible(); await expect(page.getByText('value-74', { exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: 'Go to next page' }).click(); await expect(page.getByText('value-74', { exact: true })).toBeVisible(); await expect(page.getByText('NULL', { exact: true })).toHaveCount(0);
  await page.route('**/api/v1/query-tasks/task-1/result', route => route.fulfill({ json: envelope({ columns: [{ name: 'total', jdbcType: 'DECIMAL' }], rows: [], rowLimit: 1000, truncated: false }) }));
  await page.getByRole('button', { name: '重新检查状态' }).click(); await expect(page.getByText('查询成功，没有匹配数据', { exact: true })).toBeVisible();
});
test('session expiry during polling clears the query page', async ({ page }) => {
  await setup(page); await page.route('**/api/v1/query-tasks/task-1', route => route.fulfill({ status: 401, json: envelope(null, 'UNAUTHORIZED', '登录已过期') }));
  await page.getByRole('button', { name: '执行查询', exact: true }).click(); await expect(page).toHaveURL(/\/login$/); await expect(page.getByRole('heading', { name: '安全 SQL 查询', exact: true })).toHaveCount(0);
});
