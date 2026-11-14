import { apiRequest } from './client';

export type ImageOwner = 'VISIT' | 'TICKET' | 'ORDER_SO';
export async function compressImage(file: File, maxBytes: number, maxEdge: number): Promise<Blob> {
  if (!['image/jpeg', 'image/png'].includes(file.type) || file.size > 10 * 1024 * 1024)
    throw new Error('Chọn ảnh JPEG/PNG tối đa 10 MB.');
  const bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' });
  try {
    if (bitmap.width * bitmap.height > 20_000_000) throw new Error('Ảnh vượt 20 triệu pixel.');
    const ratio = Math.min(1, maxEdge / Math.max(bitmap.width, bitmap.height));
    const canvas = document.createElement('canvas');
    canvas.width = Math.max(1, Math.round(bitmap.width * ratio));
    canvas.height = Math.max(1, Math.round(bitmap.height * ratio));
    const context = canvas.getContext('2d');
    if (!context) throw new Error('Trình duyệt không hỗ trợ xử lý ảnh.');
    context.fillStyle = '#fff';
    context.fillRect(0, 0, canvas.width, canvas.height);
    context.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    for (const quality of [0.7, 0.6, 0.5]) {
      const blob = await new Promise<Blob>((resolve, reject) =>
        canvas.toBlob(
          (value) => (value ? resolve(value) : reject(new Error('Không thể nén ảnh.'))),
          'image/jpeg',
          quality,
        ),
      );
      if (blob.size <= maxBytes) return blob;
    }
    throw new Error('Ảnh sau nén vẫn vượt giới hạn của tenant. Hãy chọn ảnh nhỏ hơn.');
  } finally {
    bitmap.close();
  }
}

export async function uploadImage(ownerType: ImageOwner, ownerId: string, file: File) {
  const options = await apiRequest<{ enabled: boolean; maxBytes: number; maxEdge: number }>(
    '/attachments/options',
  );
  if (!options.enabled) {
    throw new Error('Tính năng ảnh sắp có; hiện chưa cấu hình kho lưu trữ ngoài database.');
  }
  const image = await compressImage(file, options.maxBytes, options.maxEdge);
  const upload = await apiRequest<{ id: string; url: string; headers: Record<string, string> }>(
    '/attachments/presign',
    {
      method: 'POST',
      body: JSON.stringify({
        ownerType,
        ownerId,
        fileName: file.name.slice(0, 240),
        contentType: image.type,
        sizeBytes: image.size,
      }),
    },
  );
  // A signed storage URL carries its own authorization. Never forward the API bearer token.
  const response = await fetch(upload.url, {
    method: 'PUT',
    headers: upload.headers,
    body: image,
    credentials: 'omit',
  });
  if (!response.ok) throw new Error('Kho ảnh chưa nhận được tệp. Hãy thử lại.');
  await apiRequest(`/attachments/${upload.id}/complete`, { method: 'POST' });
  return upload.id;
}
