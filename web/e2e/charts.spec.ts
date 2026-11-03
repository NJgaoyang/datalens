import { expect, test, type Page } from '@playwright/test';
const envelope = (data: unknown, code = 'OK', message = '成功') => ({code,message,data,requestId:'charts-ui'});
const definition = {subjectId:'s1',sql:'SELECT orders.total FROM orders',parameters:[],metricDescription:'收入',timeRule:{kind:'NONE'},spec:{type:'TABLE',dimension:null,measures:[]}};
const chart = {id:'c1',name:'收入图表',ownerId:2,canManage:true,visibility:'PRIVATE',userIds:[],deleted:false,version:1,revisionId:'r1',definition,lastSuccessAt:'2026-11-02T12:00:00Z',latestTaskId:'t1'};
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
test('shared viewer sees current data and metric, without SQL or management controls', async ({page}) => {
  await setup(page);
  await page.route('**/api/v1/charts/c1', route => route.fulfill({json:envelope({...chart,canManage:false,visibility:'SPECIFIC',definition:{...definition,sql:'',parameters:[]}})}));
  await page.goto('/charts/c1'); await expect(page.getByText('9007199254740993.0100',{exact:true})).toBeVisible();
  await expect(page.getByText('只读共享图表。编辑、共享和删除仅限所有者或超管。')).toBeVisible();
  for (const name of ['编辑图表','管理共享','移入回收站','转移所有权']) await expect(page.getByRole('button',{name,exact:true})).toHaveCount(0);
  await expect(page.getByLabel('图表 SQL')).toHaveCount(0);
});
test('owner shares with selected users then revokes to private without sending stale recipient IDs', async ({page}) => {
  await setup(page); let current = {...chart}; const changes: unknown[] = [];
  await page.route('**/api/v1/charts/c1', route => route.fulfill({json:envelope(current)}));
  await page.route('**/api/v1/charts/recipients', route => route.fulfill({json:envelope([{id:2,username:'alice'},{id:3,username:'bob'}])}));
  await page.route('**/api/v1/charts/c1/sharing', route => {const body = route.request().postDataJSON();changes.push(body);current = {...current,...body,version:current.version+1};return route.fulfill({json:envelope(current)});});
  await page.goto('/charts/c1'); await page.getByRole('button',{name:'管理共享'}).click();
  await page.getByLabel('共享范围',{exact:true}).selectOption('SPECIFIC'); await page.getByRole('button',{name:'保存共享范围'}).click(); await expect(page.getByText('请选择至少一位共享用户')).toBeVisible(); expect(changes).toHaveLength(0);
  await page.getByLabel('共享用户',{exact:true}).selectOption('3'); await page.getByRole('button',{name:'保存共享范围'}).click();
  await expect(page.getByRole('heading',{name:'当前版本 2'})).toBeVisible(); expect(changes[0]).toEqual({visibility:'SPECIFIC',userIds:[3],expectedVersion:1});
  await page.getByRole('button',{name:'管理共享'}).click(); await page.getByLabel('共享范围',{exact:true}).selectOption('PRIVATE'); await page.getByRole('button',{name:'保存共享范围'}).click();
  await expect(page.getByRole('heading',{name:'当前版本 3'})).toBeVisible(); expect(changes[1]).toEqual({visibility:'PRIVATE',userIds:[],expectedVersion:2});
});
test('list tabs use authorized scopes and trash restores without dispatching a query', async ({page}) => {
  await setup(page); const scopes: string[] = []; let restored = false, refreshes = 0;
  await page.route('**/api/v1/charts?*', route => {const scope = new URL(route.request().url()).searchParams.get('scope')!;scopes.push(scope);return route.fulfill({json:envelope({items:scope === 'trash' && !restored ? [{...chart,deleted:true}] : [],total:scope === 'trash' && !restored ? 1:0,page:1,size:20})});});
  await page.route('**/api/v1/charts/c1/restore', route => {expect(route.request().postDataJSON()).toEqual({expectedVersion:1});restored=true;return route.fulfill({json:envelope({...chart,version:2})});});
  page.on('request', req => {if(req.url().endsWith('/refreshes')) refreshes++;});
  await page.goto('/charts/shared'); await expect(page.getByRole('heading',{name:'共享给我',exact:true})).toBeVisible();
  await page.locator('nav[aria-label="图表范围"]').getByRole('link',{name:'全员共享'}).click(); await expect(page.getByRole('heading',{name:'全员共享',exact:true})).toBeVisible();
  await page.locator('nav[aria-label="图表范围"]').getByRole('link',{name:'回收站'}).click(); await page.getByRole('button',{name:'恢复图表'}).click(); await expect(page.getByText('暂无图表。')).toBeVisible();
  expect(scopes).toEqual(['shared','all','trash','trash']); expect(refreshes).toBe(0);
});
test('admin transfers ownership with current version and real actor session', async ({page}) => {
  await setup(page); let transferred = false;
  await page.route('**/api/v1/auth/me', route => route.fulfill({json:envelope({id:1,username:'admin',role:'SUPER_ADMIN',enabled:true})}));
  await page.route('**/api/v1/charts/recipients', route => route.fulfill({json:envelope([{id:3,username:'bob'}])}));
  await page.route('**/api/v1/charts/c1/owner', route => {expect(route.request().postDataJSON()).toEqual({ownerId:3,expectedVersion:1});transferred=true;return route.fulfill({json:envelope({...chart,ownerId:3,version:2})});});
  page.on('dialog', dialog => dialog.accept());
  await page.goto('/charts/c1'); await page.getByRole('button',{name:'管理共享'}).click(); await page.getByLabel('新所有者').selectOption('3'); await page.getByRole('button',{name:'转移所有权'}).click(); await expect.poll(() => transferred).toBe(true);
});
test('soft delete confirms once and navigates to trash; cancel performs no mutation', async ({page}) => {
  await setup(page); let deletions = 0;
  await page.route('**/api/v1/charts/c1?expectedVersion=*', route => {expect(route.request().method()).toBe('DELETE');expect(new URL(route.request().url()).searchParams.get('expectedVersion')).toBe('1');deletions++;return route.fulfill({json:envelope(null)});});
  await page.goto('/charts/c1'); page.once('dialog', dialog => dialog.dismiss()); await page.getByRole('button',{name:'移入回收站'}).click(); expect(deletions).toBe(0);
  page.once('dialog', dialog => dialog.accept()); await page.getByRole('button',{name:'移入回收站'}).click(); await expect(page).toHaveURL(/\/charts\/trash$/); expect(deletions).toBe(1);
});
test('late recipient response cannot reopen management after navigation', async ({page}) => {
  await setup(page); let release!: () => void;
  const gate = new Promise<void>(resolve => {release=resolve;});
  await page.route('**/api/v1/charts/recipients', async route => {await gate;await route.fulfill({json:envelope([{id:3,username:'bob'}])});});
  await page.goto('/charts/c1'); await page.getByRole('button',{name:'管理共享'}).click(); await page.getByRole('link',{name:'返回我的图表'}).click(); release();
  await expect(page.getByRole('heading',{name:'我的图表',exact:true})).toBeVisible(); await expect(page.getByRole('heading',{name:'共享与管理'})).toHaveCount(0);
});
test('revoking a viewer during refresh clears chart and blocks retained task data', async ({page}) => {
  await setup(page);
  await page.route('**/api/v1/charts/c1', route => route.fulfill({json:envelope({...chart,canManage:false,visibility:'SPECIFIC',definition:{...definition,sql:'',parameters:[]}})}));
  await page.route('**/api/v1/charts/c1/refreshes', route => route.fulfill({json:envelope(task('RUNNING'))}));
  await page.route('**/api/v1/query-tasks/t1', route => route.fulfill({status:403,json:envelope(null,'FORBIDDEN','共享已撤销')}));
  await page.goto('/charts/c1'); await expect(page.getByText(/共享已撤销/)).toBeVisible();
  await expect(page.getByRole('heading',{name:'收入图表',exact:true})).toHaveCount(0); await expect(page.getByText('9007199254740993.0100',{exact:true})).toHaveCount(0); await expect(page.getByRole('button',{name:'重新刷新'})).toHaveCount(0);
});
test('deleted chart detail never dispatches a refresh and exposes restore only', async ({page}) => {
  await setup(page); let refreshes = 0;
  await page.route('**/api/v1/charts/c1', route => route.fulfill({json:envelope({...chart,deleted:true})}));
  page.on('request', req => {if(req.url().endsWith('/refreshes')) refreshes++;});
  await page.goto('/charts/c1'); await expect(page.getByRole('button',{name:'恢复图表'})).toBeVisible();
  await expect(page.getByRole('button',{name:'重新刷新'})).toHaveCount(0); await expect(page.getByRole('button',{name:'编辑图表',exact:true})).toHaveCount(0); expect(refreshes).toBe(0);
});
test('sharing policy denial clears management without inventing a successful change', async ({page}) => {
  await setup(page);
  await page.route('**/api/v1/charts/recipients', route => route.fulfill({json:envelope([{id:3,username:'bob'}])}));
  await page.route('**/api/v1/charts/c1/sharing', route => route.fulfill({status:403,json:envelope(null,'FORBIDDEN','业务主题不允许此共享范围')}));
  await page.goto('/charts/c1'); await page.getByRole('button',{name:'管理共享'}).click(); await page.getByLabel('共享范围',{exact:true}).selectOption('ALL'); await page.getByRole('button',{name:'保存共享范围'}).click();
  await expect(page.getByText(/业务主题不允许此共享范围/)).toBeVisible(); await expect(page.getByRole('heading',{name:'共享与管理'})).toHaveCount(0); await expect(page.getByText('9007199254740993.0100',{exact:true})).toHaveCount(0);
});
