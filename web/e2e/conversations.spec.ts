import { expect, test, type Page } from '@playwright/test';
const envelope = (data: unknown, code = 'OK', message = '成功') => ({ code, message, data, requestId: 'conversation-ui' });
const summary = { id: 'c1', subjectId: 's1', title: '月度销售', version: 1 };
const ready = { id: 't1', sequence: 1, question: '查询销售额', status: 'READY', clarification: null, metricDescription: '已审核销售额：有效订单实付金额', previewId: 'p1', taskId: 'q1', chartId: null, errorCode: null, createdAt: '2026-11-03T01:00:00Z', queryTime: '2026-11-03T01:00:01Z', truncated: false, spec: { type: 'TABLE', dimension: null, measures: [] }, timeRule: { kind: 'LAST_DAYS', days: 7, timeZone: 'Asia/Shanghai', startIndex: 0, endIndex: 1 } };
const result = { columns: [{ name: 'amount', jdbcType: 'DECIMAL' }], rows: [['9007199254740993.25']], rowLimit: 1000, truncated: false };
async function fixtures(page: Page, turns: unknown[] = []) {
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/v1/auth/me') return route.fulfill({ json: envelope({ id: 1, username: 'fixture', role: 'USER', enabled: true }) });
    if (path === '/api/v1/auth/csrf') return route.fulfill({ json: envelope({ token: 'csrf', headerName: 'X-CSRF-TOKEN' }) });
    if (path === '/api/v1/subjects') return route.fulfill({ json: envelope([{ id: 's1', name: '销售主题' }]) });
    if (path === '/api/v1/conversations') return route.fulfill({ json: envelope([summary]) });
    if (path === '/api/v1/conversations/c1') return route.fulfill({ json: envelope({ ...summary, turns }) });
    if (path.endsWith('/result')) return route.fulfill({ json: envelope(result) });
    return route.fulfill({ status: 404, json: envelope(null, 'NOT_FOUND') });
  });
}
test('create once, select authorized subject, no browser storage or model calls', async ({ page }) => {
  await fixtures(page); let creates = 0; const paths: string[] = []; page.on('request', req => paths.push(new URL(req.url()).pathname));
  await page.route('**/api/v1/conversations', async route => {
    if (route.request().method() === 'GET') return route.fulfill({ json: envelope([summary]) });
    creates++; expect(route.request().postDataJSON()).toMatchObject({ subjectId: 's1', title: '月度销售' }); expect(route.request().postDataJSON().idempotencyKey).toBeTruthy();
    expect(route.request().headers()['x-csrf-token']).toBe('csrf');
    await new Promise(resolve => setTimeout(resolve, 150)); return route.fulfill({ json: envelope(summary) });
  });
  await page.goto('/ask'); await page.getByLabel('会话业务主题').selectOption('s1'); await page.getByLabel('会话标题').fill('月度销售');
  await page.getByRole('button', { name: '创建会话', exact: true }).dblclick(); await expect(page).toHaveURL(/\/ask\/c1$/); expect(creates).toBe(1);
  expect(paths.some(path => path.includes('/model/'))).toBe(false);
  expect(await page.evaluate(() => ({ local: { ...localStorage }, session: { ...sessionStorage } }))).toEqual({ local: {}, session: {} });
});
test('ready preview exposes basis, timezone and query time; save needs explicit confirmation and submits once', async ({ page }) => {
  await fixtures(page, [ready]); let saves = 0;
  await page.route('**/api/v1/conversations/c1/turns/t1/save', async route => {
    saves++; expect(route.request().postDataJSON()).toEqual({ name: '精确月度图表', confirmation: true });
    await new Promise(resolve => setTimeout(resolve, 150)); return route.fulfill({ json: envelope({ id: 'chart1', name: '精确月度图表', visibility: 'PRIVATE' }) });
  });
  await page.goto('/ask/c1'); await expect(page.getByText('9007199254740993.25', { exact: true })).toBeVisible();
  await expect(page.getByText('已审核销售额：有效订单实付金额', { exact: false })).toBeVisible();
  await expect(page.getByText('最近 7 天（含今天） · 业务时区：Asia/Shanghai', { exact: true })).toBeVisible();
  await expect(page.getByText('查询完成时间：2026-11-03T01:00:01Z')).toBeVisible(); expect(saves).toBe(0);
  await page.getByLabel('第 1 轮图表名称').fill('精确月度图表'); await page.getByRole('button', { name: '确认保存私有图表' }).dblclick();
  await expect(page.getByRole('link', { name: '打开已保存图表' })).toHaveAttribute('href', '/charts/chart1'); expect(saves).toBe(1);
  await page.screenshot({ path: 'test-results/conversation-preview.png', fullPage: true });
});
test('clarification, query polling and exact last-month follow-up retain server version', async ({ page }) => {
  const clarification = { ...ready, status: 'CLARIFICATION_REQUIRED', clarification: '请选择销售额还是订单数', metricDescription: null, previewId: null, taskId: null, queryTime: null, truncated: null, spec: null, timeRule: null };
  await fixtures(page, [clarification]); let turns: unknown[] = [clarification], version = 2, posts = 0;
  await page.route('**/api/v1/conversations/c1', route => route.fulfill({ json: envelope({ ...summary, version, turns }) }));
  await page.route('**/api/v1/conversations/c1/turns', route => {
    posts++; const body = route.request().postDataJSON(); expect(body.expectedVersion).toBe(version); expect(body.idempotencyKey).toBeTruthy();
    if (posts === 2) expect(body.question).toBe('改成上个月');
    const turn = { ...ready, id: `t${posts + 1}`, sequence: posts + 1, question: body.question, ...(posts === 2 ? { timeRule: { ...ready.timeRule, kind: 'LAST_MONTH', days: null } } : {}) };
    turns = [...turns, turn]; version++; return route.fulfill({ json: envelope({ ...turn, status: 'QUERYING', queryTime: null }) });
  });
  await page.route('**/api/v1/conversations/c1/turns/t*', route => route.fulfill({ json: envelope(turns.at(-1)) }));
  await page.goto('/ask/c1'); await expect(page.getByText('请选择销售额还是订单数')).toBeVisible(); await expect(page.getByRole('button', { name: '确认保存私有图表' })).toHaveCount(0);
  await page.getByLabel('问数问题').fill('销售额'); await page.getByRole('button', { name: '提交问题' }).click();
  await expect(page.getByText('9007199254740993.25', { exact: true })).toBeVisible(); await page.getByRole('button', { name: '改成上个月', exact: true }).click();
  await expect(page.getByLabel('问数问题')).toHaveValue('改成上个月'); await page.getByRole('button', { name: '提交问题' }).click();
  await expect(page.getByText('上一个完整自然月 · 业务时区：Asia/Shanghai', { exact: true })).toBeVisible(); expect(posts).toBe(2);
});
test('no-data and truncated responses stay factual without automatic save', async ({ page }) => {
  await fixtures(page, [{ ...ready, status: 'NO_DATA' }]); let saves = 0;
  page.on('request', request => { if (request.url().endsWith('/save')) saves++; });
  await page.route('**/result', route => route.fulfill({ json: envelope({ ...result, rows: [] }) }));
  await page.goto('/ask/c1'); await expect(page.getByText('查询成功，但没有匹配数据。不会据此生成趋势、总额或原因说明。')).toBeVisible();
  await expect(page.getByRole('button', { name: '确认保存私有图表' })).toBeVisible(); expect(saves).toBe(0);
  await page.route('**/api/v1/conversations/c1', route => route.fulfill({ json: envelope({ ...summary, turns: [{ ...ready, truncated: true }] }) }));
  await page.route('**/result', route => route.fulfill({ json: envelope({ ...result, truncated: true }) }));
  await page.getByRole('button', { name: '重新加载', exact: true }).click();
  await expect(page.getByText('结果已截断，仅代表返回的部分数据；不计算总额或占比。')).toBeVisible();
});
test('conflict blocks replay until reload and clears previously visible data', async ({ page }) => {
  await fixtures(page, [ready]); let submits = 0;
  await page.route('**/api/v1/conversations/c1/turns', route => { submits++; return route.fulfill({ status: 409, json: envelope(null, 'CONVERSATION_VERSION_CONFLICT', '会话已更新') }); });
  await page.goto('/ask/c1'); await expect(page.getByText('9007199254740993.25', { exact: true })).toBeVisible();
  await page.getByLabel('问数问题').fill('改成上个月'); await page.getByRole('button', { name: '提交问题' }).click();
  await expect(page.getByText(/会话已更新/)).toBeVisible(); await expect(page.getByRole('button', { name: '提交问题' })).toBeDisabled(); await expect(page.getByText('9007199254740993.25', { exact: true })).toHaveCount(0); expect(submits).toBe(1);
  await page.getByRole('button', { name: '重新加载', exact: true }).click(); await expect(page.getByLabel('问数问题')).toBeEnabled();
});
test('revoked result access clears conversation metadata and history', async ({ page }) => {
  await fixtures(page, [ready]);
  await page.route('**/result', route => route.fulfill({ status: 403, json: envelope(null, 'FORBIDDEN', '主题权限已撤销') }));
  await page.goto('/ask/c1'); await expect(page.getByText(/主题权限已撤销/)).toBeVisible();
  await expect(page.getByText('查询销售额', { exact: true })).toHaveCount(0); await expect(page.getByText('9007199254740993.25', { exact: true })).toHaveCount(0);
  await expect(page.getByRole('link', { name: /月度销售/ })).toHaveCount(0);
});
test('navigation discards late preview and Back reloads authorized state without SQL replay', async ({ page }) => {
  await fixtures(page, [ready]); let release!: () => void; let entered!: () => void; const waiting = new Promise<void>(resolve => { entered = resolve; });
  await page.route('**/result', async route => { entered(); await new Promise<void>(resolve => { release = resolve; }); await route.fulfill({ json: envelope(result) }).catch(() => {}); });
  await page.goto('/ask/c1'); await waiting; await page.getByRole('link', { name: '新建会话', exact: true }).click(); await expect(page).toHaveURL(/\/ask$/); release();
  await expect(page.getByText('9007199254740993.25', { exact: true })).toHaveCount(0); await expect(page.getByRole('button', { name: '确认保存私有图表' })).toHaveCount(0);
  await page.route('**/result', route => route.fulfill({ json: envelope(result) })); const mutations: string[] = []; page.on('request', req => { if (req.method() === 'POST') mutations.push(req.url()); });
  await page.goBack(); await expect(page.getByText('9007199254740993.25', { exact: true })).toBeVisible(); expect(mutations).toEqual([]);
});
test('expired session while polling clears questions and redirects to login', async ({ page }) => {
  await fixtures(page, [{ ...ready, status: 'QUERYING', queryTime: null }]);
  await page.route('**/api/v1/conversations/c1/turns/t1', route => route.fulfill({ status: 401, json: envelope(null, 'UNAUTHORIZED', '登录已过期') }));
  await page.goto('/ask/c1'); await expect(page).toHaveURL(/\/login$/); await expect(page.getByText('查询销售额', { exact: true })).toHaveCount(0);
});
test('interrupted execution never presents a successful result or a save action', async ({ page }) => {
  await fixtures(page, [{ ...ready, status: 'INTERRUPTED', queryTime: null, errorCode: 'CONVERSATION_INTERRUPTED' }]);
  const paths: string[] = []; page.on('request', req => paths.push(new URL(req.url()).pathname));
  await page.goto('/ask/c1'); await expect(page.getByText('本轮未获得可用结果：CONVERSATION_INTERRUPTED')).toBeVisible();
  await expect(page.getByRole('button', { name: '确认保存私有图表' })).toHaveCount(0); expect(paths.some(path => path.endsWith('/result'))).toBe(false);
});
test('uncertain turn submission retries the same key and input without automatic replay', async ({ page }) => {
  await fixtures(page); const requests: Record<string, unknown>[] = [];
  await page.route('**/api/v1/conversations/c1/turns', route => {
    requests.push(route.request().postDataJSON());
    if (requests.length === 1) return route.abort();
    return route.fulfill({ json: envelope(ready) });
  });
  await page.goto('/ask/c1'); await page.getByLabel('问数问题').fill('查询销售额'); await page.getByRole('button', { name: '提交问题' }).click();
  await expect(page.getByText(/无法连接后端服务/)).toBeVisible(); expect(requests).toHaveLength(1);
  await page.route('**/api/v1/conversations/c1', route => route.fulfill({ json: envelope({ ...summary, version: 2, turns: [ready] }) }));
  await page.getByRole('button', { name: '提交问题' }).click();
  await expect(page.getByText('9007199254740993.25', { exact: true })).toBeVisible(); expect(requests).toHaveLength(2); expect(requests[0]).toEqual(requests[1]);
});
