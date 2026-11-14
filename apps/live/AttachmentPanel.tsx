import { useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiRequest, type CurrentUser } from '../../packages/api/client';
import { uploadImage, type ImageOwner } from '../../packages/api/images';
import { ErrorState } from './components';

export default function AttachmentPanel({
  ownerType,
  ownerId,
  canUpload,
  me,
}: {
  ownerType: ImageOwner;
  ownerId: string;
  canUpload: boolean;
  me: CurrentUser;
}) {
  const client = useQueryClient();
  const input = useRef<HTMLInputElement>(null);
  const [lastId, setLastId] = useState('');
  const [viewError, setViewError] = useState<unknown>(null);
  const list = useQuery({
    queryKey: ['attachments', me.tenant.id, me.user.id, ownerType, ownerId],
    queryFn: ({ signal }) =>
      apiRequest<{ id: string; file_name: string }[]>(
        `/attachments?ownerType=${ownerType}&ownerId=${ownerId}`,
        { signal },
      ),
  });
  const photoOptions = useQuery({
    queryKey: ['attachment-options'],
    queryFn: ({ signal }) =>
      apiRequest<{ enabled: boolean }>('/attachments/options', { signal }),
  });
  const upload = useMutation({
    mutationFn: (file: File) => uploadImage(ownerType, ownerId, file),
    onSuccess: async (id) => {
      setLastId(id);
      if (input.current) input.current.value = '';
      await client.invalidateQueries({ queryKey: ['attachments'] });
    },
  });
  async function view(id: string) {
    // Open synchronously to preserve the user's browser popup gesture.
    const windowRef = window.open('about:blank', '_blank');
    if (windowRef) windowRef.opener = null;
    try {
      const result = await apiRequest<{ url: string }>(`/attachments/${id}/download`);
      if (windowRef) windowRef.location.href = result.url;
      else throw new Error('Trình duyệt đang chặn cửa sổ xem ảnh.');
    } catch (error) {
      windowRef?.close();
      setViewError(error);
    }
  }
  return (
    <section className="live-panel">
      <h2>Ảnh đính kèm</h2>
      {canUpload && photoOptions.data?.enabled && (
        <label>
          Chọn ảnh JPEG/PNG (tự nén trước khi tải)
          <input
            ref={input}
            type="file"
            accept="image/jpeg,image/png"
            disabled={upload.isPending}
            onChange={(event) => {
              const file = event.target.files?.[0];
              if (file) upload.mutate(file);
            }}
          />
        </label>
      )}
      {canUpload && !photoOptions.isPending && !photoOptions.isError && !photoOptions.data?.enabled && (
        <p role="status">Sắp có — tính năng ảnh chưa được bật vì chưa cấu hình kho lưu trữ ngoài.</p>
      )}
      {photoOptions.isError && <ErrorState error={photoOptions.error} retry={() => void photoOptions.refetch()} />}
      {upload.isPending && <p>Đang nén, tải lên và tạo ảnh xem trước…</p>}
      {upload.isError && <ErrorState error={upload.error} />}
      {viewError != null && <ErrorState error={viewError} />}
      {lastId && (
        <p role="status">
          Đã tải ảnh.
          {ownerType === 'ORDER_SO' && (
            <>
              {' '}
              Mã ảnh để dùng khi xác nhận thiếu/hỏng: <code>{lastId}</code>
            </>
          )}
        </p>
      )}
      {list.isError ? (
        <ErrorState error={list.error} retry={() => void list.refetch()} />
      ) : (
        list.data?.map((file) => (
          <div key={file.id} className="live-photo">
            <Thumbnail id={file.id} name={file.file_name} me={me} />
            <button onClick={() => void view(file.id)}>Xem {file.file_name}</button>
          </div>
        ))
      )}
    </section>
  );
}
function Thumbnail({ id, name, me }: { id: string; name: string; me: CurrentUser }) {
  const image = useQuery({
    queryKey: ['image-thumbnail', me.tenant.id, me.user.id, id],
    queryFn: ({ signal }) =>
      apiRequest<{ url: string }>(`/attachments/${id}/download?thumbnail=true`, { signal }),
    staleTime: 240000,
  });
  return image.data ? (
    <img
      src={image.data.url}
      alt={`Ảnh ${name}`}
      width={160}
      height={120}
      loading="lazy"
      style={{ objectFit: 'contain', maxWidth: '100%' }}
    />
  ) : image.isError ? (
    <ErrorState error={image.error} retry={() => void image.refetch()} />
  ) : null;
}
