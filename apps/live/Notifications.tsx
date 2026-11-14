import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiRequest, type CurrentUser } from '../../packages/api/client';
import { ErrorState, Loading } from './components';

type Notice = {
  id: string;
  type: string;
  payload: Record<string, unknown>;
  createdAt: string;
  readAt: string | null;
};
type Inbox = { items: Notice[]; totalElements: number; totalPages: number };
const titles: Record<string, string> = {
  ORDER_STATUS_CHANGED: 'Đơn hàng đổi trạng thái',
  TASK_ASSIGNED: 'Công việc mới được giao',
  TASK_DUE: 'Công việc đến hạn',
  APPOINTMENT_DUE: 'Lời hẹn đến hạn',
  TICKET_SLA_WARNING: 'Ticket sắp quá hạn SLA',
  ORDER_CONFIRMATION_REMINDER: 'Nhắc xác nhận đơn / nhận hàng',
  ORDER_CONFIRMATION_ESCALATION: 'Đơn quá hạn cần quản lý xử lý',
  NPP_ORDER_CYCLE: 'Đã đến chu kỳ đặt hàng',
  ORDER_RECEIVED_ISSUE: 'Đơn nhận thiếu hoặc hỏng',
};
export function InboxNav({
  me,
  active,
  open,
}: {
  me: CurrentUser;
  active: boolean;
  open: () => void;
}) {
  const unread = useQuery({
    queryKey: ['unread', me.tenant.id, me.user.id],
    queryFn: ({ signal }) => apiRequest<Inbox>('/notifications?unreadOnly=true&size=1', { signal }),
    refetchInterval: 45000,
    retry: false,
  });
  return (
    <button className={active ? 'active' : ''} onClick={open}>
      Thông báo{unread.data?.totalElements ? ` (${unread.data.totalElements})` : ''}
    </button>
  );
}
export default function Notifications({ me }: { me: CurrentUser }) {
  const [page, setPage] = useState(0);
  const [unread, setUnread] = useState(false);
  const client = useQueryClient();
  const list = useQuery({
    queryKey: ['notifications', me.tenant.id, me.user.id, page, unread],
    queryFn: ({ signal }) =>
      apiRequest<Inbox>(`/notifications?page=${page}&size=25&unreadOnly=${unread}`, { signal }),
    refetchInterval: 45000,
  });
  const read = useMutation({
    mutationFn: (id?: string) =>
      apiRequest(id ? `/notifications/${id}/read` : '/notifications/read-all', { method: 'POST' }),
    onSuccess: async () => {
      await Promise.all([
        client.invalidateQueries({ queryKey: ['notifications'] }),
        client.invalidateQueries({ queryKey: ['unread'] }),
      ]);
    },
  });
  return (
    <section className="live-panel">
      <h1>Thông báo</h1>
      <div className="live-actions">
        <button
          onClick={() => {
            setUnread(!unread);
            setPage(0);
          }}
        >
          {unread ? 'Xem tất cả' : 'Chỉ chưa đọc'}
        </button>
        <button disabled={read.isPending} onClick={() => read.mutate(undefined)}>
          Đánh dấu tất cả đã đọc
        </button>
      </div>
      {read.isError && <ErrorState error={read.error} />}
      {list.isPending ? (
        <Loading />
      ) : list.isError ? (
        <ErrorState error={list.error} retry={() => void list.refetch()} />
      ) : (
        <>
          {list.data.items.length === 0 && <p>Không có thông báo.</p>}
          <ul className="live-notices">
            {list.data.items.map((notice) => (
              <li key={notice.id}>
                <strong>{titles[notice.type] || 'Thông báo SYNERA'}</strong>
                <time>
                  {new Intl.DateTimeFormat('vi-VN', {
                    dateStyle: 'short',
                    timeStyle: 'short',
                    timeZone: 'Asia/Ho_Chi_Minh',
                  }).format(new Date(notice.createdAt))}
                </time>
                {notice.payload.title != null && <p>{String(notice.payload.title)}</p>}
                {notice.payload.status != null && (
                  <p>Trạng thái: {String(notice.payload.status)}</p>
                )}
                {!notice.readAt ? (
                  <button disabled={read.isPending} onClick={() => read.mutate(notice.id)}>
                    Đánh dấu đã đọc
                  </button>
                ) : (
                  <span>Đã đọc</span>
                )}
              </li>
            ))}
          </ul>
          <div className="live-actions">
            <button disabled={page === 0} onClick={() => setPage(page - 1)}>
              Trước
            </button>
            <span>
              Trang {page + 1} / {Math.max(1, list.data.totalPages)}
            </span>
            <button disabled={page + 1 >= list.data.totalPages} onClick={() => setPage(page + 1)}>
              Sau
            </button>
          </div>
        </>
      )}
    </section>
  );
}
