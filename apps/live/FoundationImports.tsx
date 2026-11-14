import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiDownload, apiRequest, ApiError } from '../../packages/api/client';
import type { Page } from '../../packages/api/services';
import { DataView, display, ErrorState, Loading, Pagination } from './components';

type ImportType = { kind: string; label: string; sheets: string[]; demoTenant: boolean };
type Batch = {
  id: string;
  kind: string;
  fileName: string;
  status: string;
  rowCount: number;
  errorCount: number;
  warningCount: number;
  createdCount: number;
  updatedCount: number;
  unchangedCount: number;
  createdAt: string;
  committedAt?: string;
  reason: string;
};
type ImportRow = {
  sheet: string;
  line: number;
  data: Record<string, string>;
  errors: string[];
  warnings: string[];
  operation: string;
};
const operations: Record<string, string> = {
  CREATE: 'Tạo mới',
  UPDATE: 'Cập nhật',
  UNCHANGED: 'Không thay đổi',
};

export default function FoundationImports() {
  const client = useQueryClient();
  const types = useQuery({
    queryKey: ['import-types'],
    queryFn: ({ signal }) => apiRequest<ImportType[]>('/foundation-imports/types', { signal }),
  });
  const [kind, setKind] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [reason, setReason] = useState('');
  const [batch, setBatch] = useState<Batch | null>(null);
  const [page, setPage] = useState(0);
  const [historyPage, setHistoryPage] = useState(0);
  const [errorsOnly, setErrorsOnly] = useState(false);
  const [warningsRead, setWarningsRead] = useState(false);
  const [localError, setLocalError] = useState<unknown>(null);
  const [downloading, setDownloading] = useState(false);
  const downloadController = useRef<AbortController | null>(null);
  useEffect(() => () => downloadController.current?.abort(), []);
  const active = kind || types.data?.[0]?.kind || '';
  const selectedType = types.data?.find((type) => type.kind === active);
  const history = useQuery({
    queryKey: ['import-history', historyPage],
    queryFn: ({ signal }) =>
      apiRequest<Page<Batch>>(`/foundation-imports/batches?page=${historyPage}&size=10`, {
        signal,
      }),
  });
  const rows = useQuery({
    queryKey: ['foundation-import-rows', batch?.id, page, errorsOnly],
    enabled: !!batch,
    queryFn: ({ signal }) =>
      apiRequest<Page<ImportRow>>(
        `/foundation-imports/batches/${batch!.id}/rows?page=${page}&size=25&errorsOnly=${errorsOnly}`,
        { signal },
      ),
  });
  const preview = useMutation({
    mutationFn: async () => {
      if (!file) throw new Error('Cần chọn tệp dữ liệu.');
      if (file.size > 5 * 1024 * 1024) throw new Error('Tệp không được vượt quá 5 MB.');
      const body = new FormData();
      body.set('file', file);
      body.set('reason', reason.trim());
      return apiRequest<Batch>(`/foundation-imports/${active}/preview`, { method: 'POST', body });
    },
    onSuccess: async (result) => {
      setBatch(result);
      submitCredit.reset();
      setPage(0);
      setErrorsOnly(false);
      setWarningsRead(false);
      await client.invalidateQueries({ queryKey: ['import-history'] });
    },
  });
  const commit = useMutation({
    mutationFn: () =>
      apiRequest<Batch>(`/foundation-imports/batches/${batch!.id}/commit`, { method: 'POST' }),
    onSuccess: async (result) => {
      setBatch(result);
      await Promise.all([
        client.invalidateQueries({ queryKey: ['records'] }),
        client.invalidateQueries({ queryKey: ['import-history'] }),
        client.invalidateQueries({ queryKey: ['foundation-import-rows', result.id] }),
        client.invalidateQueries({ queryKey: ['lookup'] }),
      ]);
    },
  });
  const submitCredit = useMutation({
    mutationFn: () =>
      apiRequest<{ submittedCount: number }>(
        `/foundation-imports/batches/${batch!.id}/submit-credit`,
        { method: 'POST' },
      ),
    onSuccess: () => client.invalidateQueries({ queryKey: ['records'] }),
  });
  async function download() {
    setLocalError(null);
    setDownloading(true);
    downloadController.current?.abort();
    downloadController.current = new AbortController();
    try {
      const blob = await apiDownload(
        `/foundation-imports/${active}/template`,
        downloadController.current.signal,
      );
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = `synera-${active.toLowerCase()}.xlsx`;
      anchor.click();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (error) {
      if (!(error instanceof Error && error.name === 'AbortError')) setLocalError(error);
    } finally {
      setDownloading(false);
    }
  }
  function upload(event: FormEvent) {
    event.preventDefault();
    setLocalError(null);
    commit.reset();
    preview.mutate();
  }
  function selectBatch(value: Batch) {
    setBatch(value);
    setKind(value.kind);
    setPage(0);
    setErrorsOnly(false);
    setWarningsRead(false);
    commit.reset();
    submitCredit.reset();
    preview.reset();
  }
  if (types.isPending) return <Loading />;
  if (types.isError) return <ErrorState error={types.error} retry={() => void types.refetch()} />;
  if (!types.data.length) return <p>Vai hiện tại không được nhập dữ liệu nền.</p>;
  const busy = preview.isPending || commit.isPending;
  return (
    <>
      <h1>Nhập dữ liệu nền</h1>
      <p>
        {selectedType?.demoTenant
          ? 'Đang làm việc trong tenant demo.'
          : 'Đang làm việc trong tenant thật. Workbook có cờ demo sẽ bị từ chối.'}
      </p>
      <p className="live-muted">1. Tải lên → 2. Xem trước → 3. Xác nhận ghi</p>
      <section className="live-panel">
        <h2>1. Chọn dữ liệu và tải lên</h2>
        <form className="live-form" onSubmit={upload}>
          <label>
            Loại dữ liệu
            <select
              value={active}
              disabled={busy}
              onChange={(event) => {
                setKind(event.target.value);
                setBatch(null);
                preview.reset();
                commit.reset();
                setFile(null);
              }}
            >
              {types.data.map((type) => (
                <option key={type.kind} value={type.kind}>
                  {type.label}
                </option>
              ))}
            </select>
          </label>
          <p>
            Sheet: {selectedType?.sheets.join(', ')}. Mẫu có dòng ví dụ cần thay bằng dữ liệu thật.
          </p>
          <button type="button" disabled={downloading || busy} onClick={() => void download()}>
            {downloading ? 'Đang tải mẫu…' : 'Tải file mẫu .xlsx'}
          </button>
          <label>
            Tệp Excel hoặc CSV UTF-8
            <input
              key={active}
              type="file"
              accept=".xlsx,.csv"
              required
              disabled={busy}
              onChange={(event) => {
                setFile(event.target.files?.[0] || null);
                setBatch(null);
                preview.reset();
                commit.reset();
              }}
            />
          </label>
          <label>
            Lý do nhập dữ liệu
            <input
              required
              maxLength={500}
              value={reason}
              disabled={busy}
              onChange={(event) => setReason(event.target.value)}
            />
          </label>
          <button type="submit" disabled={!file || busy || !reason.trim()}>
            {preview.isPending ? 'Đang kiểm tra…' : 'Tải lên và xem trước'}
          </button>
        </form>
        {localError !== null && <ErrorState error={localError} />}
        {preview.isError && <ErrorState error={preview.error} />}
      </section>
      {batch && (
        <section className="live-panel">
          <h2>{batch.status === 'COMMITTED' ? '3. Báo cáo sau import' : '2. Xem trước dữ liệu'}</h2>
          <p>
            {batch.fileName} · {display(batch.createdAt)}
          </p>
          <div className="live-table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Dòng nhận</th>
                  <th>Dòng lỗi</th>
                  <th>Dòng cảnh báo</th>
                  <th>Tạo mới</th>
                  <th>Cập nhật</th>
                  <th>Không thay đổi</th>
                </tr>
              </thead>
              <tbody>
                <tr>
                  <td>{batch.rowCount}</td>
                  <td>{batch.errorCount}</td>
                  <td>{batch.warningCount}</td>
                  <td>{batch.createdCount}</td>
                  <td>{batch.updatedCount}</td>
                  <td>{batch.unchangedCount}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <p className="live-muted">
            Số tạo/cập nhật tính theo bản ghi chính của từng dòng nguồn; các đơn vị quy đổi và liên
            kết được ghi trong cùng giao dịch.
          </p>
          <label className="live-check">
            <input
              type="checkbox"
              checked={errorsOnly}
              onChange={(event) => {
                setErrorsOnly(event.target.checked);
                setPage(0);
              }}
            />
            Chỉ xem dòng lỗi
          </label>
          {rows.isPending ? (
            <Loading />
          ) : rows.isError ? (
            <ErrorState error={rows.error} retry={() => void rows.refetch()} />
          ) : (
            rows.data && (
              <>
                {rows.data.items.length === 0 ? (
                  <p>Không có dòng phù hợp.</p>
                ) : (
                  <div className="live-table-wrap">
                    <table>
                      <thead>
                        <tr>
                          <th>Sheet / dòng</th>
                          <th>Dữ liệu chuẩn hóa</th>
                          <th>Kết quả</th>
                        </tr>
                      </thead>
                      <tbody>
                        {rows.data.items.map((row) => (
                          <tr key={`${row.sheet}:${row.line}`}>
                            <td>
                              {row.sheet} · {row.line}
                            </td>
                            <td>
                              <details>
                                <summary>Xem dữ liệu</summary>
                                <DataView data={row.data} />
                              </details>
                            </td>
                            <td>
                              <strong>{operations[row.operation] || row.operation}</strong>
                              {row.errors.map((error, index) => (
                                <p className="form-error" key={`e${index}`}>
                                  {error}
                                </p>
                              ))}
                              {row.warnings.map((warning, index) => (
                                <p key={`w${index}`}>{warning}</p>
                              ))}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
                <Pagination page={page} totalPages={rows.data.totalPages} setPage={setPage} />
              </>
            )
          )}
          {batch.status !== 'COMMITTED' && (
            <>
              {batch.errorCount > 0 ? (
                <p role="alert">
                  Có dòng lỗi nên chưa thể ghi. Hãy sửa tệp và tải lên lại; dữ liệu nghiệp vụ chưa
                  bị thay đổi.
                </p>
              ) : (
                <>
                  <p>
                    Toàn bộ lô sẽ ghi trong một giao dịch. Nếu phát sinh lỗi, cả lô được hoàn tác.
                  </p>
                  {batch.warningCount > 0 && (
                    <label className="live-check">
                      <input
                        type="checkbox"
                        checked={warningsRead}
                        onChange={(event) => setWarningsRead(event.target.checked)}
                      />
                      Tôi đã xem các cảnh báo trùng/đối chiếu trong lô này.
                    </label>
                  )}
                  <button
                    disabled={busy || (batch.warningCount > 0 && !warningsRead)}
                    onClick={() => commit.mutate()}
                  >
                    {commit.isPending ? 'Đang ghi…' : '3. Xác nhận ghi toàn bộ lô'}
                  </button>
                </>
              )}
            </>
          )}
          {batch.status === 'COMMITTED' && (
            <p role="status">
              Đã ghi thành công lúc {display(batch.committedAt)}. Nhập lại cùng dữ liệu không tạo
              bản ghi trùng.
            </p>
          )}
          {batch.status === 'COMMITTED' && batch.kind === 'CREDIT' && (
            <div className="live-panel">
              <p>
                Hạn mức đang nháp chưa có hiệu lực. Gửi chờ duyệt để người có quyền duyệt khác người
                tạo xem xét.
              </p>
              <button
                disabled={submitCredit.isPending || submitCredit.isSuccess}
                onClick={() => submitCredit.mutate()}
              >
                {submitCredit.isPending ? 'Đang gửi…' : 'Gửi hạn mức nhập này chờ duyệt'}
              </button>
              {submitCredit.isSuccess && (
                <p role="status">Đã gửi {submitCredit.data.submittedCount} hạn mức chờ duyệt.</p>
              )}
              {submitCredit.isError && <ErrorState error={submitCredit.error} />}
            </div>
          )}
          {commit.isError && (
            <>
              <ErrorState error={commit.error} />
              {commit.error instanceof ApiError && commit.error.details !== undefined && (
                <DataView data={commit.error.details} />
              )}
            </>
          )}
        </section>
      )}
      <section className="live-panel">
        <h2>Lịch sử và báo cáo của tôi</h2>
        {history.isPending ? (
          <Loading />
        ) : history.isError ? (
          <ErrorState error={history.error} retry={() => void history.refetch()} />
        ) : (
          <>
            {history.data.items.length === 0 ? (
              <p>Chưa có lô nhập.</p>
            ) : (
              <div className="live-table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>Tệp</th>
                      <th>Trạng thái</th>
                      <th>Thời gian</th>
                      <th />
                    </tr>
                  </thead>
                  <tbody>
                    {history.data.items.map((item) => (
                      <tr key={item.id}>
                        <td>{item.fileName}</td>
                        <td>{item.status === 'COMMITTED' ? 'Đã ghi' : 'Đang xem trước'}</td>
                        <td>{display(item.createdAt)}</td>
                        <td>
                          <button disabled={busy} onClick={() => selectBatch(item)}>
                            Mở báo cáo
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            <Pagination
              page={historyPage}
              totalPages={history.data.totalPages}
              setPage={setHistoryPage}
            />
          </>
        )}
      </section>
    </>
  );
}
