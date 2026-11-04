import { expect, test } from '@playwright/test';
test.beforeEach(async ({ page }) => {
  await page.route('**/api/v1/auth/me', route => route.fulfill({ json: { code: 'OK', message: '成功', data: { id: 1, username: 'admin', role: 'SUPER_ADMIN', enabled: true }, requestId: 'ui-fixture' } }));
});
test('system status moved out of the default workspace and remains reachable for administrators', async ({ page }) => {
  await page.route('**/api/v1/system/info', route => route.fulfill({ json: { code: 'OK', message: '成功', data: { name: 'DataLens', version: '0.1.0', stage: 'M00', enabledModules: ['foundation'] }, requestId: 'ui-fixture' } }));
  await page.route('**/actuator/health/readiness', route => route.fulfill({ json: { status: 'UP' } }));
  await page.goto('/system'); await expect(page.getByRole('heading', { name: '从可靠的基础开始' })).toBeVisible(); await expect(page.getByText('已连接', { exact: true })).toBeVisible();
  await page.screenshot({ path: 'test-results/foundation.png', fullPage: true });
  await page.route('**/api/v1/charts?*', route => route.fulfill({ json: { code: 'OK', message: '成功', data: { items: [], total: 0, page: 1, size: 20 }, requestId: 'ui-fixture' } }));
  await page.getByRole('link', { name: '我的图表', exact: true }).click(); await expect(page.getByText('暂无图表。')).toBeVisible(); await expect(page.getByRole('heading', { name: '我的图表' })).toBeVisible();
});
test('backend failure is visible and never replaced by mock success', async ({ page }) => {
  await page.route('**/api/v1/system/info', route => route.abort()); await page.route('**/actuator/health/readiness', route => route.abort());
  await page.goto('/system'); await expect(page.getByText('后端尚未就绪')).toBeVisible(); await expect(page.getByText('未连接', { exact: true })).toBeVisible(); await expect(page.getByText('已连接', { exact: true })).toHaveCount(0);
});
