import { expect, test, type Page } from '@playwright/test';

const envelope = (data: unknown, code = 'OK', message = '成功') => ({ code, message, data, requestId: 'consumer-ui' });
const summary = { id: 'c-real', subjectId: 's-1', title: '各城市销售额', version: 2 };
const subject = { id: 's-1', name: '销售分析', sourceId: 'src-1', fields: [], deniedFields: [], userIds: [], shareScope: 'ALL_AUTHENTICATED' };
const row = { id: 't-1', sequence: 1, question: '最近 30 天各城市的销售额是多少？', status: 'READY', clarification: null, metricDescription: '已审核销售额', previewId: 'p-1', taskId: 'q-1', chartId: null, errorCode: null, createdAt: '2026-11-04T06:26:00Z', queryTime: '2026-11-04T06:26:03Z', truncated: false, spec: { type: 'BAR', dimension: 'city', measures: ['amount'] }, timeRule: { kind: 'LAST_DAYS', days: 30, timeZone: 'Asia/Shanghai', startIndex: 0, endIndex: 1 } };
const data = { columns: [{ name: 'city', jdbcType: 'VARCHAR' }, { name: 'amount', jdbcType: 'DECIMAL' }], rows: [['上海','4620'],['北京','3980'],['深圳','3210'],['广州','2840'],['杭州','1960'],['成都','1730'],['武汉','1420']], rowLimit: 1000, truncated: false };
async function mock(page: Page, present: boolean, subjects = [subject]) {
  const posts: string[] = [];
  page.on('request', req => { if (req.method() !== 'GET') posts.push(req.url()); });
  await page.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/v1/auth/me') return route.fulfill({ json: envelope({ id: 1, username: 'admin', role: 'SUPER_ADMIN', enabled: true }) });
    if (path === '/api/v1/subjects') return route.fulfill({ json: envelope(subjects) });
    if (path === '/api/v1/conversations') return route.fulfill({ json: envelope(present ? [summary] : []) });
    if (path === '/api/v1/conversations/c-real') return route.fulfill({ json: envelope({ ...summary, turns: [row] }) });
    if (path === '/api/v1/subjects/s-1/metadata') return route.fulfill({ json: envelope([
      { tableName: 'orders', columnName: 'city', typeName: 'VARCHAR', sourceId: 'src-1', ordinalPosition: 1, nullable: false },
      { tableName: 'orders', columnName: 'amount', typeName: 'DECIMAL', sourceId: 'src-1', ordinalPosition: 2, nullable: false },
      { tableName: 'users', columnName: 'id', typeName: 'BIGINT', sourceId: 'src-1', ordinalPosition: 1, nullable: false },
    ]) });
    if (path.endsWith('/result')) return route.fulfill({ json: envelope(data) });
    return route.fulfill({ status: 404, json: envelope(null, 'NOT_FOUND') });
  });
  return posts;
}

test('empty fresh installation renders a complete onboarding screen, not a blank workspace', async ({ page }) => {
  await page.setViewportSize({ width: 1648, height: 928 });
  const posts = await mock(page, false, []);
  await page.goto('/ask');
  await expect(page.getByRole('heading', { name: '你好，今天想了解什么数据？' })).toBeVisible();
  await expect(page.getByText('暂无可用分析空间', { exact: true }).first()).toBeVisible();
  await expect(page.getByRole('link', { name: '配置数据源', exact: true })).toBeVisible();
  await expect(page.getByLabel('问数问题')).toBeDisabled();
  await expect(page.getByText('可用数据', { exact: true })).toBeVisible();
  expect(posts).toEqual([]);
  await expect(page.locator('.ask-welcome')).toBeInViewport();
  await page.screenshot({ path: 'test-results/d02-empty-desktop.png', fullPage: true, animations: 'disabled' });
});
test('approved results layout uses actual authorized schema and real query-result values', async ({ page }) => {
  await page.setViewportSize({ width: 1648, height: 928 });
  const posts = await mock(page, true);
  await page.goto('/ask/c-real');
  await expect(page.getByText('最近 30 天各城市的销售额是多少？')).toBeVisible();
  await expect(page.getByRole('heading', { name: '分析空间', exact: true })).toBeVisible();
  await expect(page.getByText('orders', { exact: true })).toBeVisible();
  await expect(page.getByText('users', { exact: true })).toBeVisible();
  await expect(page.getByRole('tab', { name: '图表', exact: true })).toHaveAttribute('aria-selected', 'true');
  await expect(page.getByRole('img', { name: 'BAR 图表' })).toBeVisible();
  await expect(page.getByText('在本次完整返回的数据中，上海 的 amount 最大，为 4620。', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '保存图表', exact: true })).toBeVisible();
  await page.screenshot({ path: 'test-results/d02-result-approved-1648.png', fullPage: true, animations: 'disabled' });
  await page.getByRole('tab', { name: '数据表格', exact: true }).click();
  await expect(page.getByText('4620', { exact: true })).toBeVisible();
  expect(posts).toEqual([]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});
test('mobile asks can select an authorized space although right sidebar collapses', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const subjects = [subject, { ...subject, id: 's-2', name: '其他分析' }];
  const posts = await mock(page, false, subjects);
  await page.goto('/ask');
  await expect(page.getByRole('heading', { name: '你好，今天想了解什么数据？' })).toBeVisible();
  const scope = page.getByLabel('移动端分析空间');
  await expect(scope).toBeVisible();
  await expect(scope).toHaveValue('');
  await scope.selectOption('s-1');
  await expect(page.getByRole('button', { name: '开始分析' })).toBeDisabled();
  await page.getByLabel('问数问题').fill('销售趋势');
  await expect(page.getByRole('button', { name: '开始分析' })).toBeEnabled();
  expect(posts).toEqual([]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: 'test-results/d02-mobile-390.png', fullPage: true, animations: 'disabled' });
});
test('search finds only authorized conversation names and charts use server-side search', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await mock(page, true);
  await page.goto('/ask');
  await page.getByLabel('搜索图表、分析记录').fill('各城市');
  await expect(page.getByRole('link', { name: '各城市销售额', exact: true }).last()).toBeVisible();
  await page.getByRole('link', { name: '各城市销售额', exact: true }).last().click();
  await expect(page).toHaveURL(/\/ask\/c-real$/);
  await page.getByLabel('搜索图表、分析记录').fill('其他资产');
  await page.getByRole('button', { name: /在我的图表中搜索/ }).click();
  await expect(page).toHaveURL(/\/charts\/mine\?search=/);
});
