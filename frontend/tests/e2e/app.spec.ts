import { expect, test, type Page } from '@playwright/test';

const NAV_VIEWS: { label: string; slug: string }[] = [
  { label: '概览', slug: 'dashboard' },
  { label: '通知', slug: 'notifications' },
  { label: '资料', slug: 'canvas' },
  { label: '邮箱', slug: 'mail' },
  { label: '日程', slug: 'timetable' },
  { label: '课堂视频', slug: 'classroom' },
  { label: '云盘', slug: 'cloudpan' },
  { label: '水源社区', slug: 'shuiyuan' },
  { label: '选课社区', slug: 'course' },
  { label: '传承交大', slug: 'share' },
  { label: 'TODO', slug: 'todo' },
  { label: '工作台', slug: 'workspace' },
  { label: '设置', slug: 'settings' },
];

const SETTINGS_SECTIONS = ['账户与连接', '外观', '通用与通知', '存储与备份', '关于'];

function collectErrors(page: Page) {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(`pageerror: ${error.message}`));
  page.on('console', (message) => {
    if (message.type() !== 'error') return;
    const text = message.text();
    // mock 数据里的头像路径没有真实资源，浏览器会报 404；不是应用错误。
    if (text.includes('Failed to load resource')) return;
    errors.push(`console: ${text}`);
  });
  return errors;
}

test('侧栏所有页面可打开且无运行错误', async ({ page }, testInfo) => {
  const errors = collectErrors(page);
  await page.goto('/');
  await expect(page.locator('aside')).toBeVisible();

  for (const view of NAV_VIEWS) {
    const entry = page.locator('aside').getByRole('button', { name: new RegExp(`^${view.label}`) }).first();
    await expect(entry, `侧栏入口 ${view.label}`).toBeVisible();
    await entry.click();
    await page.waitForTimeout(350);
    await page.screenshot({ path: testInfo.outputPath(`${view.slug}.png`) });
  }

  expect(errors, errors.join('\n')).toEqual([]);
});

test('设置页每个分区可打开', async ({ page }, testInfo) => {
  const errors = collectErrors(page);
  await page.goto('/');
  await page
    .locator('aside')
    .getByRole('button', { name: /^设置/ })
    .first()
    .click();

  for (const section of SETTINGS_SECTIONS) {
    await page.getByRole('button', { name: new RegExp(`^${section}`) }).first().click();
    await page.waitForTimeout(250);
    await expect(page.getByText(section, { exact: false }).first()).toBeVisible();
    await page.screenshot({ path: testInfo.outputPath(`settings-${section}.png`) });
  }

  expect(errors, errors.join('\n')).toEqual([]);
});

test('水源帖子图片可打开灯箱并关闭', async ({ page }, testInfo) => {
  const errors = collectErrors(page);
  await page.goto('/');
  await page
    .locator('aside')
    .getByRole('button', { name: /^水源社区/ })
    .first()
    .click();
  await page.getByText('机器学习课程项目组队').first().click();
  await page.waitForTimeout(500);

  const image = page.locator('.shuiyuan-post img').first();
  await expect(image).toBeVisible();
  await image.click();

  const lightbox = page.getByRole('dialog', { name: '图片预览' });
  await expect(lightbox).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('shuiyuan-lightbox.png') });

  await page.keyboard.press('Escape');
  await expect(lightbox).toHaveCount(0);
  expect(errors, errors.join('\n')).toEqual([]);
});

test('侧栏折叠与恢复', async ({ page }) => {
  await page.goto('/');
  const sidebar = page.locator('aside');
  const before = await sidebar.boundingBox();
  await page.getByRole('button', { name: '切换侧栏' }).click();
  await page.waitForTimeout(300);
  const collapsed = await sidebar.boundingBox();
  expect(collapsed!.width).toBeLessThan(before!.width);
  await page.getByRole('button', { name: '切换侧栏' }).click();
  await page.waitForTimeout(300);
  const restored = await sidebar.boundingBox();
  expect(restored!.width).toBeGreaterThan(collapsed!.width);
});
