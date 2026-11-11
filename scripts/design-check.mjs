import { chromium, expect } from '@playwright/test';

const base = process.env.TEST_URL || 'http://127.0.0.1:4173';
const browser = await chromium.launch({
  channel: process.env.BROWSER_CHANNEL || 'msedge',
  headless: true,
});
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });

try {
  await page.goto(base + '/#/landing');
  await expect(page.locator('.landing-header')).toBeVisible();
  await expect(page.locator('.landing-hero video')).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole('button', { name: 'Mở menu website' }).click();
  await expect(page.getByRole('navigation', { name: 'Điều hướng website mobile' })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);

  for (const route of ['/login', '/register']) {
    await page.goto(base + '/#' + route);
    await expect(page.locator('.auth-page')).toBeVisible();
    await expect(page.getByRole('note')).toContainText('lưu trên máy chủ');
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
  }

  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.goto(base + '/#/landing');
  await expect(page.locator('.landing-hero video')).toBeHidden();
  console.log('Landing and auth responsive design checks passed.');
} finally {
  await browser.close();
}
