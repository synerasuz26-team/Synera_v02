import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { can, type CurrentUser } from '../../packages/api/client';
import { getRecord, writeRecord, type Order } from '../../packages/api/services';
import { DataView, ErrorState, FieldsForm, Loading } from './components';
import { reason, type Field } from './resources';

type Action = {
  suffix: string;
  label: string;
  permission: string;
  mode?: 'quantity' | 'receipt' | 'return' | 'reject';
  fields?: Field[];
};
export function OrderDetail({ path, me }: { path: string; me: CurrentUser }) {
  const client = useQueryClient();
  const detail = useQuery({
    queryKey: ['detail', path],
    queryFn: ({ signal }) => getRecord<Order>(path, signal),
    staleTime: 0,
    refetchOnMount: 'always',
  });
  const [action, setAction] = useState<Action | null>(null);
  const mutation = useMutation({
    mutationFn: ({ body, selected }: { body: Record<string, unknown>; selected: Action }) =>
      writeRecord<Order>(`${path}/${selected.suffix}`, body),
    onMutate: async () => {
      await client.cancelQueries({ queryKey: ['detail', path] });
      const previous = client.getQueryData<Order>(['detail', path]);
      // Show the pending confirmation without predicting a business status.
      if (previous)
        client.setQueryData(['detail', path], { ...previous, pendingConfirmation: true });
      return { previous };
    },
    onSuccess: (result, variables) => {
      if (variables.selected.suffix !== 'returns') client.setQueryData(['detail', path], result);
      setAction(null);
    },
    onError: (_error, _variables, context) => {
      if (context?.previous) client.setQueryData(['detail', path], context.previous);
    },
    onSettled: async () => {
      await Promise.all([
        client.invalidateQueries({ queryKey: ['detail', path] }),
        client.invalidateQueries({ queryKey: ['records'] }),
        client.invalidateQueries({ queryKey: ['returns', path] }),
      ]);
    },
  });
  if (detail.isPending) return <Loading />;
  if (detail.isError)
    return <ErrorState error={detail.error} retry={() => void detail.refetch()} />;
  const order = detail.data;
  const si = path.includes('/sell-in/');
  const actions: Action[] = si
    ? [
        { suffix: 'submit', label: 'Gửi duyệt', permission: 'order_si:create' },
        { suffix: 'review', label: 'Tiếp nhận', permission: 'order_si:review' },
        { suffix: 'approve', label: 'Duyệt trong hạn mức', permission: 'order_si:approve' },
        {
          suffix: 'approve-over-limit',
          label: 'Duyệt vượt hạn mức',
          permission: 'order_si:approve_over_limit',
        },
        {
          suffix: 'reject',
          label: 'Từ chối / yêu cầu bổ sung',
          permission: 'order_si:reject',
          mode: 'reject',
        },
        { suffix: 'cancel', label: 'Hủy đơn nháp', permission: 'order_si:cancel_draft' },
        {
          suffix: 'shipment',
          label: 'Cập nhật xuất kho',
          permission: 'order_si:ship',
          mode: 'quantity',
        },
        {
          suffix: 'receipt',
          label: 'Xác nhận thực nhận',
          permission: 'order_si:receive_confirm',
          mode: 'receipt',
        },
      ]
    : [
        { suffix: 'submit', label: 'Gửi đơn nháp', permission: 'order_so:create' },
        { suffix: 'cancel', label: 'Hủy trước xác nhận', permission: 'order_so:update_draft' },
        {
          suffix: 'cancel-confirmed',
          label: 'Hủy đơn đã xác nhận',
          permission: 'order_so:cancel_confirmed',
        },
        {
          suffix: 'fulfillment',
          label: 'Xác nhận đáp ứng',
          permission: 'order_so:fulfill_confirm',
          mode: 'quantity',
          fields: [
            {
              key: 'expectedDeliveryAt',
              label: 'Ngày giao dự kiến phần còn thiếu',
              type: 'datetime-local',
              optional: true,
            },
          ],
        },
        {
          suffix: 'shipment',
          label: 'Cập nhật xuất kho',
          permission: 'order_so:ship_update',
          mode: 'quantity',
        },
        {
          suffix: 'receipt',
          label: 'Xác nhận thực nhận',
          permission: 'order_so:receive_confirm',
          mode: 'receipt',
        },
        {
          suffix: 'returns',
          label: 'Yêu cầu đổi trả / giao bù',
          permission: 'return:create',
          mode: 'return',
          fields: [{ key: 'requestType', label: 'Loại yêu cầu: RETURN / EXCHANGE / REPLACEMENT' }],
        },
      ];
  function send(body: Record<string, unknown>) {
    if (!action) return;
    const lines = order.lines.map((line) =>
      action.mode === 'receipt'
        ? {
            orderLineId: line.id,
            receivedQty: body[`qty:${line.id}`],
            damagedQty: body[`damage:${line.id}`],
          }
        : { orderLineId: line.id, quantity: body[`qty:${line.id}`] },
    );
    const payload: Record<string, unknown> = { reason: body.reason, version: order.version };
    if (action.mode === 'quantity' || action.mode === 'receipt' || action.mode === 'return')
      payload.lines = lines;
    if (action.mode === 'receipt' && !si)
      payload.attachmentIds = String(body.attachmentIds || '')
        .split(',')
        .map((value) => value.trim())
        .filter(Boolean);
    if (action.mode === 'reject') payload.needInfo = body.needInfo;
    if (action.mode === 'return') payload.requestType = body.requestType;
    if (action.suffix === 'fulfillment') payload.expectedDeliveryAt = body.expectedDeliveryAt;
    // Return endpoint has a different response shape; invalidate instead of rendering it as an order.
    mutation.mutate({ body: payload, selected: action });
  }
  const fields: Field[] = [reason, ...(action?.fields || [])];
  if (action?.mode === 'reject')
    fields.push({
      key: 'needInfo',
      label: 'Yêu cầu bổ sung thay vì từ chối',
      type: 'checkbox',
      optional: true,
    });
  if (action && ['quantity', 'receipt', 'return'].includes(action.mode || '')) {
    order.lines.forEach((line) => {
      fields.push({
        key: `qty:${line.id}`,
        label: `${line.sku} ${line.gift ? '(hàng tặng)' : ''} · ${line.unitCode} · ${action.mode === 'receipt' ? 'Thực nhận' : 'Số lượng'}`,
        type: 'number',
        min: action.mode === 'return' ? 1 : 0,
      });
      if (action.mode === 'receipt')
        fields.push({ key: `damage:${line.id}`, label: `${line.sku} · Hỏng`, type: 'number' });
    });
  }
  if (action?.mode === 'receipt' && !si)
    fields.push({
      key: 'attachmentIds',
      label: 'ID ảnh chứng từ, phân cách dấu phẩy',
      optional: true,
    });
  return (
    <>
      <h3>{order.orderCode}</h3>
      <DataView data={order} />
      <div className="live-actions">
        {actions
          .filter((item) => can(me, item.permission))
          .map((item) => (
            <button
              disabled={mutation.isPending || detail.isFetching}
              key={item.suffix}
              onClick={() => {
                setAction(item);
                mutation.reset();
              }}
            >
              {item.label}
            </button>
          ))}
      </div>
      {action && (
        <section>
          <h3>{action.label}</h3>
          <FieldsForm
            key={action.suffix}
            fields={fields}
            onSubmit={send}
            busy={mutation.isPending || detail.isFetching}
          />
        </section>
      )}
      {mutation.isError && <ErrorState error={mutation.error} />}
      {!si && <ReturnWorkflow path={path} order={order} me={me} />}
    </>
  );
}
type ReturnRow = {
  id: string;
  status: string;
  type: string;
  requester: string;
  version: number;
  delivery_status: string | null;
  reason: string;
};
function ReturnWorkflow({ path, order, me }: { path: string; order: Order; me: CurrentUser }) {
  const client = useQueryClient();
  const [reasonText, setReasonText] = useState('');
  const requests = useQuery({
    queryKey: ['returns', path],
    queryFn: ({ signal }) => getRecord<ReturnRow[]>(`${path}/returns`, signal),
    staleTime: 0,
  });
  const update = useMutation({
    mutationFn: ({ row, step }: { row: ReturnRow; step: string }) =>
      writeRecord(
        `/returns/${row.id}/${step}`,
        step === 'decision'
          ? {
              approved: true,
              resolution: reasonText,
              version: row.version,
              orderVersion: order.version,
            }
          : { version: row.version, reason: reasonText },
      ),
    onSuccess: async () => {
      setReasonText('');
      await Promise.all([
        client.invalidateQueries({ queryKey: ['returns', path] }),
        client.invalidateQueries({ queryKey: ['detail', path] }),
        client.invalidateQueries({ queryKey: ['records'] }),
      ]);
    },
  });
  if (requests.isPending) return <Loading />;
  if (requests.isError)
    return <ErrorState error={requests.error} retry={() => void requests.refetch()} />;
  if (!requests.data.length) return null;
  return (
    <section className="live-panel">
      <h3>Xử lý giao bù</h3>
      <label>
        Lý do xử lý giao bù
        <input
          maxLength={500}
          value={reasonText}
          onChange={(event) => setReasonText(event.target.value)}
        />
      </label>
      {requests.data.map((row) => (
        <article key={row.id}>
          <p>{row.reason}</p>
          <p>
            {(
              {
                PENDING: 'Chờ giao bù',
                SHIPPED: 'Đã giao, chờ xác nhận nhận bù',
                RECEIVED: 'Đã nhận bù',
              } as Record<string, string>
            )[row.delivery_status || ''] ||
              (row.status === 'SUBMITTED' ? 'Chờ duyệt yêu cầu' : row.status)}
          </p>
          <div className="live-actions">
            {row.status === 'SUBMITTED' &&
              row.requester !== me.user.id &&
              can(me, 'return:approve') && (
                <button
                  disabled={!reasonText.trim() || update.isPending}
                  onClick={() => update.mutate({ row, step: 'decision' })}
                >
                  Duyệt yêu cầu giao bù
                </button>
              )}
            {row.delivery_status === 'PENDING' && can(me, 'order_so:ship_update') && (
              <button
                disabled={!reasonText.trim() || update.isPending}
                onClick={() => update.mutate({ row, step: 'replacement-shipment' })}
              >
                Xác nhận đã giao bù
              </button>
            )}
            {row.delivery_status === 'SHIPPED' && can(me, 'order_so:receive_confirm') && (
              <button
                disabled={!reasonText.trim() || update.isPending}
                onClick={() => update.mutate({ row, step: 'replacement-receipt' })}
              >
                Xác nhận nhận đủ hàng bù
              </button>
            )}
          </div>
        </article>
      ))}
      {update.isError && <ErrorState error={update.error} />}
    </section>
  );
}
