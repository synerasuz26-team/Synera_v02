import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiRequest, can, type CurrentUser } from '../../packages/api/client';
import { getRecord, listRecords, writeRecord, type RecordRow } from '../../packages/api/services';
import {
  DataView,
  Dialog,
  display,
  ErrorState,
  FieldsForm,
  Loading,
  Pagination,
  SearchForm,
} from './components';
import { labels, reason, type Resource } from './resources';
import { OrderDetail } from './OrderDetail';
import { OrderForm } from './OrderForm';
import AttachmentPanel from './AttachmentPanel';

function RecordDetail({ path }: { path: string }) {
  const detail = useQuery({
    queryKey: ['detail', path],
    queryFn: ({ signal }) => getRecord(path, signal),
  });
  if (detail.isPending) return <Loading />;
  if (detail.isError)
    return <ErrorState error={detail.error} retry={() => void detail.refetch()} />;
  return <DataView data={detail.data} />;
}
export function ResourceScreen({
  resource,
  me,
  mobile,
}: {
  resource: Resource;
  me: CurrentUser;
  mobile: boolean;
}) {
  const client = useQueryClient();
  const [page, setPage] = useState(0);
  const [filter, setFilter] = useState('');
  const [selected, setSelected] = useState<RecordRow | null>(null);
  const [creating, setCreating] = useState(false);
  const [gpsReason, setGpsReason] = useState('');
  const path = resource.path;
  const orderKind =
    path === '/orders/sell-in' ? 'sell-in' : path === '/orders/sell-out' ? 'sell-out' : null;
  const createPermission = orderKind
    ? orderKind === 'sell-in'
      ? 'order_si:create'
      : 'order_so:create'
    : resource.create?.permission;
  const list = useQuery({
    queryKey: ['records', me.tenant.id, me.user.id, path, page, filter],
    queryFn: ({ signal }) =>
      listRecords(
        path,
        { page, size: 25, [resource.search || 'status']: filter || undefined },
        signal,
      ),
  });
  const create = useMutation({
    mutationFn: (body: Record<string, unknown>) => writeRecord(path, body),
    onSuccess: async () => {
      setCreating(false);
      await client.invalidateQueries({ queryKey: ['records'] });
    },
  });
  const gps = useMutation({
    mutationFn: async ({ row, action }: { row: RecordRow; action: string }) => {
      const position = await new Promise<GeolocationPosition>((resolve, reject) =>
        navigator.geolocation.getCurrentPosition(resolve, reject, {
          enableHighAccuracy: true,
          timeout: 15000,
          maximumAge: 0,
        }),
      );
      return writeRecord(`/visits/${row.id}/${action}`, {
        latitude: position.coords.latitude,
        longitude: position.coords.longitude,
        reason: gpsReason,
      });
    },
    onSuccess: () => client.invalidateQueries({ queryKey: ['records'] }),
  });
  return (
    <>
      <div className="live-heading">
        <div>
          <span className="ops-eyebrow">{me.tenant.name}</span>
          <h1>{resource.label}</h1>
        </div>
        <div className="live-actions">
          <button onClick={() => void list.refetch()} disabled={list.isFetching}>
            Làm mới
          </button>
          {createPermission && can(me, createPermission) && (
            <button
              onClick={() => {
                setCreating(true);
                create.reset();
              }}
            >
              Tạo mới
            </button>
          )}
        </div>
      </div>
      {(resource.search || resource.status) && (
        <SearchForm
          label={
            resource.search === 'sku'
              ? 'Lọc SKU'
              : resource.search
                ? 'Tìm theo mã hoặc tên'
                : 'Lọc mã trạng thái'
          }
          onSearch={(value) => {
            setFilter(value);
            setPage(0);
          }}
        />
      )}
      {list.isPending ? (
        <Loading />
      ) : list.isError ? (
        <ErrorState error={list.error} retry={() => void list.refetch()} />
      ) : (
        <>
          <p className="live-muted">
            {list.data.totalElements} bản ghi ·{' '}
            {list.isFetching ? 'Đang cập nhật…' : 'Dữ liệu máy chủ'}
          </p>
          {list.data.items.length === 0 ? (
            <div className="live-empty">Chưa có dữ liệu phù hợp.</div>
          ) : (
            <div className="live-table-wrap">
              <table>
                <thead>
                  <tr>
                    {resource.columns.map((column) => (
                      <th key={column}>{labels[column] || column}</th>
                    ))}
                    <th>Thao tác</th>
                  </tr>
                </thead>
                <tbody>
                  {list.data.items.map((row, index) => (
                    <tr key={row.id || index}>
                      {resource.columns.map((column) => (
                        <td key={column}>{display(row[column], column)}</td>
                      ))}
                      <td>
                        <button onClick={() => setSelected(row)}>Chi tiết</button>
                        {path === '/visits' && can(me, 'visit:checkin') && (
                          <>
                            <button
                              disabled={gps.isPending}
                              onClick={() => gps.mutate({ row, action: 'check-in' })}
                            >
                              Check-in GPS
                            </button>
                            <button
                              disabled={gps.isPending}
                              onClick={() => gps.mutate({ row, action: 'check-out' })}
                            >
                              Check-out GPS
                            </button>
                          </>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <Pagination page={page} totalPages={list.data.totalPages} setPage={setPage} />
        </>
      )}
      {path === '/visits' && can(me, 'visit:checkin') && (
        <label>
          Lý do ngoại lệ GPS (nếu cần)
          <input
            value={gpsReason}
            onChange={(event) => setGpsReason(event.target.value)}
            maxLength={500}
          />
        </label>
      )}
      {gps.isError && <ErrorState error={gps.error} />}{' '}
      {gps.isSuccess && <DataView data={gps.data} />}
      {selected && (
        <Dialog title={resource.label} close={() => setSelected(null)}>
          {orderKind ? (
            <OrderDetail key={selected.id} path={`${path}/${selected.id}`} me={me} />
          ) : (
            <>
              {resource.detail ? (
                <RecordDetail path={`${path}/${selected.id}`} />
              ) : (
                <DataView data={selected} />
              )}
              <RecordActions
                resource={resource}
                row={list.data?.items.find((item) => item.id === selected.id) || selected}
                me={me}
              />
              {path === '/routes' && <RouteDetails id={selected.id} />}
              {path === '/tasks' && (
                <TaskActions
                  row={list.data?.items.find((item) => item.id === selected.id) || selected}
                  me={me}
                />
              )}
            </>
          )}
          {['/visits', '/tickets', '/orders/sell-out'].includes(path) && (
            <AttachmentPanel
              key={`files:${selected.id}`}
              ownerType={path === '/visits' ? 'VISIT' : path === '/tickets' ? 'TICKET' : 'ORDER_SO'}
              ownerId={selected.id}
              me={me}
              canUpload={
                can(
                  me,
                  path === '/visits'
                    ? 'visit:checkin'
                    : path === '/tickets'
                      ? 'ticket:process'
                      : 'order_so:receive_confirm',
                ) ||
                (path === '/tickets' && can(me, 'ticket:create'))
              }
            />
          )}
        </Dialog>
      )}
      {creating && (
        <Dialog title={`Tạo ${resource.label.toLowerCase()}`} close={() => setCreating(false)}>
          {orderKind ? (
            <OrderForm kind={orderKind} me={me} mobile={mobile} done={() => setCreating(false)} />
          ) : (
            resource.create && (
              <>
                <FieldsForm
                  fields={resource.create.fields}
                  groups={resource.create.groups}
                  onSubmit={(body) => create.mutate(body)}
                  busy={create.isPending}
                />
                {create.isError && <ErrorState error={create.error} />}
              </>
            )
          )}
        </Dialog>
      )}
      {((path === '/inventory' && can(me, 'inventory:update')) ||
        (path === '/sellout-records' && can(me, 'sellout_data:submit'))) && (
        <ImportPanel kind={path === '/inventory' ? 'inventory' : 'sellout'} />
      )}
    </>
  );
}
function TaskActions({ row, me }: { row: RecordRow; me: CurrentUser }) {
  const client = useQueryClient();
  const [assignee, setAssignee] = useState('');
  const action = useMutation({
    mutationFn: (assign: boolean) =>
      apiRequest(`/tasks/${row.id}/${assign ? 'assign' : 'complete'}`, {
        method: 'POST',
        body: JSON.stringify({ version: row.version, ...(assign ? { assigneeId: assignee } : {}) }),
      }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['records'] }),
  });
  return (
    <section className="live-panel">
      {can(me, 'task:assign') && (
        <label>
          ID người nhận
          <input value={assignee} onChange={(event) => setAssignee(event.target.value)} />
          <button disabled={!assignee || action.isPending} onClick={() => action.mutate(true)}>
            Giao việc
          </button>
        </label>
      )}
      {row.assigneeId === me.user.id && ['OPEN', 'IN_PROGRESS'].includes(String(row.status)) && (
        <button disabled={action.isPending} onClick={() => action.mutate(false)}>
          Hoàn tất công việc
        </button>
      )}
      {action.isError && <ErrorState error={action.error} />}
      {action.isSuccess && <p role="status">Đã cập nhật công việc.</p>}
    </section>
  );
}

function RecordActions({
  resource,
  row,
  me,
}: {
  resource: Resource;
  row: RecordRow;
  me: CurrentUser;
}) {
  const client = useQueryClient();
  const [action, setAction] = useState('');
  let permission = '',
    actions: { suffix: string; label: string; permission?: string }[] = [];
  if (['/price-lists', '/promotions', '/vouchers'].includes(resource.path)) {
    const prefix = resource.path === '/price-lists' ? 'price_list' : 'promotion';
    actions = [
      { suffix: 'submit', label: 'Gửi duyệt', permission: `${prefix}:manage` },
      { suffix: 'approve', label: 'Duyệt', permission: `${prefix}:approve` },
      { suffix: 'reject', label: 'Từ chối', permission: `${prefix}:approve` },
      { suffix: 'activate', label: 'Kích hoạt', permission: `${prefix}:approve` },
    ];
  } else if (resource.path === '/payments') {
    permission = 'payment:record';
    actions = [{ suffix: 'record', label: 'Ghi nhận thanh toán' }];
  } else if (resource.path === '/reconciliations') {
    permission = 'reconciliation:confirm';
    actions = [{ suffix: 'confirm', label: 'Xác nhận đối soát' }];
  } else if (resource.path === '/periods') {
    permission = 'period:close';
    actions = [{ suffix: 'close', label: 'Chốt kỳ' }];
  } else if (resource.path === '/credit-limits')
    actions = [
      { suffix: 'submit', label: 'Gửi duyệt', permission: 'credit_limit:manage' },
      { suffix: 'decision', label: 'Quyết định hạn mức', permission: 'credit_limit:approve' },
    ];
  const mutation = useMutation({
    mutationFn: (body: Record<string, unknown>) =>
      writeRecord(`${resource.path}/${row.id}/${action}`, { ...body, version: row.version }),
    onSuccess: async () => {
      setAction('');
      await client.invalidateQueries({ queryKey: ['records'] });
      await client.invalidateQueries({ queryKey: ['detail'] });
    },
  });
  const allowed = actions.filter((item) => can(me, item.permission || permission));
  if (!allowed.length) return null;
  return (
    <section>
      <div className="live-actions">
        {allowed.map((item) => (
          <button
            key={item.suffix}
            onClick={() => {
              setAction(item.suffix);
              mutation.reset();
            }}
          >
            {item.label}
          </button>
        ))}
      </div>
      {action && (
        <FieldsForm
          key={action}
          fields={[
            reason,
            ...(action === 'activate'
              ? [
                  { key: 'effectiveFrom', label: 'Hiệu lực từ', type: 'datetime-local' as const },
                  { key: 'effectiveTo', label: 'Hiệu lực đến', type: 'datetime-local' as const },
                ]
              : []),
            ...(action === 'decision'
              ? [
                  {
                    key: 'approved',
                    label: 'Chấp thuận',
                    type: 'checkbox' as const,
                    optional: true,
                  },
                ]
              : []),
          ]}
          busy={mutation.isPending}
          onSubmit={(body) => mutation.mutate(body)}
          submitLabel={allowed.find((item) => item.suffix === action)?.label}
        />
      )}
      {mutation.isError && <ErrorState error={mutation.error} />}
    </section>
  );
}
function RouteDetails({ id }: { id: string }) {
  const [mode, setMode] = useState('outlets');
  const detail = useQuery({
    queryKey: ['route-detail', id, mode],
    queryFn: ({ signal }) => apiRequest(`/routes/${id}/${mode}`, { signal }),
  });
  return (
    <section>
      <div className="live-actions">
        <button onClick={() => setMode('outlets')}>Điểm bán trên tuyến</button>
        <button onClick={() => setMode('mcp')}>Lịch MCP</button>
      </div>
      {detail.isPending ? (
        <Loading />
      ) : detail.isError ? (
        <ErrorState error={detail.error} retry={() => void detail.refetch()} />
      ) : (
        <DataView data={detail.data} />
      )}
    </section>
  );
}
function ImportPanel({ kind }: { kind: 'inventory' | 'sellout' }) {
  const client = useQueryClient();
  const [batch, setBatch] = useState<RecordRow | null>(null);
  const [page, setPage] = useState(0);
  const [file, setFile] = useState<File | null>(null);
  const [npp, setNpp] = useState('');
  const rows = useQuery({
    queryKey: ['import-rows', batch?.id, page],
    enabled: !!batch,
    queryFn: ({ signal }) => listRecords(`/imports/${batch!.id}/rows`, { page, size: 25 }, signal),
  });
  const preview = useMutation({
    mutationFn: async () => {
      const body = new FormData();
      body.set('file', file!);
      return apiRequest<RecordRow>(
        `/imports/${kind}/preview${npp ? `?nppId=${encodeURIComponent(npp)}` : ''}`,
        { method: 'POST', body },
      );
    },
    onSuccess: (result) => {
      setBatch(result);
      setPage(0);
    },
  });
  const commit = useMutation({
    mutationFn: () => apiRequest<RecordRow>(`/imports/${batch!.id}/commit`, { method: 'POST' }),
    onSuccess: async (result) => {
      setBatch(result);
      await client.invalidateQueries({ queryKey: ['records'] });
    },
  });
  return (
    <section className="live-panel">
      <h2>Nhập dữ liệu từ tệp</h2>
      <div className="live-form">
        <label>
          ID NPP (tùy chọn)
          <input
            value={npp}
            onChange={(event) => {
              setNpp(event.target.value);
              setBatch(null);
            }}
          />
        </label>
        <label>
          Tệp CSV / Excel
          <input
            type="file"
            accept=".csv,.xlsx"
            onChange={(event) => {
              setFile(event.target.files?.[0] || null);
              setBatch(null);
              preview.reset();
              commit.reset();
            }}
          />
        </label>
        <button
          disabled={!file || preview.isPending || commit.isPending}
          onClick={() => preview.mutate()}
        >
          Xem trước
        </button>
      </div>
      {preview.isError && <ErrorState error={preview.error} />}
      {batch && (
        <>
          <DataView data={batch} />
          {rows.isPending ? (
            <Loading />
          ) : rows.isError ? (
            <ErrorState error={rows.error} retry={() => void rows.refetch()} />
          ) : (
            <>
              <DataView data={rows.data.items} />
              <Pagination page={page} totalPages={rows.data.totalPages} setPage={setPage} />
            </>
          )}
          <button
            disabled={
              commit.isPending || batch.status === 'COMMITTED' || Number(batch.invalidCount) > 0
            }
            onClick={() => commit.mutate()}
          >
            Xác nhận nhập dữ liệu
          </button>
        </>
      )}
      {commit.isError && <ErrorState error={commit.error} />}
    </section>
  );
}
