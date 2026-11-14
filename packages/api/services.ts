import { apiRequest } from './client';
export type Page<T> = {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};
export type RecordRow = { id: string; version?: number; [key: string]: unknown };
export type OrderLine = {
  id: string;
  productId: string;
  sku: string;
  productName: string;
  unitCode: string;
  orderedQty: number;
  confirmedQty: number;
  shippedQty: number;
  receivedQty: number;
  gift: boolean;
  unitPriceVnd: number;
  lineAmountVnd: number;
};
export type Order = RecordRow & {
  orderCode: string;
  status: string;
  version: number;
  totalVnd: number;
  lines: OrderLine[];
  history: { fromStatus: string; toStatus: string; reason: string; occurredAt: string }[];
};
export type OrderInput = {
  nsxTenantId?: string;
  nppTenantId?: string;
  outletId?: string;
  lines: { productId: string; unitCode: string; quantity: number }[];
  note?: string;
  voucherCode?: string;
  reason: string;
  submit: boolean;
};
export function queryString(values: Record<string, string | number | undefined>) {
  const query = new URLSearchParams();
  Object.entries(values).forEach(([key, value]) => {
    if (value !== undefined && value !== '') query.set(key, String(value));
  });
  return query.toString();
}
export const listRecords = (
  path: string,
  filters: Record<string, string | number | undefined>,
  signal?: AbortSignal,
) => apiRequest<Page<RecordRow>>(`${path}?${queryString(filters)}`, { signal });
export const getRecord = <T = RecordRow>(path: string, signal?: AbortSignal) =>
  apiRequest<T>(path, { signal });
export const writeRecord = <T = RecordRow>(
  path: string,
  body: unknown,
  method = 'POST',
  key?: string,
) =>
  apiRequest<T>(path, {
    method,
    body: JSON.stringify(body),
    headers: key ? { 'Idempotency-Key': key } : undefined,
  });
export const createOrder = (kind: 'sell-in' | 'sell-out', body: OrderInput, key: string) =>
  writeRecord<Order>(`/orders/${kind}`, body, 'POST', key);
export const calculateOrder = (input: OrderInput, signal?: AbortSignal) =>
  apiRequest<{ totalVnd: number; calculatedAt: string }>('/prices/calculate', {
    method: 'POST',
    signal,
    body: JSON.stringify({
      nppId: input.nppTenantId,
      outletId: input.outletId,
      lines: input.lines,
      voucherCode: input.voucherCode,
    }),
  });
