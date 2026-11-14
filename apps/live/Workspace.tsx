import { useEffect, useState, useSyncExternalStore } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Navigate, useNavigate } from 'react-router-dom';
import { Brand } from '../../src/shared/Brand';
import {
  can,
  getCurrentUser,
  hasSession,
  homeFor,
  logoutAccount,
  subscribeSession,
  type CurrentUser,
} from '../../packages/api/client';
import { readDrafts, saveDraft, syncDrafts, type Draft } from '../../packages/api/offline';
import { resources } from './resources';
import { ResourceScreen } from './ResourceScreen';
import { display, ErrorState, Loading } from './components';
import FoundationImports from './FoundationImports';
import Notifications, { InboxNav } from './Notifications';
import OperationsPanel from './OperationsPanel';
import StoragePanel from './StoragePanel';

export default function Workspace({ portal }: { portal: 'nsx' | 'npp' | 'outlet' | 'sales' }) {
  const signedIn = useSyncExternalStore(subscribeSession, hasSession);
  const navigate = useNavigate();
  const client = useQueryClient();
  const me = useQuery({
    queryKey: ['me'],
    queryFn: ({ signal }) => getCurrentUser(signal),
    enabled: signedIn,
  });
  const [selected, setSelected] = useState('');
  const [logoutError, setLogoutError] = useState<unknown>(null);
  useEffect(() => {
    if (portal === 'sales' && import.meta.env.PROD && 'serviceWorker' in navigator) {
      void navigator.serviceWorker.register('/mobile-sales-sw.js').catch(setLogoutError);
    }
  }, [portal]);
  if (!signedIn) return <Navigate to="/login" replace />;
  if (me.isPending)
    return (
      <main className="live-workspace">
        <Loading />
      </main>
    );
  if (me.isError)
    return (
      <main className="live-workspace">
        <ErrorState error={me.error} retry={() => void me.refetch()} />
        <button onClick={() => void logoutAccount().catch(setLogoutError)}>Đăng nhập lại</button>
      </main>
    );
  const user = me.data;
  const correct = homeFor(user).slice(1);
  if (
    (user.tenant.type === 'NPP' && portal !== 'npp') ||
    (user.tenant.type === 'OUTLET' && portal !== 'outlet') ||
    (['NSX', 'PLATFORM'].includes(user.tenant.type) && ['npp', 'outlet'].includes(portal))
  )
    return <Navigate to={`/${correct}`} replace />;
  const visible = resources.filter((resource) =>
    resource.permissions.some((permission) => can(user, permission)),
  );
  const active = visible.find((resource) => resource.path === selected) || visible[0];
  const titles = {
    nsx: 'Điều hành nhà sản xuất',
    npp: 'Portal nhà phân phối',
    outlet: 'Cổng điểm bán',
    sales: 'Mobile Sales',
  };
  async function logout() {
    try {
      await logoutAccount();
      client.clear();
      navigate('/login', { replace: true });
    } catch (error) {
      setLogoutError(error);
    }
  }
  return (
    <div className={`live-workspace ${portal === 'sales' ? 'live-mobile' : ''}`}>
      <aside className="live-sidebar">
        <Brand />
        <p>{titles[portal]}</p>
        <nav aria-label="Chức năng">
          <InboxNav
            me={user}
            active={selected === 'notifications'}
            open={() => setSelected('notifications')}
          />
          {can(user, 'config:manage') && (
            <button
              className={selected === 'operations' ? 'active' : ''}
              onClick={() => setSelected('operations')}
            >
              Cấu hình vận hành
            </button>
          )}
          {can(user, 'app:admin_console') && (
            <button
              className={selected === 'storage' ? 'active' : ''}
              onClick={() => setSelected('storage')}
            >
              Dung lượng database
            </button>
          )}
          {can(user, 'foundation:import') && (
            <button
              className={selected === 'imports' ? 'active' : ''}
              onClick={() => setSelected('imports')}
            >
              Nhập dữ liệu nền
            </button>
          )}
          {visible.map((resource) => (
            <button
              className={
                active?.path === resource.path &&
                !['drafts', 'imports', 'notifications', 'operations'].includes(selected)
                  ? 'active'
                  : ''
              }
              key={resource.path}
              onClick={() => setSelected(resource.path)}
            >
              {resource.label}
            </button>
          ))}
          {portal === 'sales' && can(user, 'order_so:create') && (
            <button
              className={selected === 'drafts' ? 'active' : ''}
              onClick={() => setSelected('drafts')}
            >
              Đơn chờ gửi
            </button>
          )}
        </nav>
        <div className="live-identity">
          <strong>{user.user.fullName}</strong>
          <span>{user.tenant.name}</span>
          <span>{user.roles.map((role) => role.name).join(', ')}</span>
          <button onClick={() => void logout()}>Đăng xuất</button>
        </div>
      </aside>
      <main className="live-main">
        <header className="live-topbar">
          <span>{titles[portal]}</span>
          <time>
            {new Intl.DateTimeFormat('vi-VN', {
              dateStyle: 'full',
              timeZone: 'Asia/Ho_Chi_Minh',
            }).format(new Date())}
          </time>
        </header>
        {logoutError !== null && <ErrorState error={logoutError} />}
        {selected === 'imports' && can(user, 'foundation:import') && <FoundationImports />}
        {selected === 'notifications' && <Notifications me={user} />}
        {selected === 'operations' && can(user, 'config:manage') && <OperationsPanel me={user} />}
        {selected === 'storage' && can(user, 'app:admin_console') && <StoragePanel me={user} />}
        {!['imports', 'notifications', 'operations'].includes(selected) &&
          selected !== 'storage' &&
          !(selected === 'drafts' && portal === 'sales') &&
          (active ? (
            <ResourceScreen
              key={`${portal}:${active.path}`}
              resource={active}
              me={user}
              mobile={portal === 'sales'}
            />
          ) : (
            <div className="live-empty">
              Tài khoản chưa được cấp quyền xem dữ liệu. Vui lòng liên hệ quản trị viên.
            </div>
          ))}
        {portal === 'sales' && can(user, 'order_so:create') && (
          <section hidden={selected !== 'drafts'}>
            <DraftQueue me={user} />
          </section>
        )}
      </main>
    </div>
  );
}
const draftState = {
  pending: 'Chờ gửi',
  sending: 'Đang gửi',
  sent: 'Đã gửi',
  error: 'Lỗi',
  'price-change': 'Giá cần xác nhận',
};
function DraftQueue({ me }: { me: CurrentUser }) {
  const client = useQueryClient();
  const owner = `${me.tenant.id}:${me.user.id}`;
  const [drafts, setDrafts] = useState<Draft[]>([]);
  const [error, setError] = useState<unknown>(null);
  const reload = () => {
    try {
      setDrafts(readDrafts(owner));
      setError(null);
    } catch (problem) {
      setError(problem);
    }
  };
  useEffect(() => {
    let alive = true;
    const refresh = () => {
      if (alive) reload();
    };
    const sync = () => {
      if (!navigator.onLine) return;
      void syncDrafts(
        owner,
        () =>
          alive && hasSession() && client.getQueryData<CurrentUser>(['me'])?.user.id === me.user.id,
        refresh,
      )
        .then(() => client.invalidateQueries({ queryKey: ['records'] }))
        .catch((problem) => {
          if (alive) setError(problem);
        });
    };
    reload();
    sync();
    const changed = (event: Event) => {
      const detail = (event as CustomEvent<{ owner: string; pending: boolean }>).detail;
      if (detail?.owner !== owner) return;
      refresh();
      if (detail.pending) sync();
    };
    const timer = window.setInterval(sync, 30000);
    window.addEventListener('online', sync);
    window.addEventListener('storage', refresh);
    window.addEventListener('synera:drafts-changed', changed);
    return () => {
      alive = false;
      window.clearInterval(timer);
      window.removeEventListener('online', sync);
      window.removeEventListener('storage', refresh);
      window.removeEventListener('synera:drafts-changed', changed);
    };
  }, [owner, client, me.user.id]);
  function retry(draft: Draft, acceptPrice = false) {
    try {
      saveDraft({
        ...draft,
        state: 'pending',
        expectedTotal: acceptPrice ? draft.quotedTotal : draft.expectedTotal,
      });
      reload();
      void syncDrafts(
        owner,
        () => hasSession() && client.getQueryData<CurrentUser>(['me'])?.user.id === me.user.id,
        reload,
      ).catch(setError);
    } catch (problem) {
      setError(problem);
    }
  }
  return (
    <>
      <h1>Đơn chờ gửi</h1>
      <p>
        Đơn nháp được lưu trên thiết bị theo tài khoản. Giữ lại trình duyệt này cho đến khi gửi
        thành công.
      </p>
      {error !== null && <ErrorState error={error} retry={reload} />}
      {drafts.length === 0 ? (
        <div className="live-empty">Chưa có đơn nháp.</div>
      ) : (
        drafts.map((draft) => (
          <article className="live-panel" key={draft.id}>
            <h2>{draftState[draft.state]}</h2>
            <p>
              {draft.input.lines.length} dòng hàng · {draft.message}
            </p>
            {draft.orderId && <p>ID đơn: {draft.orderId}</p>}
            {draft.state === 'price-change' && (
              <>
                <p>
                  Giá trước: {display(draft.expectedTotal, 'totalVnd')} · Giá máy chủ:{' '}
                  {display(draft.quotedTotal, 'totalVnd')}
                </p>
                <button onClick={() => retry(draft, true)}>Chấp nhận giá và gửi</button>
              </>
            )}
            {['error', 'pending'].includes(draft.state) && (
              <button onClick={() => retry(draft)}>Thử gửi lại</button>
            )}
          </article>
        ))
      )}
    </>
  );
}
