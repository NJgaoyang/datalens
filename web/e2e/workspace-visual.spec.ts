import { expect, test } from '@playwright/test';

// UI fixtures only. These screenshots verify the built frontend, not a real analytics query.
test('save dialog is legible after its transition and cancellation creates no asset', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 960 });
  const summary = { id: 'visual-c1', subjectId: 'visual-s1', title: '近 7 天销售额', version: 2 };
  const turn = {
    id: 'visual-t1', sequence: 1, question: '查询近 7 天销售额', status: 'READY',
    clarification: null, metricDescription: '有效订单实付金额；仅用于界面测试的示例口径',
    previewId: 'visual-p1', taskId: 'visual-q1', chartId: null, errorCode: null,
    createdAt: '2026-11-04T01:00:00Z', queryTime: '2026-11-04T01:00:01Z', truncated: false,
    spec: { type: 'TABLE', dimension: null, measures: [] },
    timeRule: { kind: 'LAST_DAYS', days: 7, timeZone: 'Asia/Shanghai', startIndex: 0, endIndex: 1 },
  };
  const envelope = (data: unknown) => ({ code: 'OK', message: '成功', data, requestId: 'd01-visual-fixture' });
  const mutations: string[] = [];
  page.on('request', request => { if (request.method() !== 'GET') mutations.push(request.url()); });
  await page.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/auth/me')) return route.fulfill({ json: envelope({ id: 1, username: 'preview', role: 'USER', enabled: true }) });
    if (path === '/api/v1/subjects') return route.fulfill({ json: envelope([{ id: 'visual-s1', name: '销售分析（测试数据）' }]) });
    if (path === '/api/v1/conversations') return route.fulfill({ json: envelope([summary]) });
    if (path === '/api/v1/conversations/visual-c1') return route.fulfill({ json: envelope({ ...summary, turns: [turn] }) });
    if (path.endsWith('/result')) return route.fulfill({ json: envelope({ columns: [{ name: 'sales_amount', jdbcType: 'DECIMAL' }], rows: [['128640.25']], rowLimit: 1000, truncated: false }) });
    return route.fulfill({ status: 404, json: { code: 'NOT_FOUND', message: '未配置的测试接口', data: null, requestId: 'd01-visual-fixture' } });
  });
  await page.goto('/ask/visual-c1');
  await expect(page.getByText('128640.25', { exact: true })).toBeVisible();
  await page.screenshot({ path: 'test-results/d01-result-stable.png', fullPage: true, animations: 'disabled' });
  await page.getByRole('button', { name: '保存图表', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: '保存图表', exact: true });
  await expect(dialog).toBeVisible();
  await page.getByLabel('图表名称', { exact: true }).fill('近 7 天销售额');
  // Fast-forward finite CSS transitions; do not capture a semi-transparent animation frame.
  await page.screenshot({ path: 'test-results/d01-save-dialog-stable.png', fullPage: true, animations: 'disabled' });
  await expect(dialog).toHaveCSS('opacity', '1');
  await expect(dialog.getByText(/默认仅自己可见/)).toBeVisible();
  await dialog.getByRole('button', { name: '取消', exact: true }).click();
  await expect(dialog).toHaveCount(0);
  expect(mutations).toEqual([]);
  await expect(page.getByRole('button', { name: '保存图表', exact: true })).toBeEnabled();
});
