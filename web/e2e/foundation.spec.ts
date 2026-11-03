import { expect, test } from '@playwright/test';

test('foundation shell explains scope and reads status', async ({ page }) => {
  // UI-contract fixtures only; backend integration tests use real MySQL and Redis.
  await page.route('**/api/v1/system/info', (route) => route.fulfill({ json: {
    code: 'OK', message: '成功', data: { name: 'DataLens', version: '0.1.0', stage: 'M00', enabledModules: ['foundation'] },
    requestId: 'ui-fixture', timestamp: '2026-11-03T00:00:00Z',
  } }));
  await page.route('**/actuator/health/readiness', (route) => route.fulfill({ json: { status: 'UP' } }));
  await page.goto('/');
  await expect(page.getByRole('heading', { name: '从可靠的基础开始' })).toBeVisible();
  await expect(page.getByText('已连接', { exact: true })).toBeVisible();
  await page.screenshot({ path: 'test-results/foundation.png', fullPage: true });
  await page.getByRole('link', { name: '我的图表' }).click();
  await expect(page.getByText('该模块尚未实现')).toBeVisible();
  await expect(page.getByRole('heading', { name: '我的图表' })).toBeVisible();
});

test('backend failure is visible and never replaced by mock success', async ({ page }) => {
  await page.route('**/api/v1/system/info', (route) => route.abort());
  await page.route('**/actuator/health/readiness', (route) => route.abort());
  await page.goto('/');
  await expect(page.getByText('后端尚未就绪')).toBeVisible();
  await expect(page.getByText('未连接', { exact: true })).toBeVisible();
  await expect(page.getByText('已连接', { exact: true })).toHaveCount(0);
});
