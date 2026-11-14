import { chromium, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const base = 'http://127.0.0.1:4174';
const server = spawn(
  process.execPath,
  [
    'node_modules/vite/bin/vite.js',
    'preview',
    '--host',
    '127.0.0.1',
    '--port',
    '4174',
    '--strictPort',
  ],
  { stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true },
);
let browser;
try {
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Preview timeout')), 15000);
    server.stdout.on('data', (chunk) => {
      if (String(chunk).includes('4174')) {
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
  const errors = [];
  for (const [portal, type, permissions] of [
    ['nsx', 'NSX', ['order_si:view', 'order_si:approve', 'product:view', 'price_list:manage']],
    ['npp', 'NPP', ['order_si:view', 'inventory:view', 'debt:view']],
    ['outlet', 'OUTLET', ['order_so:view', 'order_so:receive_confirm']],
    [
      'sales',
      'NSX',
      [
        'visit:view',
        'visit:checkin',
        'order_so:view',
        'order_so:create',
        'product:view',
        'price_sellout:view',
      ],
    ],
  ]) {
    const context = await browser.newContext({
      viewport: portal === 'sales' ? { width: 390, height: 844 } : { width: 1440, height: 1000 },
      serviceWorkers: 'block',
    });
    const page = await context.newPage();
    page.on('pageerror', (error) => errors.push(error.message));
    const requests = [];
    let failList = false;
    const cors = {
      'access-control-allow-origin': base,
      'access-control-allow-methods': 'GET, POST, OPTIONS',
      'access-control-allow-headers': 'Authorization, Content-Type, Idempotency-Key',
    };
    await page.route('**/api/v1/**', async (route) => {
      const request = route.request();
      const url = new URL(request.url());
      requests.push({
        path: url.pathname,
        query: url.search,
        headers: request.headers(),
        body: request.postData() ? request.postDataJSON() : null,
      });
      const reply = (body, status = 200) =>
        route.fulfill({
          status,
          headers: { ...cors, 'content-type': 'application/json' },
          body: JSON.stringify(body),
        });
      if (request.method() === 'OPTIONS') return route.fulfill({ status: 204, headers: cors });
      if (url.pathname.endsWith('/auth/login'))
        return reply({
          id: 'user',
          fullName: 'Tài khoản kiểm thử',
          email: 'qa@example.com',
          accessToken: 'access',
          refreshToken: 'refresh',
          deviceId: 'device',
          expiresIn: 900,
          tokenType: 'Bearer',
        });
      if (url.pathname.endsWith('/auth/me'))
        return reply({
          user: { id: 'user', fullName: 'Tài khoản kiểm thử', email: 'qa@example.com' },
          tenant: { id: 'tenant', type, code: 'QA', name: 'Đơn vị kiểm thử' },
          roles: [{ code: 'QA', name: 'Vai trò kiểm thử', assignmentLevel: 1 }],
          permissions: permissions.map((code) => ({ code, scope: 'A' })),
        });
      if (url.pathname.endsWith('/auth/logout'))
        return route.fulfill({ status: 204, headers: cors });
      if (url.pathname.endsWith('/approve'))
        return reply(
          { code: 'VERSION_CONFLICT', message: 'Dữ liệu đã được người khác cập nhật.' },
          409,
        );
      if (/\/orders\/sell-(in|out)\/row-\d$/.test(url.pathname))
        return reply({
          id: 'row-1',
          orderCode: 'Đơn kiểm thử',
          status: 'PENDING_APPROVAL',
          version: 7,
          totalVnd: 120000,
          lines: [
            {
              id: 'line',
              productId: 'product',
              sku: 'QA-SKU',
              productName: 'Sản phẩm kiểm thử',
              unitCode: 'CASE',
              orderedQty: 2,
              confirmedQty: 0,
              shippedQty: 0,
              receivedQty: 0,
              gift: false,
              lineAmountVnd: 120000,
            },
          ],
          history: [],
        });
      if (failList)
        return reply({ code: 'FORBIDDEN', message: 'Bạn không có quyền xem dữ liệu này.' }, 403);
      const listPath = url.pathname.includes('/orders/') || url.pathname.endsWith('/visits');
      const current = Number(url.searchParams.get('page') || 0);
      return reply({
        items: listPath
          ? [
              {
                id: `row-${current}`,
                orderCode: `Đơn ${current + 1}`,
                nppName: 'Đối tác kiểm thử',
                outletName: 'Điểm bán kiểm thử',
                salesName: 'Nhân viên kiểm thử',
                status: 'DRAFT',
                totalVnd: 120000,
                version: 0,
              },
            ]
          : [],
        page: current,
        size: 25,
        totalElements: listPath ? 26 : 0,
        totalPages: listPath ? 2 : 0,
      });
    });
    await page.goto(`${base}/#/${portal}`);
    await expect(page.getByRole('heading', { name: 'Chào mừng trở lại' })).toBeVisible();
    await page.getByLabel('Email', { exact: true }).fill('qa@example.com');
    await page.getByLabel('Mật khẩu', { exact: true }).fill('test-password-123');
    await page.getByRole('button', { name: 'Đăng nhập', exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`#/${portal}$`));
    await expect(page.locator('.live-identity')).toContainText('Tài khoản kiểm thử');
    const navigation = page.getByRole('navigation', { name: 'Chức năng' });
    await expect(navigation.getByRole('button', { name: 'Bảng giá', exact: true })).toHaveCount(
      portal === 'nsx' ? 1 : 0,
    );
    await expect(page.getByRole('button', { name: 'Sau', exact: true })).toBeEnabled();
    await page.getByRole('button', { name: 'Sau', exact: true }).click();
    await expect(page.locator('.live-pagination').first()).toContainText('Trang 2 / 2');
    assert(requests.some((request) => request.query.includes('page=1')));
    assert(
      requests
        .filter((request) => !request.path.includes('/auth/'))
        .some((request) => request.headers.authorization === 'Bearer access'),
    );
    if (portal === 'nsx') {
      await page.getByRole('button', { name: 'Chi tiết', exact: true }).click();
      const dialog = page.getByRole('dialog');
      await expect(
        dialog.getByRole('heading', { name: 'Đơn kiểm thử', exact: true }),
      ).toBeVisible();
      await dialog.getByRole('button', { name: 'Duyệt trong hạn mức', exact: true }).click();
      await dialog.getByLabel('Lý do', { exact: true }).fill('Kiểm thử xung đột phiên bản');
      await dialog.getByRole('button', { name: 'Lưu', exact: true }).click();
      await expect(dialog.getByRole('alert')).toContainText('Dữ liệu đã được người khác cập nhật.');
      assert.deepEqual(requests.find((request) => request.path.endsWith('/approve')).body, {
        reason: 'Kiểm thử xung đột phiên bản',
        version: 7,
      });
      await dialog.getByRole('button', { name: 'Đóng', exact: true }).click();
    }
    if (portal === 'sales') {
      await navigation.getByRole('button', { name: 'Lượt ghé', exact: true }).click();
      await expect(page.getByRole('button', { name: 'Check-in GPS', exact: true })).toBeVisible();
      await navigation.getByRole('button', { name: 'Đơn chờ gửi', exact: true }).click();
      await expect(page.getByRole('heading', { name: 'Đơn chờ gửi', exact: true })).toBeVisible();
      await navigation.getByRole('button', { name: 'Đơn sell-out', exact: true }).click();
    }
    failList = true;
    await page.getByRole('button', { name: 'Làm mới', exact: true }).click();
    await expect(page.getByRole('alert')).toContainText('Bạn không có quyền');
    failList = false;
    await page.getByRole('button', { name: 'Thử lại', exact: true }).click();
    await expect(page.locator('.live-table-wrap')).toBeVisible();
    assert(
      await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
      `${portal}: horizontal overflow`,
    );
    fs.mkdirSync('backend/target/p4-ui', { recursive: true });
    await page.screenshot({ path: `backend/target/p4-ui/${portal}.png`, fullPage: true });
    const storage = await page.evaluate(() => [
      ...Object.keys(localStorage),
      ...Object.keys(sessionStorage),
    ]);
    assert(!storage.some((key) => /token|session/i.test(key)));
    await page.getByRole('button', { name: 'Đăng xuất', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Chào mừng trở lại' })).toBeVisible();
    await context.close();
    console.log(
      `P4 ${portal}: login, permissions, server pagination, 403 retry, layout, logout passed`,
    );
  }
  assert.deepEqual(errors, []);
} finally {
  if (browser) await browser.close();
  server.kill();
}
