import { useQuery } from '@tanstack/react-query';
import { apiRequest, type CurrentUser } from '../../packages/api/client';
import { ErrorState, Loading } from './components';

type TableUsage = {
  table: string;
  estimatedRows: number;
  dataBytes: number;
  indexBytes: number;
  totalBytes: number;
  volumePercent: number;
};
type StorageReport = {
  databaseBytes: number;
  volumeCapacityBytes: number;
  databaseBudgetBytes: number;
  volumePercent: number;
  budgetPercent: number;
  status: 'HEALTHY' | 'WARNING' | 'CRITICAL';
  volumeUsageMeasured: boolean;
  note: string;
  tables: TableUsage[];
};
function formatBytes(bytes: number) {
  return `${(bytes / 1_000_000).toFixed(2)} MB`;
}

export default function StoragePanel({ me }: { me: CurrentUser }) {
  const storage = useQuery({
    queryKey: ['admin-storage', me.tenant.id],
    queryFn: ({ signal }) => apiRequest<StorageReport>('/admin/storage', { signal }),
    refetchInterval: 60_000,
  });
  return (
    <section className="live-panel">
      <h1>Dung lượng database</h1>
      {storage.isPending ? (
        <Loading />
      ) : storage.isError ? (
        <ErrorState error={storage.error} retry={() => void storage.refetch()} />
      ) : (
        <>
          {storage.data.status !== 'HEALTHY' && (
            <div className="live-storage-warning" role="alert">
              <strong>
                {storage.data.status === 'CRITICAL'
                  ? 'Cảnh báo ước tính: database đã vượt ngưỡng 85% so với volume 500 MB.'
                  : 'Cảnh báo ước tính: database đã vượt ngưỡng 70% so với volume 500 MB.'}
              </strong>
            </div>
          )}
          <dl>
            <dt>Database</dt>
            <dd>{formatBytes(storage.data.databaseBytes)}</dd>
            <dt>Ngân sách database (300 MB)</dt>
            <dd>{storage.data.budgetPercent}%</dd>
            <dt>Database ước tính so với volume 500 MB</dt>
            <dd>{storage.data.volumePercent}%</dd>
          </dl>
          <p>{storage.data.note}</p>
          <div className="live-table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Bảng</th>
                  <th>Số dòng (ước tính)</th>
                  <th>Dữ liệu</th>
                  <th>Index</th>
                  <th>Tổng</th>
                  <th>% dung lượng 500 MB (ước tính)</th>
                </tr>
              </thead>
              <tbody>
                {storage.data.tables.map((table) => (
                  <tr key={table.table}>
                    <td>{table.table}</td>
                    <td>{table.estimatedRows.toLocaleString('vi-VN')}</td>
                    <td>{formatBytes(table.dataBytes)}</td>
                    <td>{formatBytes(table.indexBytes)}</td>
                    <td>{formatBytes(table.totalBytes)}</td>
                    <td>{table.volumePercent}%</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {storage.data.tables.length === 0 && <p>Chưa có bảng dữ liệu.</p>}
          </div>
        </>
      )}
    </section>
  );
}
