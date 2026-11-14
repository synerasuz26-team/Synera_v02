import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from './client';
import { calculateOrder, createOrder } from './services';
import { readDrafts, saveDraft, syncDrafts, type Draft } from './offline';
vi.mock('./services', () => ({ calculateOrder: vi.fn(), createOrder: vi.fn() }));
const input = {
  nppTenantId: 'npp',
  outletId: 'outlet',
  lines: [{ productId: 'product', unitCode: 'CASE', quantity: 2 }],
  reason: 'Đặt hàng',
  submit: true,
};
const draft: Draft = {
  id: 'draft',
  key: 'stable-key',
  owner: 'tenant:user',
  input,
  state: 'pending',
  expectedTotal: 100,
};
beforeEach(() => {
  const storage = new Map<string, string>();
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => storage.get(key) ?? null,
    setItem: (key: string, value: string) => storage.set(key, value),
  });
  vi.stubGlobal('navigator', {});
  vi.resetAllMocks();
});
describe('offline orders', () => {
  it('requires explicit confirmation when the server price changes', async () => {
    saveDraft(draft);
    vi.mocked(calculateOrder).mockResolvedValue({ totalVnd: 120, calculatedAt: '' });
    await syncDrafts(
      draft.owner,
      () => true,
      () => {},
    );
    expect(readDrafts(draft.owner)[0]).toMatchObject({ state: 'price-change', quotedTotal: 120 });
    expect(createOrder).not.toHaveBeenCalled();
  });
  it('retries transient failure with the same idempotency key and never resends sent orders', async () => {
    saveDraft(draft);
    vi.mocked(calculateOrder).mockResolvedValue({ totalVnd: 100, calculatedAt: '' });
    vi.mocked(createOrder)
      .mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', 'Mất mạng'))
      .mockResolvedValueOnce({ id: 'order', orderCode: 'SO' } as Awaited<
        ReturnType<typeof createOrder>
      >);
    await syncDrafts(
      draft.owner,
      () => true,
      () => {},
    );
    expect(readDrafts(draft.owner)[0].state).toBe('pending');
    await syncDrafts(
      draft.owner,
      () => true,
      () => {},
    );
    await syncDrafts(
      draft.owner,
      () => true,
      () => {},
    );
    expect(createOrder).toHaveBeenCalledTimes(2);
    expect(createOrder).toHaveBeenNthCalledWith(1, 'sell-out', input, 'stable-key');
    expect(createOrder).toHaveBeenNthCalledWith(2, 'sell-out', input, 'stable-key');
    expect(readDrafts('other-tenant:user')).toEqual([]);
  });
  it('does not automatically retry permission failures', async () => {
    saveDraft(draft);
    vi.mocked(calculateOrder).mockRejectedValue(new ApiError(403, 'FORBIDDEN', 'Không có quyền'));
    await syncDrafts(
      draft.owner,
      () => true,
      () => {},
    );
    await syncDrafts(
      draft.owner,
      () => true,
      () => {},
    );
    expect(readDrafts(draft.owner)[0].state).toBe('error');
    expect(calculateOrder).toHaveBeenCalledTimes(1);
  });
  it('preserves unreadable storage instead of overwriting drafts', () => {
    localStorage.setItem('synera:orders:v1:tenant:user', 'broken');
    expect(() => saveDraft(draft)).toThrow();
    expect(localStorage.getItem('synera:orders:v1:tenant:user')).toBe('broken');
  });
});
