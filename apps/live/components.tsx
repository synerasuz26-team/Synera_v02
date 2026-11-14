import { useState, type FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { listRecords, type RecordRow } from '../../packages/api/services';
import { labels, type Field, type FieldGroup } from './resources';

const currency = new Intl.NumberFormat('vi-VN', {
  style: 'currency',
  currency: 'VND',
  maximumFractionDigits: 0,
});
export function display(value: unknown, key = ''): string {
  if (value === null || value === undefined || value === '') return '—';
  if (typeof value === 'boolean') return value ? 'Có' : 'Không';
  if (typeof value === 'number')
    return key.endsWith('Vnd')
      ? currency.format(value)
      : new Intl.NumberFormat('vi-VN').format(value);
  if (typeof value === 'string' && /^\d{4}-\d\d-\d\d[T ]/.test(value)) {
    const date = new Date(value);
    if (!Number.isNaN(date.getTime()))
      return new Intl.DateTimeFormat('vi-VN', {
        dateStyle: 'short',
        timeStyle: 'short',
        timeZone: 'Asia/Ho_Chi_Minh',
      }).format(date);
  }
  return String(value);
}
export function ErrorState({ error, retry }: { error: unknown; retry?: () => void }) {
  return (
    <div className="live-error" role="alert">
      <p>{error instanceof Error ? error.message : 'Không thể tải dữ liệu.'}</p>
      {retry && (
        <button type="button" onClick={retry}>
          Thử lại
        </button>
      )}
    </div>
  );
}
export function Loading() {
  return (
    <div className="live-loading" role="status" aria-label="Đang tải dữ liệu">
      <span />
      <span />
      <span />
    </div>
  );
}
export function DataView({ data, depth = 0 }: { data: unknown; depth?: number }) {
  if (data === null || data === undefined) return <span>—</span>;
  if (depth > 6) return <span>…</span>;
  if (Array.isArray(data))
    return data.length ? (
      <div className="live-records">
        {data.map((row, index) => (
          <DataView key={index} data={row} depth={depth + 1} />
        ))}
      </div>
    ) : (
      <p>Chưa có dữ liệu.</p>
    );
  if (typeof data !== 'object') return <span>{display(data)}</span>;
  return (
    <dl className="live-details">
      {Object.entries(data)
        .filter(([, value]) => value !== null)
        .map(([key, value]) => (
          <div key={key}>
            <dt>{labels[key] || key}</dt>
            <dd>
              {typeof value === 'object' ? (
                <DataView data={value} depth={depth + 1} />
              ) : (
                display(value, key)
              )}
            </dd>
          </div>
        ))}
    </dl>
  );
}
export function FieldsForm({
  fields,
  groups = [],
  onSubmit,
  busy,
  initial = {},
  submitLabel = 'Lưu',
}: {
  fields: Field[];
  onSubmit: (body: Record<string, unknown>) => void;
  groups?: FieldGroup[];
  busy?: boolean;
  initial?: Record<string, unknown>;
  submitLabel?: string;
}) {
  const [counts, setCounts] = useState<Record<string, number>>({});
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const result: Record<string, unknown> = {};
    function collect(items: Field[], prefix = '') {
      const row: Record<string, unknown> = {};
      items.forEach((field) => {
        const value = form.get(`${prefix}${field.key}`);
        if (field.type === 'checkbox') row[field.key] = value === 'on';
        else if (value !== '' && value !== null)
          row[field.key] =
            field.type === 'number'
              ? Number(value)
              : field.type === 'datetime-local'
                ? new Date(String(value)).toISOString()
                : String(value).trim();
      });
      return row;
    }
    Object.assign(result, collect(fields));
    groups.forEach((group) => {
      result[group.key] = Array.from({ length: counts[group.key] ?? 1 }, (_, index) =>
        collect(group.fields, `${group.key}:${index}:`),
      );
    });
    onSubmit(result);
  }
  function renderField(field: Field, prefix = '') {
    const key = `${prefix}${field.key}`;
    return (
      <label key={key}>
        {field.label}
        {field.optional ? ' (tùy chọn)' : ''}
        {field.options ? (
          <select
            name={key}
            required={!field.optional}
            defaultValue={String(initial[field.key] ?? '')}
          >
            <option value="">Chọn</option>
            {field.options.map((value) => (
              <option key={value} value={value}>
                {value}
              </option>
            ))}
          </select>
        ) : (
          <input
            name={key}
            type={field.type || 'text'}
            required={!field.optional && field.type !== 'checkbox'}
            defaultChecked={field.type === 'checkbox' ? Boolean(initial[field.key]) : undefined}
            defaultValue={field.type !== 'checkbox' ? String(initial[field.key] ?? '') : undefined}
            min={field.type === 'number' ? (field.min ?? 0) : undefined}
            step={field.type === 'number' ? 1 : undefined}
            maxLength={field.type ? undefined : 500}
          />
        )}
      </label>
    );
  }
  return (
    <form className="live-form" onSubmit={submit}>
      {fields.map((field) => renderField(field))}
      {groups.map((group) => (
        <fieldset key={group.key}>
          <legend>{group.label}</legend>
          {Array.from({ length: counts[group.key] ?? 1 }, (_, index) => (
            <div className="live-form live-panel" key={index}>
              {group.fields.map((field) => renderField(field, `${group.key}:${index}:`))}
            </div>
          ))}
          <div className="live-actions">
            <button
              type="button"
              onClick={() => setCounts({ ...counts, [group.key]: (counts[group.key] ?? 1) + 1 })}
            >
              Thêm dòng
            </button>
            <button
              type="button"
              disabled={(counts[group.key] ?? 1) <= 1}
              onClick={() => setCounts({ ...counts, [group.key]: (counts[group.key] ?? 1) - 1 })}
            >
              Bỏ dòng cuối
            </button>
          </div>
        </fieldset>
      ))}
      <button type="submit" disabled={busy}>
        {busy ? 'Đang lưu…' : submitLabel}
      </button>
    </form>
  );
}
export function Dialog({
  title,
  close,
  children,
}: {
  title: string;
  close: () => void;
  children: React.ReactNode;
}) {
  return (
    <div className="live-overlay" onClick={close}>
      <section
        className="live-dialog"
        role="dialog"
        aria-modal="true"
        aria-label={title}
        onClick={(event) => event.stopPropagation()}
        onKeyDown={(event) => {
          if (event.key === 'Escape') close();
        }}
      >
        <header>
          <h2>{title}</h2>
          <button aria-label="Đóng" onClick={close}>
            ×
          </button>
        </header>
        {children}
      </section>
    </div>
  );
}
export function Pagination({
  page,
  totalPages,
  setPage,
}: {
  page: number;
  totalPages: number;
  setPage: (page: number) => void;
}) {
  return (
    <div className="live-pagination">
      <button type="button" disabled={page === 0} onClick={() => setPage(page - 1)}>
        Trước
      </button>
      <span>
        Trang {page + 1} / {Math.max(1, totalPages)}
      </span>
      <button type="button" disabled={page + 1 >= totalPages} onClick={() => setPage(page + 1)}>
        Sau
      </button>
    </div>
  );
}
export function SearchForm({
  label,
  onSearch,
}: {
  label: string;
  onSearch: (value: string) => void;
}) {
  const [value, setValue] = useState('');
  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        onSearch(value.trim());
      }}
      className="live-search"
    >
      <label>
        {label}
        <input value={value} onChange={(event) => setValue(event.target.value)} />
      </label>
      <button>Tìm kiếm</button>
    </form>
  );
}
export function Lookup({
  path,
  label,
  name,
  value,
  onChange,
}: {
  path: string;
  label: string;
  name?: string;
  value: string;
  onChange: (id: string, row?: RecordRow) => void;
}) {
  const [page, setPage] = useState(0);
  const [query, setQuery] = useState('');
  const [selected, setSelected] = useState<RecordRow | null>(null);
  const list = useQuery({
    queryKey: ['lookup', path, page, query],
    queryFn: ({ signal }) => listRecords(path, { page, size: 25, q: query }, signal),
  });
  return (
    <div>
      <label>
        {label}
        <select
          name={name}
          required
          value={value}
          onChange={(event) => {
            const row = list.data?.items.find((item) => item.id === event.target.value);
            setSelected(row || null);
            onChange(event.target.value, row);
          }}
        >
          <option value="">Chọn {label.toLowerCase()}</option>
          {value && !list.data?.items.some((row) => row.id === value) && (
            <option value={value}>
              {selected ? `${selected.code || selected.sku || ''} ${selected.name || ''}` : value}
            </option>
          )}
          {list.data?.items.map((row) => (
            <option key={row.id} value={row.id}>
              {String(row.code || row.sku || '')} · {String(row.name || row.id)}
            </option>
          ))}
        </select>
      </label>
      <input
        aria-label={`Tìm ${label.toLowerCase()}`}
        placeholder="Tìm mã hoặc tên"
        value={query}
        onChange={(event) => {
          setQuery(event.target.value);
          setPage(0);
        }}
      />
      {list.isPending ? (
        <Loading />
      ) : list.isError ? (
        <ErrorState error={list.error} retry={() => void list.refetch()} />
      ) : (
        <Pagination page={page} totalPages={list.data.totalPages} setPage={setPage} />
      )}
    </div>
  );
}
