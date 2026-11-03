import { expect, test, type Page } from '@playwright/test';
const envelope=(data:unknown,code='OK',message='成功')=>({code,message,data,requestId:'workspace-ui'});
const chart={id:'c1',name:'收入图表',ownerId:2,canManage:true,visibility:'PRIVATE',userIds:[],deleted:false,version:1,revisionId:'r1',definition:{subjectId:'s1',sql:'SELECT total FROM orders',parameters:[],metricDescription:'收入',timeRule:{kind:'NONE'},spec:{type:'TABLE',dimension:null,measures:[]}},lastSuccessAt:null};
const job={id:'e1',chartId:'c1',taskId:'t1',status:'SUCCEEDED',errorCode:null,expiresAt:'2026-11-03T15:15:00Z'};
async function setup(page:Page,admin=false) {
 await page.route('**/api/v1/**',route=>{
 const path=new URL(route.request().url()).pathname;
 if(path.endsWith('/auth/me'))return route.fulfill({json:envelope({id:2,username:'alice',role:admin?'SUPER_ADMIN':'USER',enabled:true})});
 if(path.endsWith('/auth/csrf'))return route.fulfill({json:envelope({token:'csrf',headerName:'X-CSRF-TOKEN'})});
 if(path==='/api/v1/charts')return route.fulfill({json:envelope({items:[chart],total:1,page:1,size:20})});
 if(path==='/api/v1/charts/recipients')return route.fulfill({json:envelope([{id:2,username:'alice'},{id:3,username:'bob'}])});
 if(path==='/api/v1/charts/c1')return route.fulfill({json:envelope(chart)});
 if(path.endsWith('/refreshes'))return route.fulfill({json:envelope({id:'t1',subjectId:'s1',status:'SUCCEEDED',errorCode:null,createdAt:'2026-11-03T15:00:00Z',finishedAt:'2026-11-03T15:00:01Z',truncated:false})});
 if(path.endsWith('/result'))return route.fulfill({json:envelope({columns:[{name:'total',jdbcType:'DECIMAL'}],rows:[['9007199254740993.0100']],rowLimit:1000,truncated:false})});
 if(path.endsWith('/permission'))return route.fulfill({json:envelope({allowed:true})});
 if(path.endsWith('/grants'))return route.fulfill({json:envelope([{userId:2,enabled:true}])});
 if(path.endsWith('/grant'))return route.fulfill({json:envelope(null)});
 if(path==='/api/v1/exports'||path==='/api/v1/exports/e1')return route.fulfill({json:envelope(job)});
 if(path.endsWith('/download'))return route.fulfill({contentType:'text/csv',body:'total\r\n9007199254740993.0100\r\n'});
 if(path==='/api/v1/audit-events')return route.fulfill({json:envelope({items:[{id:1,actorId:2,action:'EXPORT_DOWNLOAD',resourceType:'EXPORT',resourceId:'e1',outcome:'SUCCEEDED',requestId:'safe-event',occurredAt:'2026-11-03T15:00:00Z'}],total:1,page:1,size:20})});
 return route.fulfill({status:404,json:envelope(null,'NOT_FOUND')});
 });
}
test('workbench loads authorized metadata only and offers scoped search',async({page})=>{
 await setup(page);const posts:string[]=[];page.on('request',req=>{if(req.method()==='POST')posts.push(req.url());});
 await page.goto('/workspace');await expect(page.getByRole('heading',{name:'工作台',exact:true})).toBeVisible();await expect(page.getByRole('link',{name:'收入图表',exact:true})).toHaveCount(3);expect(posts).toHaveLength(0);
 await page.getByRole('link',{name:'查看与搜索共享给我'}).click();await page.getByLabel('搜索图表',{exact:true}).fill('收入_%');await page.getByRole('button',{name:'搜索',exact:true}).click();
 await expect(page).toHaveURL(/search=/);await expect(page.getByLabel('搜索图表',{exact:true})).toHaveValue('收入_%');expect(posts).toHaveLength(0);
 await page.screenshot({path:'test-results/m09-search.png',fullPage:true});
});
test('zero search results show no hidden names or raw counts',async({page})=>{
 await setup(page);await page.route('**/api/v1/charts?*',route=>route.fulfill({json:envelope({items:[],total:0,page:1,size:20})}));await page.goto('/charts/shared?search=private');await expect(page.getByText('没有找到匹配的图表。')).toBeVisible();await expect(page.getByText('收入图表',{exact:true})).toHaveCount(0);await expect(page.locator('.el-pagination')).toHaveCount(0);
});
test('ownership does not enable export without a separate grant',async({page})=>{
 await setup(page);await page.route('**/permission',route=>route.fulfill({json:envelope({allowed:false})}));await page.goto('/charts/c1');await expect(page.getByRole('button',{name:'创建 CSV 导出'})).toBeDisabled();await expect(page.getByText(/不自动获得导出权/)).toBeVisible();await expect(page.getByRole('button',{name:'管理导出授权'})).toHaveCount(0);
});
test('authorized export uses existing result and explicit download',async({page})=>{
 await setup(page);let creates=0,refreshes=0;page.on('request',req=>{if(req.method()==='POST'&&req.url().endsWith('/exports'))creates++;if(req.url().endsWith('/refreshes'))refreshes++;});
 await page.goto('/charts/c1');await expect(page.getByRole('button',{name:'创建 CSV 导出'})).toBeEnabled();await page.getByRole('button',{name:'创建 CSV 导出'}).click();await expect(page.getByRole('button',{name:'下载 CSV'})).toBeVisible();expect(creates).toBe(1);expect(refreshes).toBe(1);
 const received=page.waitForEvent('download');await page.getByRole('button',{name:'下载 CSV'}).click();expect((await received).suggestedFilename()).toBe('datalens-chart.csv');await expect(page.getByText(/已交给浏览器下载/)).toBeVisible();
});
test('revoked download clears job and never downloads error content',async({page})=>{
 await setup(page);let downloads=0;page.on('download',()=>downloads++);await page.goto('/charts/c1');await page.getByRole('button',{name:'创建 CSV 导出'}).click();await expect(page.getByRole('button',{name:'下载 CSV'})).toBeVisible();await page.route('**/download',route=>route.fulfill({status:403,json:envelope(null,'EXPORT_REVOKED','导出权限已撤销')}));await page.getByRole('button',{name:'下载 CSV'}).click();await expect(page.getByText(/导出权限已撤销/)).toBeVisible();await expect(page.getByRole('button',{name:'下载 CSV'})).toHaveCount(0);expect(downloads).toBe(0);
});
test('truncated results cannot be exported as a full dataset',async({page})=>{
 await setup(page);await page.route('**/result',route=>route.fulfill({json:envelope({columns:[{name:'total',jdbcType:'DECIMAL'}],rows:[['12']],rowLimit:1000,truncated:true})}));await page.goto('/charts/c1');await expect(page.getByText(/当前结果已截断/)).toBeVisible();await expect(page.getByRole('button',{name:'创建 CSV 导出'})).toBeDisabled();
});
test('navigating away cancels export polling and no stale job returns',async({page})=>{
 await setup(page);await page.route('**/api/v1/exports',route=>route.fulfill({json:envelope({...job,status:'QUEUED'})}));let polls=0;await page.route('**/api/v1/exports/e1',route=>{polls++;return route.fulfill({json:envelope({...job,status:'RUNNING'})});});await page.goto('/charts/c1');await page.getByRole('button',{name:'创建 CSV 导出'}).click();await expect(page.getByText('正在自动检查导出状态。')).toBeVisible();await page.getByRole('link',{name:'返回我的图表'}).click();const before=polls;await page.waitForTimeout(1700);expect(polls).toBe(before);await expect(page.getByRole('button',{name:'下载 CSV'})).toHaveCount(0);
});
test('admin explicitly grants and revokes with chart version, while audit contains safe metadata',async({page})=>{
 await setup(page,true);const changes:unknown[]=[];await page.route('**/api/v1/exports/charts/c1/grant',route=>{changes.push(route.request().postDataJSON());return route.fulfill({json:envelope(null)});});await page.goto('/charts/c1');await page.getByRole('button',{name:'管理导出授权'}).click();await page.getByLabel('导出用户',{exact:true}).selectOption('3');await page.getByRole('button',{name:'授予导出权'}).click();await expect(page.getByText(/导出授权已更新/)).toBeVisible();await page.getByRole('button',{name:'撤销导出权'}).click();await expect(page.getByText('导出授权已撤销，旧文件不能再下载。')).toBeVisible();expect(changes).toEqual([{userId:3,expectedChartVersion:1,enabled:true},{userId:3,expectedChartVersion:1,enabled:false}]);
 await page.getByRole('link',{name:'查询与操作日志'}).click();await expect(page.getByText('EXPORT_DOWNLOAD',{exact:true})).toBeVisible();await expect(page.getByText('safe-event',{exact:true})).toBeVisible();await page.screenshot({path:'test-results/m09-audit.png',fullPage:true});
});
test('ordinary user cannot enter the administrative audit view',async({page})=>{
 await setup(page);let auditReads=0;page.on('request',req=>{if(req.url().includes('/audit-events'))auditReads++;});await page.goto('/audit');await expect(page).toHaveURL(/\/workspace$/);await expect(page.getByRole('link',{name:'查询与操作日志'})).toHaveCount(0);expect(auditReads).toBe(0);
});
test('newer search navigation discards an older delayed result',async({page})=>{
 await setup(page);let release:()=>void=()=>{};const held=new Promise<void>(resolve=>{release=resolve;});
 await page.route('**/api/v1/charts?*',async route=>{const query=new URL(route.request().url()).searchParams.get('search');if(query==='old')await held;await route.fulfill({json:envelope({items:query==='old'?[{...chart,name:'旧结果'}]:[{...chart,name:'新结果'}],total:1,page:1,size:20})}).catch(()=>{});});
 await page.goto('/charts/mine');await page.getByLabel('搜索图表',{exact:true}).fill('old');await page.getByRole('button',{name:'搜索',exact:true}).click();await expect(page).toHaveURL(/search=old/);
 await page.getByRole('link',{name:'全员共享',exact:true}).first().click();release();await expect(page.getByRole('link',{name:'新结果',exact:true})).toBeVisible();await expect(page.getByRole('link',{name:'旧结果',exact:true})).toHaveCount(0);
});
