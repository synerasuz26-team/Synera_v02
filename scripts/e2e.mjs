import { chromium, expect } from '@playwright/test';

const base = process.env.TEST_URL || 'http://127.0.0.1:4173';
const browser = await chromium.launch({
  channel: process.env.BROWSER_CHANNEL || 'msedge',
  headless: true,
});
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
const errors = [];
const apiRequests = [];
const apiPayloads = [];
page.on('pageerror', (error) => errors.push(error.message));
page.on('request', (request) => {
  if (/\/api(?:\/|$)/.test(new URL(request.url()).pathname)) apiRequests.push(request.url());
});

try {
  const corsHeaders = {
    'access-control-allow-origin': new URL(base).origin,
    'access-control-allow-credentials': 'true',
    'access-control-allow-methods': 'GET, POST, OPTIONS',
    'access-control-allow-headers': 'Content-Type, X-XSRF-TOKEN',
  };
  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (request.method() === 'OPTIONS') {
      await route.fulfill({ status: 204, headers: corsHeaders });
      return;
    }

    if (request.method() === 'GET' && path.endsWith('/csrf')) {
      await route.fulfill({
        status: 200,
        headers: { ...corsHeaders, 'content-type': 'application/json' },
        body: JSON.stringify({ token: 'e2e-csrf-token' }),
      });
      return;
    }

    apiPayloads.push({
      path,
      headers: request.headers(),
      body: request.postDataJSON(),
    });
    await route.fulfill({
      status: path.endsWith('/register') ? 201 : 200,
      headers: { ...corsHeaders, 'content-type': 'application/json' },
      body: JSON.stringify({
        id: '550e8400-e29b-41d4-a716-446655440000',
        fullName: 'Nguyễn An',
        email: 'an@example.com',
      }),
    });
  });

  await page.goto(base + '/#/');
  await expect(page.getByRole('heading', { level: 1 })).toContainText('Một nền tảng');
  await expect(page.getByRole('link', { name: 'Đăng nhập' }).first()).toHaveAttribute(
    'href',
    '#/login',
  );
  await page.getByRole('link', { name: 'Tạo tài khoản', exact: true }).first().click();
  await expect(page.getByRole('heading', { name: 'Tạo tài khoản' })).toBeVisible();
  await page.getByLabel('Họ và tên').fill('Nguyễn An');
  await page.getByLabel('Email').fill('an@example.com');
  await page.getByLabel('Mật khẩu', { exact: true }).fill('password-with-12');
  await page.getByLabel('Xác nhận mật khẩu').fill('password-does-not-match');
  await page.getByRole('button', { name: 'Tạo tài khoản' }).click();
  await expect(page.getByRole('alert')).toHaveText('Mật khẩu xác nhận không khớp.');
  await page.getByLabel('Xác nhận mật khẩu').fill('password-with-12');
  await page.getByRole('button', { name: 'Tạo tài khoản' }).click();
  await expect(page.getByRole('status')).toContainText('Tạo tài khoản thành công');
  await page.getByRole('link', { name: 'Đăng nhập', exact: true }).last().click();
  await expect(page.getByRole('heading', { name: 'Chào mừng trở lại' })).toBeVisible();
  await page.getByLabel('Email').fill('an@example.com');
  await page.getByLabel('Mật khẩu', { exact: true }).fill('password-with-12');
  await page.getByRole('button', { name: 'Đăng nhập' }).click();
  await expect(page.getByRole('status')).toContainText('Đăng nhập thành công');
  await page.goto(base + '/#/dashboard');
  await expect(page.getByRole('heading', { level: 1 })).toContainText('Một nền tảng');
  expect(apiRequests.filter((url) => new URL(url).pathname.endsWith('/register'))).toHaveLength(1);
  expect(apiRequests.filter((url) => new URL(url).pathname.endsWith('/login'))).toHaveLength(1);
  expect(apiPayloads.map((request) => request.path)).toEqual([
    '/api/auth/register',
    '/api/auth/login',
  ]);
  expect(apiPayloads[0].headers['x-xsrf-token']).toBe('e2e-csrf-token');
  expect(apiPayloads[0].body).toEqual({
    fullName: 'Nguyễn An',
    email: 'an@example.com',
    password: 'password-with-12',
  });
  expect(errors).toEqual([]);
  console.log('Landing, API-backed login/register forms and route fallback passed.');
} finally {
  await browser.close();
}
