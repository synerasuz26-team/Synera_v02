import { ApiError } from './client';
import { calculateOrder, createOrder, type OrderInput } from './services';

export type Draft = {
  id: string;
  key: string;
  owner: string;
  input: OrderInput;
  state: 'pending' | 'sending' | 'sent' | 'error' | 'price-change';
  expectedTotal?: number;
  quotedTotal?: number;
  message?: string;
  orderId?: string;
};
const storageKey = (owner: string) => `synera:orders:v1:${owner}`;
export function readDrafts(owner: string): Draft[] {
  const raw = localStorage.getItem(storageKey(owner));
  if (!raw) return [];
  const parsed: unknown = JSON.parse(raw);
  if (
    !Array.isArray(parsed) ||
    parsed.some(
      (draft) =>
        !draft ||
        draft.owner !== owner ||
        typeof draft.key !== 'string' ||
        typeof draft.id !== 'string' ||
        !Array.isArray(draft.input?.lines),
    )
  ) {
    throw new Error('Không thể đọc đơn nháp trên thiết bị. Dữ liệu được giữ nguyên để khôi phục.');
  }
  return parsed as Draft[];
}
export function saveDraft(draft: Draft) {
  const drafts = readDrafts(draft.owner);
  const index = drafts.findIndex((item) => item.id === draft.id);
  if (index < 0) drafts.push(draft);
  else drafts[index] = draft;
  localStorage.setItem(storageKey(draft.owner), JSON.stringify(drafts));
  if (typeof window !== 'undefined')
    window.dispatchEvent(
      new CustomEvent('synera:drafts-changed', {
        detail: { owner: draft.owner, pending: draft.state === 'pending' },
      }),
    );
}
const flights = new Map<string, Promise<void>>();
export function syncDrafts(owner: string, isCurrent: () => boolean, onChange: () => void) {
  const existing = flights.get(owner);
  if (existing) return existing;
  const run = async () => {
    for (const initial of readDrafts(owner)) {
      if (!isCurrent()) break;
      if (!['pending', 'sending'].includes(initial.state)) continue;
      const draft = { ...initial };
      try {
        // Requote before sending. Price differences always require a human decision.
        const quote = await calculateOrder(draft.input);
        if (!isCurrent()) break;
        if (draft.expectedTotal === undefined || quote.totalVnd !== draft.expectedTotal) {
          saveDraft({
            ...draft,
            quotedTotal: quote.totalVnd,
            state: 'price-change',
            message: 'Vui lòng xác nhận giá máy chủ trước khi gửi.',
          });
          onChange();
          continue;
        }
        saveDraft({ ...draft, state: 'sending' });
        onChange();
        const order = await createOrder('sell-out', draft.input, draft.key);
        saveDraft({
          ...draft,
          state: 'sent',
          orderId: order.id,
          message: `Đã gửi ${order.orderCode}`,
        });
        onChange();
      } catch (error) {
        const temporary = error instanceof ApiError && (error.status === 0 || error.status >= 500);
        saveDraft({
          ...draft,
          state: temporary ? 'pending' : 'error',
          message: error instanceof Error ? error.message : 'Không thể gửi đơn.',
        });
        onChange();
        if (temporary || !isCurrent()) break;
      }
    }
  };
  const operation =
    typeof navigator !== 'undefined' && navigator.locks
      ? navigator.locks.request(`synera:sync:${owner}`, run)
      : run();
  const flight = operation.finally(() => {
    flights.delete(owner);
  });
  flights.set(owner, flight);
  return flight;
}
