import { expect, test, type Page } from '@playwright/test';
const envelope = (data: unknown, code = 'OK', message = '成功') => ({code,message,data,requestId:'charts-ui'});
const definition = {subjectId:'s1',sql:'SELECT orders.total FROM orders',parameters:[],metricDescription:'收入',timeRule:{kind:'NONE'},spec:{type:'TABLE',dimension:null,measures:[]}};
const chart = {id:'c1',name:'收入图表',ownerId:'2',version:1,revisionId:'r1',definition,lastSuccessAt:'2026-11-02T12:00:00Z',latestTaskId:'t1'};
const task = (status = 'SUCCEEDED') => ({id:'t1',subjectId:'s1',status,errorCode:status === 'FAILED' ? 'QUERY_FAILED' : null,createdAt:'2026-11-03T12:00:00Z',finishedAt:null,durationMs:20,truncated:false});
const result = {columns:[{name:'total',jdbcType:'DECIMAL'}],rows:[['9007199254740993.0100']],rowLimit:1000,truncated:false};
async function setup(page: Page) {
  await page.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/auth/me')) return route.fulfill({json:envelope({id:2,username:'alice',role:'USER',enabled:true})});
    if (path.endsWith('/auth/csrf')) return route.fulfill({json:envelope({token:'csrf',headerName:'X-CSRF-TOKEN'})});
    if (path === '/api/v1/subjects') return route.fulfill({json:envelope([{id:'s1',name:'销售主题'}])});
    if (path === '/api/v1/charts' && route.request().method() === 'GET') return route.fulfill({json:envelope({items:[chart],total:1,page:1,size:20})});
    if (path === '/api/v1/charts/previews') return route.fulfill({json:envelope({id:'p1',definition:route.request().postDataJSON().definition,taskId:'t1'})});
    if (path.endsWith('/result')) return route.fulfill({json:envelope(result)});
    if (path === '/api/v1/query-tasks/t1' || path.endsWith('/refreshes')) return route.fulfill({json:envelope(task())});
    if (path === '/api/v1/charts/c1' || path === '/api/v1/charts' || path.endsWith('/revisions')) return route.fulfill({json:envelope(chart)});
    return route.fulfill({status:404,json:envelope(null,'NOT_FOUND')});
  });
}
async function fill(page: Page) {
  await page.goto('/charts/new'); await page.getByLabel('图表名称',{exact:true}).fill('收入图表'); await page.getByLabel('图表业务主题').selectOption('s1'); await page.getByLabel('图表 SQL').fill(definition.sql);
}
test('preview does not save; explicit save creates private asset and entering detail refreshes', async ({page}) => {
  await setup(page); let saves = 0, refreshes = 0;
  page.on('request', req => { if (req.method() === 'POST' && req.url().endsWith('/charts')) saves++; if (req.url().endsWith('/refreshes')) refreshes++; });
  await fill(page); await expect(page.getByRole('button',{name:'确认保存图表'})).toHaveCount(0); await page.getByRole('button',{name:'执行预览'}).click(); await expect(page.getByText('9007199254740993.0100',{exact:true})).toBeVisible(); expect(saves).toBe(0);
  await page.getByRole('button',{name:'确认保存图表'}).click(); await expect(page).toHaveURL(/\/charts\/c1$/); await expect(page.getByText('9007199254740993.0100',{exact:true})).toBeVisible(); expect(saves).toBe(1); expect(refreshes).toBe(1);
  await page.screenshot({path:'test-results/chart-detail.png',fullPage:true});
});
test('editing invalidates preview and only explicitly publishes a new revision', async ({page}) => {
  await setup(page); let revisions = 0;
  await page.route('**/api/v1/charts/c1/revisions', route => { revisions++; expect(route.request().postDataJSON()).toEqual({name:'收入图表',previewId:'p1',expectedVersion:1}); return route.fulfill({json:envelope({...chart,version:2})}); });
  await page.goto('/charts/c1'); await expect(page.getByText('9007199254740993.0100',{exact:true})).toBeVisible(); await page.getByRole('button',{name:'编辑图表',exact:true}).click(); await page.getByRole('button',{name:'执行预览'}).click(); await expect(page.getByRole('button',{name:'确认发布新版本'})).toBeVisible();
  await page.getByLabel('指标口径').fill('新口径'); await expect(page.getByRole('button',{name:'确认发布新版本'})).toHaveCount(0); expect(revisions).toBe(0);
  await page.getByRole('button',{name:'执行预览'}).click(); await page.getByRole('button',{name:'确认发布新版本'}).click(); await expect.poll(() => revisions).toBe(1);
});
test('failed refresh retains labeled last success time but never displays old data', async ({page}) => {
  await setup(page); await page.goto('/charts/c1'); await expect(page.getByText('9007199254740993.0100',{exact:true})).toBeVisible();
  await page.route('**/api/v1/charts/c1/refreshes', route => route.fulfill({json:envelope(task('FAILED'))})); await page.getByRole('button',{name:'重新刷新'}).click(); await expect(page.getByText('本次刷新失败：QUERY_FAILED')).toBeVisible(); await expect(page.getByText('9007199254740993.0100',{exact:true})).toHaveCount(0); await expect(page.getByText('最近成功：2026-11-02T12:00:00Z')).toBeVisible();
});
test('result authorization failure clears all previously visible result and chart state', async ({page}) => {
  await setup(page); await page.goto('/charts/c1'); await expect(page.getByText('9007199254740993.0100',{exact:true})).toBeVisible();
  await page.route('**/api/v1/charts/c1/refreshes/t1/result', route => route.fulfill({status:403,json:envelope(null,'FORBIDDEN','主题权限已撤销')})); await page.getByRole('button',{name:'重新刷新'}).click(); await expect(page.getByText(/主题权限已撤销/)).toBeVisible(); await expect(page.getByText('9007199254740993.0100',{exact:true})).toHaveCount(0); await expect(page.getByRole('button',{name:'编辑图表',exact:true})).toHaveCount(0);
});
test('navigating away aborts polling and stale results do not reappear', async ({page}) => {
  await setup(page); let polls = 0; await page.route('**/api/v1/charts/c1/refreshes', route => route.fulfill({json:envelope(task('RUNNING'))})); await page.route('**/api/v1/query-tasks/t1', route => {polls++;return route.fulfill({json:envelope(task('RUNNING'))});});
  await page.goto('/charts/c1'); await expect(page.getByRole('heading',{name:'执行中',exact:true})).toBeVisible(); await page.getByRole('link',{name:'返回我的图表'}).click(); const before = polls; await page.waitForTimeout(1500); expect(polls).toBe(before); await expect(page.getByRole('heading',{name:'执行中',exact:true})).toHaveCount(0);
});
test('KPI preserves exact decimal strings and refuses truncated or multirow aggregates', async ({page}) => {
  await setup(page); await fill(page); await page.getByLabel('图表类型').selectOption('KPI'); await page.getByLabel('指标列',{exact:true}).fill('total'); await page.getByRole('button',{name:'执行预览'}).click(); await expect(page.locator('.chart-kpi strong')).toHaveText('9007199254740993.0100');
  await page.route('**/api/v1/charts/previews/p1/result', route => route.fulfill({json:envelope({...result,truncated:true})})); await page.getByRole('button',{name:'执行预览'}).click(); await expect(page.getByText(/KPI 需要至多一行完整/)).toBeVisible(); await expect(page.getByRole('button',{name:'确认保存图表'})).toHaveCount(0);
});
for (const chartType of ['BAR','LINE','PIE']) test(`${chartType} renders using safe mapped columns and preserves exact detail values`, async ({page}) => {
  await setup(page); await page.route('**/api/v1/charts/previews/p1/result', route => route.fulfill({json:envelope({columns:[{name:'category',jdbcType:'VARCHAR'},{name:'total',jdbcType:'DECIMAL'}],rows:[['A','9007199254740993.0100']],truncated:false,rowLimit:1000})}));
  await fill(page); await page.getByLabel('图表类型').selectOption(chartType); await page.getByLabel('维度列',{exact:true}).fill('category'); await page.getByLabel('指标列',{exact:true}).fill('total'); await page.getByRole('button',{name:'执行预览'}).click();
  await expect(page.getByRole('img',{name:`${chartType} 图表`})).toBeVisible(); await expect(page.locator('.chart-canvas canvas')).toHaveCount(1); await page.getByText('查看精确数据',{exact:true}).click(); await expect(page.getByText('9007199254740993.0100',{exact:true})).toBeVisible();
});
test('preview schema validation failure blocks saving and surfaces server reason', async ({page}) => {
  await setup(page); await page.route('**/api/v1/charts/previews/p1/result', route => route.fulfill({status:422,json:envelope(null,'CHART_INVALID','维度字段无效')})); await fill(page); await page.getByRole('button',{name:'执行预览'}).click(); await expect(page.getByText(/维度字段无效/)).toBeVisible(); await expect(page.getByRole('button',{name:'确认保存图表'})).toHaveCount(0);
});
test('uncertain preview submission reuses the same idempotent input on retry', async ({page}) => {
  await setup(page); const requests: unknown[] = []; await page.route('**/api/v1/charts/previews', route => {const body = route.request().postDataJSON();requests.push(body);return requests.length === 1 ? route.abort() : route.fulfill({json:envelope({id:'p1',definition:body.definition,taskId:'t1'})});});
  await fill(page); await page.getByRole('button',{name:'执行预览'}).click(); await expect(page.getByText(/无法连接后端服务/)).toBeVisible(); await page.getByRole('button',{name:'执行预览'}).click(); await expect(page.getByRole('button',{name:'确认保存图表'})).toBeVisible(); expect(requests).toHaveLength(2); expect(requests[0]).toEqual(requests[1]);
});
