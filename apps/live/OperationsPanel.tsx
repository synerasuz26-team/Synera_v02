import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiRequest, type CurrentUser } from '../../packages/api/client';
import { DataView, ErrorState, Loading, Pagination } from './components';

type Settings = { value: Record<string, number | boolean>; version: number };
const names: Record<string, string> = {
  nightly_enabled: 'Bật job đêm',
  nightly_hour: 'Giờ chốt (giờ Việt Nam, 0–23)',
  nightly_minute: 'Phút chốt (0–59)',
  reminders_enabled: 'Bật nhắc việc',
  reminder_lead_minutes: 'Nhắc trước hạn công việc / lời hẹn (phút)',
  escalation_minutes: 'Leo thang sau lần nhắc (phút)',
  ticket_sla_minutes: 'SLA ticket mặc định (phút)',
  ticket_warning_minutes: 'Cảnh báo trước SLA ticket (phút)',
  npp_cycle_days: 'Chu kỳ đặt hàng NPP (ngày)',
  variance_warning_percent: 'Cảnh báo chênh lệch lớn hơn (%)',
  approval_threshold_vnd: 'Ngưỡng cảnh báo cần duyệt (VND)',
  upload_max_bytes: 'Dung lượng tối đa một ảnh (byte)',
  upload_expiry_minutes: 'Hiệu lực URL tải lên (phút)',
  image_max_edge: 'Cạnh dài ảnh chính (pixel)',
  thumbnail_edge: 'Cạnh dài thumbnail (pixel)',
  token_retention_days: 'Giữ token đã hết hạn thêm (ngày)',
  push_enabled: 'Bật gửi Expo push',
  push_max_attempts: 'Số lần thử push tối đa',
  confirmation_minutes: 'Hạn xác nhận đơn sell-out (phút)',
  delivery_sla_minutes: 'Hạn việc xử lý thiếu / hỏng (phút)',
};
function SettingsForm({ data }: { data: Settings }) {
  const client = useQueryClient();
  const [value, setValue] = useState(data.value);
  const [reason, setReason] = useState('');
  const save = useMutation({
    mutationFn: () =>
      apiRequest('/operations/settings', {
        method: 'PUT',
        body: JSON.stringify({ value, version: data.version, reason }),
      }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['runtime-settings'] }),
  });
  return (
    <form
      className="live-form"
      onSubmit={(event) => {
        event.preventDefault();
        save.mutate();
      }}
    >
      {Object.entries(value).map(([key, current]) => (
        <label key={key}>
          {names[key] || key}
          {typeof current === 'boolean' ? (
            <input
              type="checkbox"
              checked={current}
              onChange={(event) => setValue({ ...value, [key]: event.target.checked })}
            />
          ) : (
            <input
              type="number"
              value={current}
              step={1}
              required
              onChange={(event) => setValue({ ...value, [key]: Number(event.target.value) })}
            />
          )}
        </label>
      ))}
      <label>
        Lý do thay đổi
        <input
          required
          maxLength={500}
          value={reason}
          onChange={(event) => setReason(event.target.value)}
        />
      </label>
      <p>
        Chốt ngày vẫn yêu cầu đủ xác nhận hai bên. Ngưỡng duyệt dùng để cảnh báo; không tự bỏ qua
        bước duyệt hiện có.
      </p>
      <button disabled={save.isPending}>Lưu cấu hình</button>
      {save.isError && <ErrorState error={save.error} />}
    </form>
  );
}
export default function OperationsPanel({ me }: { me: CurrentUser }) {
  const [page, setPage] = useState(0);
  const settings = useQuery({
    queryKey: ['runtime-settings', me.tenant.id],
    queryFn: ({ signal }) => apiRequest<Settings>('/operations/settings', { signal }),
  });
  const jobs = useQuery({
    queryKey: ['jobs', me.tenant.id, page],
    queryFn: ({ signal }) =>
      apiRequest<{ items: Record<string, unknown>[]; totalPages: number }>(
        `/operations/jobs?page=${page}`,
        { signal },
      ),
    refetchInterval: 45000,
  });
  return (
    <>
      <section className="live-panel">
        <h1>Cấu hình vận hành</h1>
        {settings.isPending ? (
          <Loading />
        ) : settings.isError ? (
          <ErrorState error={settings.error} retry={() => void settings.refetch()} />
        ) : (
          <SettingsForm key={settings.data.version} data={settings.data} />
        )}
      </section>
      <section className="live-panel">
        <h2>Lịch sử job</h2>
        {jobs.isPending ? (
          <Loading />
        ) : jobs.isError ? (
          <ErrorState error={jobs.error} retry={() => void jobs.refetch()} />
        ) : (
          <>
            {jobs.data.items.length ? (
              jobs.data.items.map((job) => (
                <details key={String(job.id)}>
                  <summary>
                    {String(job.job_name)} · {String(job.status)} · {String(job.run_key)} ·{' '}
                    {String(job.record_count)} bản ghi
                  </summary>
                  <DataView data={job} />
                </details>
              ))
            ) : (
              <p>Chưa có lượt chạy.</p>
            )}
            <Pagination page={page} totalPages={jobs.data.totalPages} setPage={setPage} />
          </>
        )}
      </section>
    </>
  );
}
