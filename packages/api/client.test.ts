import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiRequest, getAccessToken, loginAccount, logoutAccount } from './client';

const session = {
  id: 'user',
  fullName: 'User',
  email: 'user@example.com',
  accessToken: 'old',
  refreshToken: 'refresh',
  deviceId: 'device',
  tokenType: 'Bearer',
  expiresIn: 900,
};
beforeEach(async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 204 })));
  await logoutAccount();
});
afterEach(() => vi.unstubAllGlobals());
describe('authenticated API transport', () => {
  it('downloads binary templates through the same authenticated transport', async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(Response.json(session))
      .mockResolvedValueOnce(
        new Response('xlsx-bytes', {
          headers: {
            'Content-Type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
          },
        }),
      );
    vi.stubGlobal('fetch', fetcher);
    await loginAccount('user@example.com', 'password');
    const blob = await apiRequest<Blob>('/foundation-imports/products/template', {}, 'blob');
    expect(await blob.text()).toBe('xlsx-bytes');
    expect(new Headers(fetcher.mock.calls[1][1].headers).get('Authorization')).toBe('Bearer old');
  });
  it('rotates once for concurrent 401s, replaying the original body and idempotency key', async () => {
    let rotations = 0;
    const requests: { token: string | null; key: string | null; body: unknown }[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string, init: RequestInit) => {
        if (url.endsWith('/auth/login')) return Response.json(session);
        if (url.endsWith('/auth/refresh')) {
          rotations++;
          await Promise.resolve();
          return Response.json({ ...session, accessToken: 'new', refreshToken: 'rotated' });
        }
        const headers = new Headers(init.headers);
        requests.push({
          token: headers.get('Authorization'),
          key: headers.get('Idempotency-Key'),
          body: init.body,
        });
        return headers.get('Authorization') === 'Bearer old'
          ? Response.json({}, { status: 401 })
          : Response.json({ ok: true });
      }),
    );
    await loginAccount('user@example.com', 'password');
    await Promise.all([
      apiRequest('/orders/sell-out', {
        method: 'POST',
        headers: { 'Idempotency-Key': 'stable' },
        body: '{"lines":[]}',
      }),
      apiRequest('/products'),
    ]);
    expect(rotations).toBe(1);
    expect(getAccessToken()).toBe('new');
    expect(requests.filter((row) => row.key === 'stable')).toEqual([
      { token: 'Bearer old', key: 'stable', body: '{"lines":[]}' },
      { token: 'Bearer new', key: 'stable', body: '{"lines":[]}' },
    ]);
  });
  it('does not refresh 403 and shows a Vietnamese permission error', async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(Response.json(session))
      .mockResolvedValueOnce(new Response('', { status: 403 }));
    vi.stubGlobal('fetch', fetcher);
    await loginAccount('user@example.com', 'password');
    await expect(apiRequest('/invoices')).rejects.toThrow('Bạn không có quyền');
    expect(fetcher).toHaveBeenCalledTimes(2);
  });
  it('clears an invalid refresh session and stops after one refresh attempt', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(Response.json(session))
        .mockResolvedValue(Response.json({ message: 'Phiên đã hết hạn' }, { status: 401 })),
    );
    await loginAccount('user@example.com', 'password');
    await expect(apiRequest('/products')).rejects.toThrow('Phiên đã hết hạn');
    expect(getAccessToken()).toBeNull();
  });
  it('passes cancellation through without converting it to a network error', async () => {
    const controller = new AbortController();
    controller.abort();
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(Response.json(session))
        .mockRejectedValue(new DOMException('Aborted', 'AbortError')),
    );
    await loginAccount('user@example.com', 'password');
    await expect(apiRequest('/products', { signal: controller.signal })).rejects.toMatchObject({
      name: 'AbortError',
    });
  });
  it('does not restore a session after logout while refresh is in flight', async () => {
    let release!: (response: Response) => void;
    let refreshing!: () => void;
    const started = new Promise<void>((resolve) => {
      refreshing = resolve;
    });
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url.endsWith('/login')) return Promise.resolve(Response.json(session));
        if (url.endsWith('/logout')) return Promise.resolve(new Response(null, { status: 204 }));
        if (url.endsWith('/refresh')) {
          refreshing();
          return new Promise<Response>((resolve) => {
            release = resolve;
          });
        }
        return Promise.resolve(new Response(null, { status: 401 }));
      }),
    );
    await loginAccount('user@example.com', 'password');
    const request = apiRequest('/products');
    const assertion = expect(request).rejects.toThrow('Phiên đăng nhập đã thay đổi');
    await started;
    await logoutAccount();
    release(Response.json({ ...session, accessToken: 'new' }));
    await assertion;
    expect(getAccessToken()).toBeNull();
  });
});
