import { chromium, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import fs from 'node:fs/promises';
import assert from 'node:assert/strict';

const api = process.env.P6_TEST_API_URL || 'http://127.0.0.1:18086';
const storage = process.env.P6_S3_ENDPOINT;
const password = process.env.P6_E2E_PASSWORD;
if (
  !/^http:\/\/127\.0\.0\.1:[0-9]+$/.test(api) ||
  !password ||
  !storage?.startsWith('http://127.0.0.1:')
)
  throw new Error('Use a dedicated local P6 API, storage emulator and test fixture password.');
const base = 'http://127.0.0.1:4176';
const server = spawn(
  process.execPath,
  [
    'node_modules/vite/bin/vite.js',
    'preview',
    '--host',
    '127.0.0.1',
    '--port',
    '4176',
    '--strictPort',
  ],
  { stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true },
);
let browser;
try {
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Preview timeout')), 15000);
    server.stdout.on('data', (chunk) => {
      if (String(chunk).includes('4176')) {
        clearTimeout(timer);
        resolve();
      }
    });
    server.once('exit', (code) => {
      clearTimeout(timer);
      reject(new Error(`Preview exited ${code}`));
    });
  });
  browser = await chromium.launch({
    channel: process.env.BROWSER_CHANNEL || 'msedge',
    headless: true,
  });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  const errors = [];
  let authorization = '';
  let storagePut = 0;
  page.on('pageerror', (error) => errors.push(error.message));
  page.on('request', async (request) => {
    const headers = await request.allHeaders();
    if (request.url().startsWith(api) && headers.authorization)
      authorization = headers.authorization;
    if (request.url().startsWith(storage) && request.method() === 'PUT') {
      assert(!headers.authorization, 'API bearer leaked to storage');
      storagePut++;
    }
  });
  await page.goto(`${base}/#/login`);
  await page.getByLabel('Email', { exact: true }).fill('p6-admin@example.invalid');
  await page.getByLabel('Mật khẩu', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Đăng nhập', exact: true }).click();
  const nav = page.getByRole('navigation', { name: 'Chức năng' });
  await expect(nav.getByRole('button', { name: /^Thông báo/ })).toBeVisible();
  await expect.poll(() => authorization.length).toBeGreaterThan(0);
  const taskTitle = `Lời hẹn P6 ${Date.now()}`;
  const task = await fetch(`${api}/api/v1/tasks`, {
    method: 'POST',
    headers: { Authorization: authorization, 'Content-Type': 'application/json' },
    body: JSON.stringify({ title: taskTitle, dueAt: new Date().toISOString() }),
  });
  assert.equal(task.status, 200);
  await nav.getByRole('button', { name: /^Thông báo/ }).click();
  await expect(page.getByText(taskTitle, { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Đánh dấu tất cả đã đọc', exact: true }).click();
  await page.getByRole('button', { name: 'Chỉ chưa đọc', exact: true }).click();
  await expect(page.getByText('Không có thông báo.', { exact: true })).toBeVisible();
  await nav.getByRole('button', { name: 'Cấu hình vận hành', exact: true }).click();
  const threshold = page.getByLabel('Cảnh báo chênh lệch lớn hơn (%)', { exact: true });
  await expect(threshold).toBeVisible();
  await threshold.fill('9');
  await page
    .getByLabel('Lý do thay đổi', { exact: true })
    .fill('Kiểm thử giao diện P6 trên DB local');
  const save = page.waitForResponse(
    (response) =>
      response.url().endsWith('/operations/settings') && response.request().method() === 'PUT',
  );
  await page.getByRole('button', { name: 'Lưu cấu hình', exact: true }).click();
  assert.equal((await save).status(), 200);
  await expect(threshold).toHaveValue('9');
  await fs.mkdir('backend/target/p6-ui', { recursive: true });
  await page.screenshot({ path: 'backend/target/p6-ui/settings.png', fullPage: true });
  await nav.getByRole('button', { name: 'Lượt ghé', exact: true }).click();
  await page.getByRole('button', { name: 'Chi tiết', exact: true }).first().click();
  const dialog = page.getByRole('dialog', { name: 'Lượt ghé', exact: true });
  const png = await page.evaluate(() => {
    const canvas = document.createElement('canvas');
    canvas.width = 1200;
    canvas.height = 600;
    const ctx = canvas.getContext('2d');
    ctx.fillStyle = '#245bdb';
    ctx.fillRect(0, 0, 1200, 600);
    return canvas.toDataURL('image/png').split(',')[1];
  });
  const completion = page.waitForResponse(
    (response) =>
      response.url().includes('/attachments/') &&
      response.url().endsWith('/complete') &&
      response.request().method() === 'POST',
  );
  await dialog.getByLabel('Chọn ảnh JPEG/PNG (tự nén trước khi tải)').setInputFiles({
    name: 'visit.png',
    mimeType: 'image/png',
    buffer: Buffer.from(png, 'base64'),
  });
  await expect(dialog.getByRole('status')).toContainText('Đã tải ảnh', { timeout: 20000 });
  assert.equal(storagePut, 1);
  const attachmentId = (await (await completion).json()).id;
  await expect(
    dialog.getByRole('img', { name: 'Ảnh visit.png', exact: true }).last(),
  ).toBeVisible();
  const thumbnail = await fetch(
    `${api}/api/v1/attachments/${attachmentId}/download?thumbnail=true`,
    { headers: { Authorization: authorization } },
  );
  assert.equal(thumbnail.status, 200);
  const signed = await thumbnail.json();
  const imageResponse = await fetch(signed.url);
  assert.equal(imageResponse.status, 200);
  assert.match(imageResponse.headers.get('content-type'), /image\/jpeg/);
  await page.screenshot({ path: 'backend/target/p6-ui/visit-image.png', fullPage: true });
  await dialog.getByRole('button', { name: 'Đóng', exact: true }).click();
  await page.setViewportSize({ width: 390, height: 844 });
  await nav.getByRole('button', { name: /^Thông báo/ }).click();
  await expect(page.getByRole('heading', { name: 'Thông báo', exact: true })).toBeVisible();
  assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
  await page.screenshot({ path: 'backend/target/p6-ui/mobile-inbox.png', fullPage: true });
  const noAuth = await fetch(`${api}/api/v1/notifications`);
  assert.equal(noAuth.status, 401);
  assert.deepEqual(errors, []);
  console.log(
    'P6 real API: inbox/read, configuration, task notification, mobile compression, signed S3 upload, thumbnail, mobile layout and 401 passed.',
  );
} finally {
  await browser?.close();
  server.kill();
}
