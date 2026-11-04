import { expect, test, type Page } from '@playwright/test';
const envelope=(data:unknown,code='OK',message='成功')=>({code,message,data,requestId:'semantic-ui'});
const document={fields:[{id:'amount',name:'实付金额',aliases:['销售额'],physical:{tableName:'orders',columnName:'amount'},type:'DECIMAL',unit:'元',sensitivity:'INTERNAL'}],metrics:[{id:'sales',name:'净销售额',aliases:['销售额'],calculation:'已支付订单实付金额扣除退款',filters:'仅有效订单',timeFieldId:'amount',owner:'夹具负责人',unit:'元',grain:'每笔订单',nullHandling:'空值按零',timePolicy:'UTC 左闭右开',fieldIds:['amount'],sql:'SELECT SUM(orders.amount) FROM orders',parameters:[]}],dimensions:[],relations:[],examples:[{id:'sample',question:'有效订单销售额是多少？',metricIds:['sales'],sql:'SELECT SUM(orders.amount) FROM orders',parameters:[],verification:'仅测试夹具，不代表真实业务验收'}]};
const pub=(version=1)=>({subjectId:'subject-1',version,document,digest:'digest-fixture',scopeFingerprint:'scope-fixture',reviewerId:1,publisherId:1,publishedAt:'2026-11-03T12:00:00Z'});
const matches={publicationVersion:1,matches:[{kind:'METRIC',id:'sales',name:'净销售额',reasons:['别名匹配']}]};
async function setup(page:Page,admin=false){
  const state={version:1,reviewed:false,published:true,saves:0,reviews:0,publishes:0,withdraws:0,privateReads:0,document};
  const draft=()=>({subjectId:'subject-1',version:state.version,document:state.document,reviewed:state.reviewed,reviewerId:state.reviewed?1:null,reviewedAt:state.reviewed?'2026-11-03T12:00:00Z':null,publishedVersion:state.published?1:null,warnings:[]});
  await page.route('**/api/v1/**',async route=>{
    const path=new URL(route.request().url()).pathname,method=route.request().method();
    if(path.endsWith('/auth/me'))return route.fulfill({json:envelope({id:admin?1:2,username:admin?'admin':'alice',role:admin?'SUPER_ADMIN':'USER',enabled:true})});
    if(path.endsWith('/auth/csrf'))return route.fulfill({json:envelope({token:'csrf',headerName:'X-CSRF-TOKEN'})});
    if(path==='/api/v1/subjects'||path==='/api/v1/admin/subjects')return route.fulfill({json:envelope([{id:'subject-1',name:'测试销售主题',fields:[],deniedFields:[],userIds:[],shareScope:'NONE'},{id:'subject-2',name:'第二主题',fields:[],deniedFields:[],userIds:[],shareScope:'NONE'}])});
    if(path.endsWith('/metadata'))return route.fulfill({json:envelope([{tableName:'orders',columnName:'amount',typeName:'DECIMAL'}])});
    if(path.endsWith('/draft')){state.privateReads++;if(method==='PUT'){state.saves++;state.version++;state.reviewed=false;state.document=route.request().postDataJSON().document;}return route.fulfill({json:envelope(draft())});}
    if(path.endsWith('/review')){state.reviews++;state.reviewed=true;state.version++;return route.fulfill({json:envelope(draft())});}
    if(path.endsWith('/publish')){state.publishes++;state.version++;state.published=true;return route.fulfill({json:envelope(pub())});}
    if(path.endsWith('/withdraw')){state.withdraws++;state.version++;state.published=false;return route.fulfill({json:envelope({...draft(),document:null,reviewed:false,reviewerId:null,reviewedAt:null})});}
    if(path.endsWith('/published'))return state.published?route.fulfill({json:envelope(pub())}):route.fulfill({status:404,json:envelope(null,'SEMANTIC_NOT_PUBLISHED','尚无发布')});
    if(path.endsWith('/search'))return route.fulfill({json:envelope(matches)});
    if(path.endsWith('/releases')){state.privateReads++;return route.fulfill({json:envelope([pub()])});}
    return route.fulfill({status:404,json:envelope(null,'NOT_FOUND')});
  });
  await page.goto('/semantics');await expect(page.getByRole('heading',{name:'业务语义与指标',exact:true})).toBeVisible();
  await page.getByLabel('语义业务主题',{exact:true}).selectOption('subject-1');
  await expect(page.getByText('指标 · 净销售额',{exact:true})).toBeVisible();return state;
}
test('ordinary users search aliases and published version only, with no draft or history request',async({page})=>{
  const state=await setup(page);await expect(page.getByRole('heading',{name:'管理员草稿与发布'})).toHaveCount(0);
  await page.getByLabel('语义检索词').fill('销售额');await page.getByRole('button',{name:'检索已发布口径'}).click();await expect(page.getByText('别名匹配',{exact:true})).toBeVisible();
  await page.getByText('查看当前发布版本的完整定义',{exact:true}).click();await expect(page.getByText('已支付订单实付金额扣除退款',{exact:true})).toBeVisible();expect(state.privateReads).toBe(0);
  await page.screenshot({path:'test-results/semantics-published.png',fullPage:true});
});
test('admin explicitly reviews saved content before publishing and every mutation includes version and CSRF',async({page})=>{
  const state=await setup(page,true);await expect(page.getByRole('button',{name:'发布已审核版本'})).toBeDisabled();await expect(page.getByRole('button',{name:'记录人工审核'})).toBeDisabled();
  await page.getByLabel('人工审核确认').check();const request=page.waitForRequest(req=>req.url().endsWith('/review'));
  await page.getByRole('button',{name:'记录人工审核'}).click();const sent=await request;expect(sent.postDataJSON()).toEqual({expectedVersion:1,confirmation:true});expect(sent.headers()['x-csrf-token']).toBe('csrf');
  await expect(page.getByRole('button',{name:'发布已审核版本'})).toBeEnabled();await page.getByRole('button',{name:'发布已审核版本'}).click();await expect(page.getByText(/已发布不可变版本/)).toBeVisible();expect(state.reviews).toBe(1);expect(state.publishes).toBe(1);expect(state.saves).toBe(0);
});
test('editing invalidates local review and requires new save and confirmation',async({page})=>{
  await setup(page,true);await page.getByLabel('人工审核确认').check();await page.getByRole('button',{name:'记录人工审核'}).click();await expect(page.getByRole('button',{name:'发布已审核版本'})).toBeEnabled();
  const changed={...document,metrics:[{...document.metrics[0],calculation:'新计算口径'}]};await page.getByLabel('语义草稿 JSON').fill(JSON.stringify(changed));
  await expect(page.getByRole('button',{name:'发布已审核版本'})).toBeDisabled();await expect(page.getByLabel('人工审核确认')).toBeDisabled();await page.getByRole('button',{name:'保存草稿',exact:true}).click();
  await expect(page.getByText(/草稿已保存/)).toBeVisible();await expect(page.getByRole('button',{name:'发布已审核版本'})).toBeDisabled();await expect(page.getByLabel('人工审核确认')).not.toBeChecked();
});
test('duplicate save dispatches once and invalid JSON remains editable without server request',async({page})=>{
  const state=await setup(page,true);await page.getByLabel('语义草稿 JSON').fill('{ broken');await page.getByRole('button',{name:'保存草稿',exact:true}).click();await expect(page.getByText('语义文档必须是有效的 JSON 对象',{exact:true})).toBeVisible();expect(state.saves).toBe(0);await expect(page.getByLabel('语义草稿 JSON')).toBeEnabled();
  await page.getByLabel('语义草稿 JSON').fill(JSON.stringify(document));let saves=0;await page.route('**/semantics/subject-1/draft',async route=>{if(route.request().method()!=='PUT')return route.fallback();saves++;await new Promise(resolve=>setTimeout(resolve,250));return route.fulfill({json:envelope({subjectId:'subject-1',version:2,document,reviewed:false,warnings:[],publishedVersion:1})});});
  await page.getByRole('button',{name:'保存草稿',exact:true}).dblclick();await expect(page.getByText(/草稿已保存/)).toBeVisible();expect(saves).toBe(1);
});
test('stale optimistic version prevents blind retries and reload restores server truth',async({page})=>{
  await setup(page,true);await page.route('**/semantics/subject-1/draft',route=>route.request().method()==='PUT'?route.fulfill({status:409,json:envelope(null,'SEMANTIC_VERSION_CONFLICT','语义已更新，请重新加载')}):route.fallback());
  await page.getByRole('button',{name:'保存草稿',exact:true}).click();await expect(page.getByText(/语义已更新，请重新加载/)).toBeVisible();await expect(page.getByRole('button',{name:'保存草稿',exact:true})).toBeDisabled();await expect(page.getByRole('button',{name:'发布已审核版本'})).toBeDisabled();
  await page.getByRole('button',{name:'重新载入（放弃本地修改）'}).click();await expect(page.getByRole('button',{name:'保存草稿',exact:true})).toBeEnabled();
});
test('permission denial clears previous published content and never falls back to stale results',async({page})=>{
  await setup(page);await page.route('**/semantics/subject-1/published',route=>route.fulfill({status:403,json:envelope(null,'FORBIDDEN','主题权限已撤销')}));
  await page.getByRole('button',{name:'检索已发布口径'}).click();await expect(page.getByText(/主题权限已撤销/)).toBeVisible();await expect(page.getByText('指标 · 净销售额',{exact:true})).toHaveCount(0);
});
test('changing subject aborts slow reads and prevents old subject response replacing new state',async({page})=>{
  await setup(page);await page.route('**/semantics/subject-1/published',async route=>{await new Promise(resolve=>setTimeout(resolve,500));await route.fulfill({json:envelope(pub())});});
  await page.route('**/semantics/subject-2/published',route=>route.fulfill({status:404,json:envelope(null,'SEMANTIC_NOT_PUBLISHED','尚无发布')}));
  await page.getByRole('button',{name:'检索已发布口径'}).click();await page.getByLabel('语义业务主题').selectOption('subject-2');await expect(page.getByText(/此主题尚无有效发布版本/)).toBeVisible();await page.waitForTimeout(600);await expect(page.getByText('指标 · 净销售额',{exact:true})).toHaveCount(0);
});
test('withdraw requires confirmation and removes all current publication results',async({page})=>{
  const state=await setup(page,true);page.once('dialog',dialog=>dialog.dismiss());await page.getByRole('button',{name:'撤回当前发布'}).click();expect(state.withdraws).toBe(0);await expect(page.getByText('指标 · 净销售额',{exact:true})).toBeVisible();
  page.once('dialog',dialog=>dialog.accept());await page.getByRole('button',{name:'撤回当前发布'}).click();await expect(page.getByText('当前发布已撤回，语义检索已停止。',{exact:true})).toBeVisible();await expect(page.getByText('指标 · 净销售额',{exact:true})).toHaveCount(0);expect(state.withdraws).toBe(1);
});
test('publication changed during retrieval is not rendered as a mixed version',async({page})=>{
  await setup(page);await page.route('**/semantics/subject-1/search?*',route=>route.fulfill({json:envelope({...matches,publicationVersion:2})}));await page.getByRole('button',{name:'检索已发布口径'}).click();await expect(page.getByText('发布版本在读取期间改变，请重新检索',{exact:true})).toBeVisible();await expect(page.getByText('指标 · 净销售额',{exact:true})).toHaveCount(0);
});
test('leaving and returning does not resurrect prior editor or publication from late responses',async({page})=>{
  await setup(page,true);await page.route('**/semantics/subject-1/published',async route=>{await new Promise(resolve=>setTimeout(resolve,500));await route.fulfill({json:envelope(pub())});});
  await page.getByRole('button',{name:'检索已发布口径'}).click();await page.getByRole('link',{name:'分析空间',exact:true}).click();await page.waitForTimeout(600);await expect(page.getByRole('heading',{name:'管理员草稿与发布'})).toHaveCount(0);await page.goBack();await expect(page.getByLabel('语义业务主题')).toHaveValue('');await expect(page.getByText('指标 · 净销售额',{exact:true})).toHaveCount(0);
});

test('admin can withdraw redacted publication after source disable without requiring readable metadata',async({page})=>{
  const state=await setup(page,true);
  await page.route('**/semantics/subject-1/draft',route=>route.fulfill({json:envelope({subjectId:'subject-1',version:7,document:null,reviewed:false,publishedVersion:1,warnings:['数据范围已变化，仅保留撤回所需版本']})}));
  await page.route('**/subjects/subject-1/metadata',route=>route.fulfill({status:409,json:envelope(null,'SOURCE_DISABLED','数据源已停用')}));
  await page.route('**/semantics/subject-1/published',route=>route.fulfill({status:409,json:envelope(null,'SOURCE_DISABLED','数据源已停用')}));
  await page.getByRole('button',{name:'重新载入（放弃本地修改）'}).click();await expect(page.getByRole('button',{name:'撤回当前发布'})).toBeEnabled();await expect(page.getByText('指标 · 净销售额',{exact:true})).toHaveCount(0);
  const request=page.waitForRequest(req=>req.url().endsWith('/withdraw'));page.once('dialog',dialog=>dialog.accept());await page.getByRole('button',{name:'撤回当前发布'}).click();expect((await request).postDataJSON()).toEqual({expectedVersion:7});await expect(page.getByText('当前发布已撤回，语义检索已停止。',{exact:true})).toBeVisible();expect(state.withdraws).toBe(1);
});

test('admin keeps only redacted withdrawal controls when effective field scope becomes empty',async({page})=>{
  await setup(page,true);
  await page.route('**/semantics/subject-1/draft',route=>route.fulfill({json:envelope({subjectId:'subject-1',version:7,document:null,reviewed:false,publishedVersion:1,warnings:['授权范围已变化']})}));
  await page.route('**/subjects/subject-1/metadata',route=>route.fulfill({status:403,json:envelope(null,'FORBIDDEN','无可用字段')}));
  await page.route('**/semantics/subject-1/published',route=>route.fulfill({status:403,json:envelope(null,'FORBIDDEN','无可用字段')}));
  await page.getByRole('button',{name:'重新载入（放弃本地修改）'}).click();await expect(page.getByRole('button',{name:'撤回当前发布'})).toBeEnabled();await expect(page.getByRole('button',{name:'发布已审核版本'})).toBeDisabled();await expect(page.getByText('指标 · 净销售额',{exact:true})).toHaveCount(0);expect(await page.getByLabel('语义草稿 JSON').inputValue()).not.toContain('净销售额');
});
test('new workspace starts with empty arrays and cannot imply any human review',async({page})=>{
  await setup(page,true);await page.route('**/semantics/subject-2/draft',route=>route.fulfill({json:envelope({subjectId:'subject-2',version:0,document:null,reviewed:false,publishedVersion:null,warnings:[]})}));
  await page.route('**/semantics/subject-2/published',route=>route.fulfill({status:404,json:envelope(null,'SEMANTIC_NOT_PUBLISHED','尚无发布')}));
  await page.getByLabel('语义业务主题').selectOption('subject-2');await expect(page.getByText(/此主题尚无有效发布版本/)).toBeVisible();await expect(page.getByLabel('人工审核确认')).toBeDisabled();await expect(page.getByRole('button',{name:'发布已审核版本'})).toBeDisabled();expect(JSON.parse(await page.getByLabel('语义草稿 JSON').inputValue())).toEqual({fields:[],metrics:[],dimensions:[],relations:[],examples:[]});
});
