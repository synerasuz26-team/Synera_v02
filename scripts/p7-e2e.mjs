import { chromium, request, expect } from '@playwright/test';
import { spawn, execFileSync } from 'node:child_process';
import fs from 'node:fs';
import assert from 'node:assert/strict';
import { performance } from 'node:perf_hooks';

const api = process.env.P7_TEST_API_URL,
  container = process.env.P7_DB_CONTAINER,
  runId = process.env.P7_RUN_ID,
  password = process.env.P7_TEST_PASSWORD;
if (
  process.env.P7_TEST_RUN !== '1' ||
  !/^http:\/\/127\.0\.0\.1:\d+$/.test(api || '') ||
  !/^synera-p7-[a-f0-9]{12}$/.test(container || '') ||
  !password
)
  throw new Error('Run backend/scripts/test-p7.ps1; P7 cannot target dev/production.');
const inspect = JSON.parse(
  execFileSync('docker', ['inspect', container], { encoding: 'utf8', windowsHide: true }),
)[0];
assert.equal(inspect.Config.Labels['synera.p7.run'], runId);
const output = 'backend/target/p7';
fs.mkdirSync(output, { recursive: true });
const fixture = JSON.parse(fs.readFileSync(`${output}/fixture.json`, 'utf8')),
  ids = fixture.ids;
let dbAssertions = 0;
const oracle = JSON.parse(
  fs.readFileSync('backend/src/test/resources/acceptance/rbac-oracle.json', 'utf8'),
);
function db(sql) {
  return execFileSync(
    'docker',
    [
      'exec',
      '-i',
      container,
      'psql',
      '-X',
      '-qAt',
      '-v',
      'ON_ERROR_STOP=1',
      '-U',
      'postgres',
      '-d',
      'synera_p7',
    ],
    { input: sql, encoding: 'utf8', windowsHide: true, maxBuffer: 20 * 1024 * 1024 },
  ).trim();
}
const quote = (value) => `'${String(value).replaceAll("'", "''")}'`;
const uuid = (value) => {
  assert.match(value, /^[a-f0-9-]{36}$/);
  return `${quote(value)}::uuid`;
};
function check(sql, expected) {
  assert.equal(db(sql), String(expected), sql);
  dbAssertions++;
}
function rejectDb(sql) {
  let rejected = false;
  try {
    db(sql);
  } catch {
    rejected = true;
  }
  assert(rejected, 'DB accepted a forbidden financial mutation');
  dbAssertions++;
}
async function harness(action, key = 'run') {
  const env = {
    ...process.env,
    P7_HARNESS_ACTION: action,
    P7_BUSINESS_DAY: fixture.day,
    P7_JOB_KEY: key,
    MAVEN_OPTS: '-Xmx512m -XX:ActiveProcessorCount=4',
  };
  await new Promise((resolve, reject) => {
    const child = spawn(
      'powershell.exe',
      [
        '-NoProfile',
        '-NonInteractive',
        '-Command',
        "& $env:P7_MAVEN -f backend/pom.xml '-Dtest=P7HarnessTest' '-DargLine=-Xmx512m -XX:ActiveProcessorCount=4' test; exit $LASTEXITCODE",
      ],
      { env, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] },
    );
    const log = fs.createWriteStream(`${output}/${action}-${key}.log`);
    child.stdout.pipe(log, { end: false });
    child.stderr.pipe(log, { end: false });
    const timer = setTimeout(() => {
      spawn('taskkill.exe', ['/PID', String(child.pid), '/T', '/F'], {
        windowsHide: true,
        stdio: 'ignore',
      });
    }, 600000);
    child.once('error', (error) => {
      clearTimeout(timer);
      log.end();
      reject(error);
    });
    child.once('close', (code) => {
      clearTimeout(timer);
      log.end();
      if (code === 0) resolve();
      else reject(new Error(`P7 ${action} harness failed; see log`));
    });
  });
}
const results = [];
let browser, server, periodId, withinOrder;
const sessions = new Map();
const base = 'http://127.0.0.1:4177';
async function call(actor, method, path, body, expected = 200, extra = {}) {
  const response = await actor.http.fetch(`${api}/api/v1${path}`, {
    method,
    data: body,
    headers: { Authorization: actor.authorization, ...extra },
    timeout: 30000,
  });
  const text = await response.text();
  assert.equal(
    response.status(),
    expected,
    `${method} ${path}: ${response.status()} ${expected === 200 ? text.slice(0, 600) : ''}`,
  );
  return text ? JSON.parse(text) : null;
}
async function login(name, ui = false) {
  const key = `${name}:${ui}`;
  if (sessions.has(key)) return sessions.get(key);
  const email = fixture.actors[name] || name,
    http = await request.newContext();
  let page, context, token;
  if (ui) {
    context = await browser.newContext({
      viewport: name === 'sales' ? { width: 390, height: 844 } : { width: 1440, height: 1000 },
    });
    page = await context.newPage();
    await page.goto(`${base}/#/login`);
    await page.getByLabel('Email', { exact: true }).fill(email);
    await page.getByLabel('Mật khẩu', { exact: true }).fill(password);
    const response = page.waitForResponse(
      (r) => r.url().endsWith('/auth/login') && r.request().method() === 'POST',
    );
    await page.getByRole('button', { name: 'Đăng nhập', exact: true }).click();
    const auth = await response;
    assert.equal(auth.status(), 200);
    token = (await auth.json()).accessToken;
    await expect(page.getByRole('navigation', { name: 'Chức năng' })).toBeVisible({
      timeout: 20000,
    });
  } else {
    const response = await http.post(`${api}/api/v1/auth/login`, {
      data: { email, password, deviceId: crypto.randomUUID() },
    });
    assert.equal(response.status(), 200, `Login ${name}`);
    token = (await response.json()).accessToken;
  }
  const actor = { http, page, context, authorization: `Bearer ${token}` };
  actor.me = await call(actor, 'GET', '/auth/me');
  sessions.set(key, actor);
  return actor;
}
async function nav(actor, label) {
  await actor.page
    .getByRole('navigation', { name: 'Chức năng' })
    .getByRole('button', { name: label, exact: true })
    .click();
}
async function openOrder(actor, kind, order) {
  await nav(actor, kind === 'sell-in' ? 'Đơn sell-in' : 'Đơn sell-out');
  await actor.page.getByRole('button', { name: 'Làm mới', exact: true }).click();
  const row = actor.page.getByRole('row').filter({ hasText: order.orderCode });
  await row.getByRole('button', { name: 'Chi tiết', exact: true }).click();
  return actor.page.getByRole('dialog');
}
async function actionUi(actor, kind, order, label, suffix, quantity, receipt = false) {
  const dialog = await openOrder(actor, kind, order);
  await dialog.getByRole('button', { name: label, exact: true }).click();
  await dialog.getByLabel('Lý do', { exact: true }).fill('P7 acceptance');
  if (suffix === 'fulfillment')
    await dialog
      .getByLabel(/Ngày giao dự kiến phần còn thiếu/)
      .fill(new Date(Date.now() + 86400000).toISOString().slice(0, 16));
  if (quantity !== undefined) {
    await dialog.getByLabel(new RegExp(receipt ? 'Thực nhận' : 'Số lượng')).fill(String(quantity));
    if (receipt) await dialog.getByLabel(/Hỏng/).fill('0');
  }
  const response = actor.page.waitForResponse(
    (r) =>
      r.url().endsWith(`/orders/${kind}/${order.id}/${suffix}`) && r.request().method() === 'POST',
  );
  await dialog.getByRole('button', { name: 'Lưu', exact: true }).click();
  const completed = await response;
  fs.appendFileSync(
    `${output}/ui-actions.jsonl`,
    JSON.stringify({
      label,
      path: completed.url(),
      payload: completed.request().postDataJSON(),
      status: completed.status(),
      body: await completed.json(),
    }) + '\n',
  );
  assert.equal(completed.status(), 200, `${label}: ${await completed.text()}`);
  const body = await completed.json();
  await dialog.getByRole('button', { name: 'Đóng', exact: true }).click();
  return body;
}
async function createInUi(buyer, qty) {
  const products = await call(buyer, 'GET', '/products');
  fs.writeFileSync(`${output}/buyer-products.json`, JSON.stringify(products, null, 2));
  assert(
    products.items.some((product) => product.id === ids.product),
    'Fixture product is not visible to buyer through the real API',
  );
  await nav(buyer, 'Đơn sell-in');
  await buyer.page.getByRole('button', { name: 'Tạo mới', exact: true }).click();
  const dialog = buyer.page.getByRole('dialog');
  await dialog.getByLabel('Mã định danh nhà sản xuất (do quản trị viên cung cấp)').fill(ids.nsx);
  await dialog.getByRole('combobox', { name: /^Sản phẩm/ }).selectOption(ids.product);
  await dialog.getByRole('combobox', { name: /^Đơn vị/ }).selectOption('EA');
  await dialog.getByLabel('Số lượng', { exact: true }).fill(String(qty));
  await dialog.getByLabel('Lý do', { exact: true }).fill('P7 within credit');
  const response = buyer.page.waitForResponse(
    (r) => r.url().endsWith('/orders/sell-in') && r.request().method() === 'POST',
  );
  await dialog.getByRole('button', { name: 'Gửi đơn', exact: true }).click();
  const completed = await response;
  assert.equal(completed.status(), 200, await completed.text());
  return completed.json();
}
async function mobileOrder(qty, loseResponse = false) {
  const sales = await login('sales', true);
  await nav(sales, 'Đơn sell-out');
  await sales.page.getByRole('button', { name: 'Tạo mới', exact: true }).click();
  const dialog = sales.page.getByRole('dialog');
  const nppField = dialog.getByLabel(/^Nhà phân phối/);
  if (await nppField.evaluate((element) => element.tagName === 'SELECT'))
    await nppField.selectOption(ids.npp);
  else await nppField.fill(ids.npp);
  const outletField = dialog.getByLabel(/^Điểm bán/);
  if (await outletField.evaluate((element) => element.tagName === 'SELECT'))
    await outletField.selectOption(ids['shop-outlet']);
  else await outletField.fill(ids['shop-outlet']);
  await dialog.getByRole('combobox', { name: /^Sản phẩm/ }).selectOption(ids.product);
  await dialog.getByRole('combobox', { name: /^Đơn vị/ }).selectOption('EA');
  await dialog.getByLabel('Số lượng', { exact: true }).fill(String(qty));
  await dialog.getByLabel('Lý do', { exact: true }).fill('P7 mobile');
  const quoteResponse = sales.page.waitForResponse(
    (r) => r.url().endsWith('/prices/calculate') && r.request().method() === 'POST',
  );
  await dialog.getByRole('button', { name: 'Xem giá máy chủ', exact: true }).click();
  assert.equal((await quoteResponse).status(), 200);
  let lost, key;
  if (loseResponse)
    await sales.page.route(
      '**/api/v1/orders/sell-out',
      async (route) => {
        if (route.request().method() !== 'POST') return route.continue();
        const response = await route.fetch();
        assert.equal(response.status(), 200);
        lost = await response.json();
        key = route.request().headers()['idempotency-key'];
        await route.abort('failed');
      },
      { times: 1 },
    );
  const response = sales.page
    .waitForResponse(
      (r) => r.url().endsWith('/orders/sell-out') && r.request().method() === 'POST',
      { timeout: 30000 },
    )
    .catch(() => null);
  await dialog.getByRole('button', { name: 'Lưu đơn chờ gửi', exact: true }).click();
  await expect(dialog.getByRole('status')).toContainText('Đã lưu đơn');
  await dialog.getByRole('button', { name: 'Đóng', exact: true }).last().click();
  await nav(sales, 'Đơn chờ gửi');
  if (loseResponse) {
    await expect.poll(() => lost?.id, { timeout: 20000 }).toBeTruthy();
    const retry = sales.page.getByRole('button', { name: 'Thử gửi lại', exact: true }).last();
    await expect(retry).toBeVisible();
    await sales.context.setOffline(true);
    await sales.context.setOffline(false);
    await retry.click();
    await expect
      .poll(
        async () => {
          const drafts = await sales.page.evaluate(() => Object.values(localStorage).join(' '));
          return drafts.includes(lost.id);
        },
        { timeout: 20000 },
      )
      .toBeTruthy();
    check(
      `SELECT count(*) FROM order_so WHERE idempotency_key=${quote(key)} AND npp_tenant_id=${uuid(ids.npp)}`,
      1,
    );
    await sales.page.screenshot({ path: `${output}/mobile-retry.png`, fullPage: true });
    return lost;
  }
  const completed = await response;
  assert(completed, 'Mobile did not send the queued order');
  assert.equal(completed.status(), 200, await completed.text());
  return completed.json();
}
async function scenario(code, name, work) {
  const start = performance.now(),
    before = dbAssertions;
  try {
    await work();
    results.push({
      code,
      name,
      status: 'PASS',
      milliseconds: Math.round(performance.now() - start),
      databaseAssertions: dbAssertions - before,
    });
    console.log(`P7 ${code}: PASS`);
  } catch (error) {
    results.push({
      code,
      name,
      status: 'FAIL',
      error: String(error.message).slice(0, 1500),
      milliseconds: Math.round(performance.now() - start),
      databaseAssertions: dbAssertions - before,
    });
    console.error(`P7 ${code}: FAIL - ${String(error.message).slice(0, 250)}`);
    for (const [key, session] of sessions)
      if (session.page)
        await session.page
          .screenshot({
            path: `${output}/failure-${code}-${key.replaceAll(':', '-')}.png`,
            fullPage: true,
          })
          .catch(() => {});
    for (const session of sessions.values())
      if (session.page) {
        const dialog = session.page.getByRole('dialog');
        if (await dialog.count())
          await dialog
            .getByRole('button', { name: 'Đóng', exact: true })
            .first()
            .click()
            .catch(() => {});
      }
  } finally {
    // Preserve progress even while the larger performance fixture is being built.
    fs.writeFileSync(
      `${output}/progress.json`,
      JSON.stringify({ runId, scenarios: results }, null, 2),
    );
    // Release browser contexts between scenarios so the DB benchmark is not competing with idle tabs.
    for (const [key, session] of sessions)
      if (session.context) {
        await session.context.close();
        await session.http.dispose();
        sessions.delete(key);
      }
  }
}
try {
  server = spawn(
    process.execPath,
    ['node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', '4177', '--strictPort'],
    {
      env: { ...process.env, VITE_API_URL: api, VITE_USE_MOCK: 'false' },
      stdio: ['ignore', 'pipe', 'pipe'],
      windowsHide: true,
    },
  );
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Vite timeout')), 20000);
    server.stdout.on('data', (chunk) => {
      if (String(chunk).includes('4177')) {
        clearTimeout(timer);
        resolve();
      }
    });
    server.once('exit', () => reject(new Error('Vite exited')));
  });
  browser = await chromium.launch({
    channel: process.env.BROWSER_CHANNEL || 'msedge',
    headless: true,
  });
  await scenario(
    'a',
    'Sell-in within credit → human confirmations → nightly match and close',
    async () => {
      const buyer = await login('buyer', true),
        reviewer = await login('reviewer', true),
        leader = await login('leader', true),
        accountant = await login('accountant');
      let order = await createInUi(buyer, 10);
      assert.equal(order.status, 'SUBMITTED');
      check(`SELECT total_vnd FROM order_si WHERE id=${uuid(order.id)}`, 100000);
      order = await actionUi(reviewer, 'sell-in', order, 'Tiếp nhận', 'review');
      order = await actionUi(leader, 'sell-in', order, 'Duyệt trong hạn mức', 'approve');
      order = await actionUi(reviewer, 'sell-in', order, 'Cập nhật xuất kho', 'shipment', 10);
      order = await actionUi(buyer, 'sell-in', order, 'Xác nhận thực nhận', 'receipt', 10, true);
      assert.equal(order.status, 'RECEIVED');
      check(`SELECT received_qty FROM order_line WHERE order_si_id=${uuid(order.id)}`, 10);
      check(`SELECT count(*) FROM invoice WHERE order_si_id=${uuid(order.id)}`, 1);
      // Test-only time arrangement: make the just-received fixture belong to T, then invoke the real T+1 job.
      db(
        `UPDATE order_si SET created_at=${quote(fixture.day + 'T12:00:00+07:00')}::timestamptz WHERE id=${uuid(order.id)}`,
      );
      await call(accountant, 'POST', '/periods', {
        periodType: 'DAY',
        startsOn: fixture.day,
        endsOn: fixture.day,
        reason: 'P7 prepare day',
      });
      periodId = db(
        `SELECT id FROM period WHERE tenant_id=${uuid(ids.nsx)} AND starts_on=${quote(fixture.day)}::date AND period_type='DAY'`,
      );
      const recs = await call(accountant, 'GET', `/reconciliations?periodId=${periodId}`);
      for (let rec of recs.items) {
        rec = await call(accountant, 'POST', `/reconciliations/${rec.id}/confirm`, {
          version: rec.version,
          reason: 'NSX verified',
        });
        const npp = await login(rec.nppTenantId === ids.npp ? 'buyer' : 'over-buyer');
        await call(npp, 'POST', `/reconciliations/${rec.id}/confirm`, {
          version: rec.version,
          reason: 'NPP verified',
        });
      }
      await harness('nightly', 'close-day');
      check(`SELECT status FROM order_si WHERE id=${uuid(order.id)}`, 'PERIOD_CLOSED');
      check(`SELECT status FROM period WHERE id=${uuid(periodId)}`, 'CLOSED');
      check(
        `SELECT count(*) FROM reconciliation_exception e JOIN reconciliation r ON r.id=e.reconciliation_id WHERE r.period_id=${uuid(periodId)} AND e.status='OPEN'`,
        0,
      );
      await harness('nightly', 'close-day');
      check(
        `SELECT count(*) FROM order_status_history WHERE order_si_id=${uuid(order.id)} AND to_status='PERIOD_CLOSED'`,
        1,
      );
      withinOrder = order;
    },
  );
  await scenario(
    'b',
    'Credit hold, ASM denied, RSM/leader allowed, creator cannot approve',
    async () => {
      const buyer = await login('over-buyer'),
        asm = await login('asm'),
        rsm = await login('rsm', true),
        leader = await login('leader');
      const body = {
        nsxTenantId: ids.nsx,
        lines: [{ productId: ids.product, unitCode: 'EA', quantity: 9 }],
        reason: 'P7 held',
        submit: true,
        createdBy: ids.leader,
      };
      let order = await call(buyer, 'POST', '/orders/sell-in', body, 200, {
        'Idempotency-Key': crypto.randomUUID(),
      });
      assert.equal(order.status, 'HELD_CREDIT');
      check(`SELECT created_by FROM order_si WHERE id=${uuid(order.id)}`, ids['over-buyer']);
      await call(
        asm,
        'POST',
        `/orders/sell-in/${order.id}/approve-over-limit`,
        { version: order.version, reason: 'denied' },
        403,
      );
      await call(
        buyer,
        'POST',
        `/orders/sell-in/${order.id}/approve-over-limit`,
        { version: order.version, reason: 'self denied' },
        403,
      );
      order = await actionUi(rsm, 'sell-in', order, 'Duyệt vượt hạn mức', 'approve-over-limit');
      assert.equal(order.status, 'APPROVED');
      const next = await call(buyer, 'POST', '/orders/sell-in', body, 200, {
        'Idempotency-Key': crypto.randomUUID(),
      });
      const approved = await call(leader, 'POST', `/orders/sell-in/${next.id}/approve-over-limit`, {
        version: next.version,
        reason: 'leader override',
      });
      assert.equal(approved.status, 'APPROVED');
      check(
        `SELECT count(*) FROM audit_log WHERE resource_id IN(${uuid(order.id)},${uuid(next.id)}) AND action='ORDER_SI_APPROVED_OVER_LIMIT'`,
        2,
      );
    },
  );
  await scenario(
    'c',
    'Sell-out 9 ordered / 7 confirmed / 5 received → ticket/task → replacement 2 → close',
    async () => {
      const buyer = await login('buyer', true),
        shop = await login('shop-owner', true);
      let order = await mobileOrder(9);
      order = await actionUi(buyer, 'sell-out', order, 'Xác nhận đáp ứng', 'fulfillment', 7);
      assert.equal(order.status, 'PARTIALLY_CONFIRMED');
      order = await actionUi(buyer, 'sell-out', order, 'Cập nhật xuất kho', 'shipment', 7);
      order = await actionUi(shop, 'sell-out', order, 'Xác nhận thực nhận', 'receipt', 5, true);
      assert.equal(order.status, 'RECEIVED_ISSUE');
      check(`SELECT count(*) FROM ticket WHERE order_so_id=${uuid(order.id)}`, 1);
      check(
        `SELECT count(*) FROM task WHERE tenant_id=${uuid(ids.npp)} AND title LIKE ${quote('%' + order.orderCode)}`,
        1,
      );
      const replacement = await call(shop, 'POST', `/orders/sell-out/${order.id}/returns`, {
        requestType: 'REPLACEMENT',
        reason: 'Missing two',
        lines: [{ orderLineId: order.lines[0].id, quantity: 2 }],
      });
      await call(buyer, 'POST', `/returns/${replacement.id}/decision`, {
        approved: true,
        resolution: 'Deliver two missing units',
        version: replacement.version,
        orderVersion: order.version,
      });
      check(`SELECT status FROM order_so WHERE id=${uuid(order.id)}`, 'RECEIVED_ISSUE');
      await call(buyer, 'POST', `/returns/${replacement.id}/replacement-shipment`, {
        version: 1,
        reason: 'P7 replacement sent',
      });
      await call(shop, 'POST', `/returns/${replacement.id}/replacement-receipt`, {
        version: 2,
        reason: 'P7 replacement received',
      });
      check(`SELECT status FROM order_so WHERE id=${uuid(order.id)}`, 'CLOSED');
      check(`SELECT received_qty FROM order_line WHERE order_so_id=${uuid(order.id)}`, 7);
      check(`SELECT status FROM ticket WHERE order_so_id=${uuid(order.id)}`, 'CLOSED');
      check(`SELECT status FROM task WHERE order_so_id=${uuid(order.id)}`, 'DONE');
      check(
        `SELECT count(*) FROM replacement_delivery WHERE return_request_id=${uuid(replacement.id)} AND status='RECEIVED' AND shipped_by<>received_by`,
        1,
      );
    },
  );
  await scenario(
    'd',
    'Mobile response lost after commit → retry same Idempotency-Key',
    async () => {
      await mobileOrder(2, true);
    },
  );
  await scenario(
    'e',
    'Closed period rejects direct edits; adjustment requires a different approver',
    async () => {
      assert(withinOrder && periodId, 'Scenario a must pass first');
      const accountant = await login('accountant'),
        leader = await login('leader');
      const before = db(`SELECT total_vnd FROM order_si WHERE id=${uuid(withinOrder.id)}`);
      let denied = false;
      try {
        db(`UPDATE order_si SET total_vnd=999999 WHERE id=${uuid(withinOrder.id)}`);
      } catch {
        denied = true;
      }
      assert(denied, 'DB allowed a closed-period mutation');
      check(`SELECT total_vnd FROM order_si WHERE id=${uuid(withinOrder.id)}`, before);
      rejectDb(`UPDATE order_line SET received_qty=9 WHERE order_si_id=${uuid(withinOrder.id)}`);
      rejectDb(
        `UPDATE reconciliation SET received_amount_vnd=999999 WHERE period_id=${uuid(periodId)} AND npp_tenant_id=${uuid(ids.npp)}`,
      );
      rejectDb(`UPDATE period SET status='OPEN' WHERE id=${uuid(periodId)}`);
      const adjustment = await call(accountant, 'POST', '/period-adjustments', {
        periodId,
        nppTenantId: ids.npp,
        metricKey: 'RECEIVED',
        amountVnd: 1000,
        reason: 'P7 correction',
      });
      await call(
        accountant,
        'POST',
        `/period-adjustments/${adjustment.id}/approve`,
        { version: 0, reason: 'self approval' },
        403,
      );
      await call(leader, 'POST', `/period-adjustments/${adjustment.id}/approve`, {
        version: 0,
        reason: 'independent approval',
      });
      check(`SELECT status FROM period_adjustment WHERE id=${uuid(adjustment.id)}`, 'APPROVED');
      rejectDb(`UPDATE period_adjustment SET amount_vnd=999999 WHERE id=${uuid(adjustment.id)}`);
      check(
        `SELECT count(*) FROM period_adjustment WHERE id=${uuid(adjustment.id)} AND created_by<>approved_by`,
        1,
      );
      check(`SELECT total_vnd FROM order_si WHERE id=${uuid(withinOrder.id)}`, before);
      const report = await call(
        leader,
        'GET',
        `/reports/official/reconciliation?periodId=${periodId}&nppId=${ids.npp}`,
      );
      assert.equal(report.items[0].officialReceivedAmountVnd, 101000);
      const operations = await call(
        leader,
        'GET',
        `/reports/operations/reconciliation?periodId=${periodId}&nppId=${ids.npp}`,
      );
      assert.equal(operations.items[0].receivedAmountVnd, 100000);
      assert.equal(operations.items[0].officialReceivedAmountVnd, 101000);
    },
  );
  await scenario(
    'f',
    '16 roles × 109 permissions, tenant isolation and request ID tampering',
    async () => {
      let pairs = 0;
      for (const [role, email] of Object.entries(fixture.matrix)) {
        const actor = await login(email),
          actual = new Map(actor.me.permissions.map((p) => [p.code, p.scope]));
        const expected = new Map(Object.entries(oracle.grants[role]));
        if (['NSX_ADMIN', 'NPP_OWNER', 'NPP_STAFF'].includes(role))
          expected.set('foundation:import', 'A');
        for (const permission of [...oracle.permissions, 'foundation:import']) {
          assert.equal(actual.get(permission), expected.get(permission), `${role} × ${permission}`);
          pairs++;
        }
      }
      assert.equal(pairs, 1744);
      results.matrixPairs = pairs;
      const foreign = await login('foreign-buyer'),
        buyer = await login('buyer'),
        sales = await login('sales'),
        leader = await login('leader');
      const visible = await call(foreign, 'GET', '/orders/sell-in');
      assert.equal(visible.totalElements, 0);
      assert(!visible.items.some((row) => row.tenantId === ids.nsx));
      const candidate =
        (await call(buyer, 'GET', '/orders/sell-in')).items[0] ||
        (await call(
          buyer,
          'POST',
          '/orders/sell-in',
          {
            nsxTenantId: ids.nsx,
            lines: [{ productId: ids.product, unitCode: 'EA', quantity: 1 }],
            submit: true,
            reason: 'P7 isolated authorization fixture',
          },
          200,
          { 'Idempotency-Key': crypto.randomUUID() },
        ));
      await call(foreign, 'GET', `/orders/sell-in/${candidate.id}`, undefined, 404);
      await call(
        foreign,
        'POST',
        `/orders/sell-in/${candidate.id}/receipt`,
        {
          version: 0,
          reason: 'ID tamper',
          lines: [{ orderLineId: crypto.randomUUID(), receivedQty: 1, damagedQty: 0 }],
        },
        404,
      );
      const attack = {
        outletId: ids['foreign-shop-outlet'],
        nppTenantId: ids.npp,
        lines: [{ productId: ids.product, unitCode: 'EA', quantity: 1 }],
        reason: 'foreign outlet attack',
        submit: true,
      };
      await call(sales, 'POST', '/orders/sell-out', attack, 403, {
        'Idempotency-Key': crypto.randomUUID(),
      });
      const url = `/orders/sell-in/${candidate.id}/approve-over-limit`;
      const response = await buyer.http.post(`${api}/api/v1${url}`, {
        headers: { Authorization: leader.authorization.slice(0, -2) + 'xx' },
        data: { version: 0, reason: 'JWT tamper' },
      });
      assert.equal(response.status(), 401);
      check(
        `SELECT count(*) FROM order_so WHERE outlet_id=${uuid(ids['foreign-shop-outlet'])} AND created_by=${uuid(ids.sales)}`,
        0,
      );
      const outside = crypto.randomUUID();
      db(
        `INSERT INTO outlet(id,tenant_id,created_by_tenant_id,code,name) VALUES(${uuid(outside)},${uuid(ids.nsx)},${uuid(ids.nsx)},'P7-OUTSIDE','P7 outside sales route'); INSERT INTO outlet_npp(id,tenant_id,outlet_id,npp_tenant_id) VALUES(gen_random_uuid(),${uuid(ids.nsx)},${uuid(outside)},${uuid(ids.npp)})`,
      );
      const outsideOrder = await call(
        buyer,
        'POST',
        '/orders/sell-out',
        {
          outletId: outside,
          nppTenantId: ids.npp,
          lines: [{ productId: ids.product, unitCode: 'EA', quantity: 1 }],
          submit: true,
          reason: 'P7 outside assigned route',
        },
        200,
        { 'Idempotency-Key': crypto.randomUUID() },
      );
      await call(sales, 'GET', `/orders/sell-out/${outsideOrder.id}`, undefined, 404);
      results.matrixPairs = pairs;
    },
  );
  if (process.env.P7_SKIP_PERFORMANCE !== 'true')
    await scenario(
      'perf',
      '50,000 orders, 5,000 outlets: latency and query-count growth',
      async () => {
        await harness('perf');
        check(`SELECT count(*) FROM order_si WHERE created_by=${uuid(ids['perf-reader'])}`, 25000);
        check(`SELECT count(*) FROM order_so WHERE created_by=${uuid(ids['perf-reader'])}`, 25000);
        check(`SELECT count(*) FROM outlet WHERE tenant_id=${uuid(ids.perf)}`, 5000);
        check(
          `SELECT count(*) FROM order_line WHERE product_id=(SELECT id FROM product WHERE tenant_id=${uuid(ids.perf)} AND sku='P7-PERF-SKU')`,
          50000,
        );
        const actor = await login('perf-reader');
        const perfPeriod = db(`SELECT id FROM period WHERE tenant_id=${uuid(ids.perf)}`);
        const metrics = [];
        const queryStats = () =>
          JSON.parse(
            db(
              "SELECT json_build_object('calls',COALESCE(SUM(calls),0),'executionMs',COALESCE(SUM(total_exec_time),0)) FROM pg_stat_statements WHERE dbid=(SELECT oid FROM pg_database WHERE datname='synera_p7') AND query NOT ILIKE '%pg_stat_statements%' AND query NOT IN('BEGIN','COMMIT','ROLLBACK')",
            ),
          );
        for (const [name, path] of [
          ['sell-in', '/orders/sell-in?'],
          ['sell-out', '/orders/sell-out?'],
          ['outlets', '/outlets?'],
          ['npp', '/npps?'],
          ['reconciliation', `/reports/operations/reconciliation?periodId=${perfPeriod}&`],
        ]) {
          const counts = [];
          const databaseTimes = [];
          for (const size of [1, 50]) {
            await call(actor, 'GET', `${path}size=${size}`);
            const start = queryStats();
            await call(actor, 'GET', `${path}size=${size}`);
            const end = queryStats();
            counts.push(end.calls - start.calls);
            databaseTimes.push(+(end.executionMs - start.executionMs).toFixed(2));
          }
          const times = [];
          for (let i = 0; i < 7; i++) {
            const start = performance.now();
            const body = await call(actor, 'GET', `${path}size=50`);
            assert(body.items.length > 0, `${name} empty benchmark`);
            times.push(performance.now() - start);
          }
          times.sort((a, b) => a - b);
          metrics.push({
            name,
            p50ms: +times[3].toFixed(2),
            p95ms: +times[6].toFixed(2),
            queriesAt1: counts[0],
            queriesAt50: counts[1],
            databaseMsAt1: databaseTimes[0],
            databaseMsAt50: databaseTimes[1],
            noNPlusOne: counts[1] === counts[0],
          });
        }
        fs.writeFileSync(
          `${output}/performance.json`,
          JSON.stringify(
            {
              runId,
              generatedAt: new Date().toISOString(),
              orders: 50000,
              outlets: 5000,
              orderLines: 50000,
              metrics,
            },
            null,
            2,
          ),
        );
        const plan = db(
          `EXPLAIN(ANALYZE,BUFFERS,FORMAT JSON) SELECT id,order_code,status,total_vnd FROM order_si WHERE tenant_id=${uuid(ids.perf)} AND deleted_at IS NULL ORDER BY updated_at DESC,id LIMIT 50`,
        );
        fs.writeFileSync(`${output}/sell-in-plan.json`, plan);
        for (const metric of metrics) {
          assert(
            metric.noNPlusOne,
            `${metric.name}: query count grew ${metric.queriesAt1} → ${metric.queriesAt50}`,
          );
          assert(metric.p95ms < 2000, `${metric.name}: basic p95 budget exceeded (2s)`);
        }
      },
    );
} finally {
  const report = {
    runId,
    generatedAt: new Date().toISOString(),
    database: 'disposable PostgreSQL 16 / synera_p7',
    dbAssertions,
    scenarios: results,
    matrixPairs: results.matrixPairs || 0,
  };
  fs.writeFileSync(`${output}/results.json`, JSON.stringify(report, null, 2));
  fs.writeFileSync(
    `${output}/results.md`,
    [
      '# P7 acceptance results',
      '',
      '| Scenario | Result | DB assertions | ms |',
      '| --- | --- | ---: | ---: |',
      ...results.map(
        (r) =>
          `| ${r.code}: ${r.name} | ${r.status} | ${r.databaseAssertions} | ${r.milliseconds} |`,
      ),
      '',
      ...results.filter((r) => r.error).map((r) => `${r.code}: ${r.error}`),
    ].join('\n'),
  );
  for (const session of sessions.values()) {
    await session.context?.close();
    await session.http.dispose();
  }
  await browser?.close();
  server?.kill();
}
if (results.some((row) => row.status === 'FAIL')) process.exitCode = 1;
