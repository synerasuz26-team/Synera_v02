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

  await page.goto(base + '/#/sales');
  await expect(page.locator('.ms-app')).toBeVisible();
  await expect(page.locator('.ms-screen')).toHaveAttribute('aria-busy', 'false');
  await expect(page.locator('.ms-active-visit')).toHaveCount(0);
  await page.evaluate(() => {
    const key = 'synera.mobile-sales.v1';
    const data = JSON.parse(localStorage.getItem(key));
    Object.assign(data, {
      activeOutlet: 'Tạp hóa Lan',
      checkInAt: Date.now(),
      gpsStatus: 'inside',
      completedStops: [],
      stock: { NS500: 5, NS150: 2, TX330: 1 },
      visitHistory: [],
    });
    localStorage.setItem(key, JSON.stringify(data));
  });
  await page.reload();
  await expect(page.locator('.ms-screen')).toHaveAttribute('aria-busy', 'false');
  await expect(page.locator('.ms-active-visit')).toHaveCount(0);
  const salesNav = page.getByRole('navigation', { name: 'Điều hướng chính' });
  await salesNav.getByRole('button', { name: 'Thêm' }).click();
  await page.getByRole('button', { name: /Sup \/ ASM/ }).click();
  await expect(salesNav.getByRole('button', { name: 'Đội' })).toBeVisible();
  await salesNav.getByRole('button', { name: 'Đội' }).click();
  await expect(page.getByRole('heading', { name: 'Ngoại lệ GPS' })).toBeVisible();
  await salesNav.getByRole('button', { name: 'Thêm' }).click();
  await page.getByRole('button', { name: /^Sales/ }).click();
  await expect(salesNav.getByRole('button', { name: 'Đội' })).toHaveCount(0);
  await salesNav.getByRole('button', { name: 'Khách hàng' }).click();
  await page.getByRole('button', { name: /Tạp hóa Lan/ }).click();
  await page.getByRole('button', { name: /CHUẨN BỊ GHÉ & CHECK-IN/ }).click();
  await page.context().grantPermissions(['geolocation']);
  await page.context().setGeolocation({ latitude: 10.72, longitude: 106.6 });
  await page.getByRole('button', { name: /CHECK-IN BẰNG GPS/ }).click();
  await expect(page.getByText('Đã ghi lượt · chờ Sup xác nhận GPS')).toBeVisible();
  await page.getByLabel('Nội dung hẹn').fill('Kiểm tra hẹn có thể hoàn tác');
  await page.getByRole('button', { name: 'LƯU LỜI HẸN' }).click();
  await expect(page.getByText(/Đã lưu lời hẹn/)).toBeVisible();
  await page.getByRole('button', { name: 'Hoàn tác' }).click();
  await expect(page.getByText('Đã hoàn tác thao tác vừa rồi.')).toBeVisible();
  expect(
    await page.evaluate(
      () => JSON.parse(localStorage.getItem('synera.mobile-sales.v1')).appointments.length,
    ),
  ).toBe(2);
  await salesNav.getByRole('button', { name: 'Hôm nay' }).click();
  await page.locator('.ms-active-visit').getByRole('button', { name: 'Tạo đơn' }).click();
  await expect(page.locator('.ms-product-list')).toHaveCount(0);
  await salesNav.getByRole('button', { name: 'Thêm' }).click();
  await page.getByRole('button', { name: /Sup \/ ASM/ }).click();
  await salesNav.getByRole('button', { name: 'Đội' }).click();
  await page.locator('.ms-exception-card').getByRole('button', { name: 'Xác nhận' }).click();
  await expect(page.getByText('Đã xác nhận ngoại lệ GPS.')).toBeVisible();
  await salesNav.getByRole('button', { name: 'Thêm' }).click();
  await page.getByRole('button', { name: /^Sales/ }).click();
  await salesNav.getByRole('button', { name: 'Hôm nay' }).click();
  await page.locator('.ms-active-visit').getByRole('button', { name: 'Tạo đơn' }).click();
  await expect(page.getByText('Đơn tại Tạp hóa Lan')).toBeVisible();
  await expect(page.locator('.ms-app')).not.toContainText('86.000đ');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);

  const undersizedText = await page.locator('.ms-app *').evaluateAll((nodes) =>
    nodes
      .filter((node) =>
        Array.from(node.childNodes).some(
          (child) => child.nodeType === Node.TEXT_NODE && child.textContent?.trim(),
        ),
      )
      .filter((node) => Number.parseFloat(getComputedStyle(node).fontSize) < 12)
      .map((node) => `${node.tagName}.${node.className}`),
  );
  expect(undersizedText).toEqual([]);

  await page.evaluate(() => {
    const key = 'synera.mobile-sales.v1';
    const data = JSON.parse(localStorage.getItem(key));
    data.orders = [];
    localStorage.setItem(key, JSON.stringify(data));
  });
  await page.reload();
  await page.waitForSelector('.ms-skeleton', { state: 'visible', timeout: 1000 });
  await expect(page.locator('.ms-screen')).toHaveAttribute('aria-busy', 'true');
  await salesNav.getByRole('button', { name: 'Đơn hàng' }).click();
  await expect(page.getByText('Chưa có đơn nào')).toBeVisible();
  await expect(page.getByText('Đơn tạo từ lượt ghé sẽ xuất hiện ở đây.')).toBeVisible();

  await page.evaluate(() => localStorage.setItem('synera.mobile-sales.v1', '{invalid'));
  await page.reload();
  await expect(page.locator('.ms-error-banner')).toContainText(
    'Không đọc được dữ liệu trên thiết bị',
  );
  await page.evaluate(() => localStorage.removeItem('synera.mobile-sales.v1'));
  await page.getByRole('button', { name: 'Thử lại' }).click();
  await expect(page.locator('.ms-error-banner')).toHaveCount(0);
  await expect(page.getByText('Đã khôi phục lưu trữ trên thiết bị.')).toBeVisible();

  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.goto(base + '/#/landing');
  await expect(page.locator('.landing-hero video')).toBeHidden();
  console.log('Landing, auth, and Mobile Sales responsive design checks passed.');
} finally {
  await browser.close();
}
