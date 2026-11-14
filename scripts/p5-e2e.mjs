import { chromium, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import fs from 'node:fs/promises';
import assert from 'node:assert/strict';

const api = process.env.P5_TEST_API_URL || 'http://127.0.0.1:18085';
if (!/^http:\/\/127\.0\.0\.1:[0-9]+$/.test(api))
  throw new Error('P5 test only supports a dedicated local API.');
const password = process.env.P5_E2E_PASSWORD;
if (!password) throw new Error('Set P5_E2E_PASSWORD for the isolated test fixture.');
const base = 'http://127.0.0.1:4175';
const server = spawn(
  process.execPath,
  [
    'node_modules/vite/bin/vite.js',
    'preview',
    '--host',
    '127.0.0.1',
    '--port',
    '4175',
    '--strictPort',
  ],
  { stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true },
);
let browser;
try {
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Preview timeout')), 15000);
    server.stdout.on('data', (chunk) => {
      if (String(chunk).includes('4175')) {
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
  const context = await browser.newContext({
    viewport: { width: 1440, height: 1000 },
    serviceWorkers: 'block',
  });
  const page = await context.newPage();
  const errors = [];
  let authorization = '';
  page.on('pageerror', (error) => errors.push(error.message));
  page.on('request', async (request) => {
    const headers = await request.allHeaders();
    if (request.url().startsWith(api) && headers.authorization)
      authorization = headers.authorization;
  });
  const login = async (email) => {
    await page.goto(`${base}/#/login`);
    await page.getByLabel('Email', { exact: true }).fill(email);
    await page.getByLabel('Mật khẩu', { exact: true }).fill(password);
    await page.getByRole('button', { name: 'Đăng nhập', exact: true }).click();
    await page
      .getByRole('navigation', { name: 'Chức năng' })
      .getByRole('button', { name: 'Nhập dữ liệu nền', exact: true })
      .click();
    await expect(
      page.getByRole('heading', { name: 'Nhập dữ liệu nền', exact: true }),
    ).toBeVisible();
  };
  const upload = async (source) => {
    await page.getByLabel('Tệp Excel hoặc CSV UTF-8').setInputFiles({
      name: 'p5-test.csv',
      mimeType: 'text/csv',
      buffer: Buffer.from(source, 'utf8'),
    });
    await page.getByLabel('Lý do nhập dữ liệu').fill('Kiểm thử P5 trên DB local tạm');
    await page.getByRole('button', { name: 'Tải lên và xem trước', exact: true }).click();
    await expect(
      page.getByRole('heading', { name: '2. Xem trước dữ liệu', exact: true }),
    ).toBeVisible();
  };
  const confirm = async () => {
    await page.getByRole('button', { name: '3. Xác nhận ghi toàn bộ lô', exact: true }).click();
    await expect(
      page.getByRole('heading', { name: '3. Báo cáo sau import', exact: true }),
    ).toBeVisible();
    return page
      .locator('section')
      .filter({ has: page.getByRole('heading', { name: '3. Báo cáo sau import', exact: true }) });
  };
  await login('p5-admin@example.invalid');
  await page.getByLabel('Loại dữ liệu').selectOption('PRODUCTS');
  const downloadPromise = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Tải file mẫu .xlsx', exact: true }).click();
  const download = await downloadPromise;
  assert.equal(download.suggestedFilename(), 'synera-products.xlsx');
  await fs.mkdir('backend/target/p5-ui', { recursive: true });
  await download.saveAs('backend/target/p5-ui/downloaded-products.xlsx');
  assert((await fs.stat('backend/target/p5-ui/downloaded-products.xlsx')).size > 1000);
  const sku = `P5-UI-${Date.now()}`;
  const header = 'sku_code,name,base_unit,case_unit,units_per_case\n';
  await upload(`${header}${sku},A,hộp,thùng,48\n${sku},,hộp,thùng,48\n`);
  await expect(page.getByRole('alert')).toContainText('Có dòng lỗi');
  await expect(
    page.getByRole('button', { name: '3. Xác nhận ghi toàn bộ lô', exact: true }),
  ).toHaveCount(0);
  const source = `${header}${sku},Sản phẩm kiểm thử P5,hộp,thùng,48\n`;
  await upload(source);
  const before = await fetch(`${api}/api/v1/products?q=${sku}`, {
    headers: { Authorization: authorization },
  }).then((response) => response.json());
  assert.equal(before.totalElements, 0);
  let report = await confirm();
  await expect(report.locator('tbody').first().locator('td').nth(3)).toHaveText('1');
  await upload(source);
  report = await confirm();
  await expect(report.locator('tbody').first().locator('td').nth(5)).toHaveText('1');
  await upload(source.replace('Sản phẩm kiểm thử P5', 'Tên đã cập nhật P5'));
  report = await confirm();
  await expect(report.locator('tbody').first().locator('td').nth(4)).toHaveText('1');
  const after = await fetch(`${api}/api/v1/products?q=${sku}`, {
    headers: { Authorization: authorization },
  }).then((response) => response.json());
  assert.equal(after.totalElements, 1);
  assert.equal(after.items[0].name, 'Tên đã cập nhật P5');
  await page.screenshot({ path: 'backend/target/p5-ui/nsx.png', fullPage: true });
  console.log(
    'P5 NSX real API: template download, per-row errors, preview without writes, commit, replay and update passed.',
  );
  await page.getByRole('button', { name: 'Đăng xuất', exact: true }).click();
  await login('p5-staff@example.invalid');
  await expect(page.getByLabel('Loại dữ liệu').locator('option')).toHaveCount(2);
  await expect(page.getByLabel('Loại dữ liệu').locator('option[value="PRODUCTS"]')).toHaveCount(0);
  const forbiddenBody = new FormData();
  forbiddenBody.set('file', new Blob([source], { type: 'text/csv' }), 'forbidden.csv');
  forbiddenBody.set('reason', 'P5 permission test');
  const forbidden = await fetch(`${api}/api/v1/foundation-imports/PRODUCTS/preview`, {
    method: 'POST',
    headers: { Authorization: authorization },
    body: forbiddenBody,
  });
  assert.equal(forbidden.status, 403);
  await page.getByLabel('Loại dữ liệu').selectOption('INVENTORY');
  await upload(`npp_code,sku_code,stock_case,updated_at\nFOREIGN-NPP,${sku},2,2026-11-13\n`);
  await expect(page.locator('.form-error')).toContainText('NPP không tồn tại hoặc ngoài phạm vi');
  await page.setViewportSize({ width: 390, height: 844 });
  assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
  await page.screenshot({ path: 'backend/target/p5-ui/npp-mobile.png', fullPage: true });
  assert.deepEqual(errors, []);
  console.log(
    'P5 NPP real API: allowed types, direct API 403, tenant boundary validation and mobile layout passed.',
  );
  await context.close();
} finally {
  if (browser) await browser.close();
  server.kill();
}
