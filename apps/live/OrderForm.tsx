import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { can, type CurrentUser } from '../../packages/api/client';
import {
  calculateOrder,
  createOrder,
  getRecord,
  type OrderInput,
} from '../../packages/api/services';
import { saveDraft } from '../../packages/api/offline';
import { DataView, ErrorState, Lookup } from './components';

export function OrderForm({
  kind,
  me,
  mobile,
  done,
}: {
  kind: 'sell-in' | 'sell-out';
  me: CurrentUser;
  mobile: boolean;
  done: () => void;
}) {
  const client = useQueryClient();
  const [key] = useState(() => crypto.randomUUID());
  const [lines, setLines] = useState([{ productId: '', unitCode: '', quantity: 1 }]);
  const [npp, setNpp] = useState(me.tenant.type === 'NPP' ? me.tenant.id : '');
  const [outlet, setOutlet] = useState('');
  const [quote, setQuote] = useState<{ totalVnd: number; calculatedAt: string } | null>(null);
  const [message, setMessage] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [lastInput, setLastInput] = useState('');
  const mutation = useMutation({
    mutationFn: async ({ input, preview }: { input: OrderInput; preview: boolean }) => {
      setError(null);
      if (preview) {
        const result = await calculateOrder(input);
        setLastInput(JSON.stringify(input));
        setQuote(result);
        return;
      }
      if (mobile && kind === 'sell-out') {
        saveDraft({
          id: key,
          key,
          owner: `${me.tenant.id}:${me.user.id}`,
          input,
          state: 'pending',
          expectedTotal: quote && JSON.stringify(input) === lastInput ? quote.totalVnd : undefined,
        });
        setMessage('Đã lưu đơn trên thiết bị. Mở Đơn chờ gửi để theo dõi.');
      } else {
        await createOrder(kind, input, key);
        await client.invalidateQueries({ queryKey: ['records'] });
        done();
      }
    },
    onError: setError,
  });
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const input: OrderInput = {
      lines,
      reason: String(data.get('reason')),
      note: String(data.get('note') || ''),
      submit: true,
    };
    if (kind === 'sell-in') input.nsxTenantId = String(data.get('nsxTenantId'));
    else {
      input.outletId = String(data.get('outletId'));
      input.nppTenantId = String(data.get('nppTenantId'));
      input.voucherCode = String(data.get('voucherCode') || '');
    }
    const preview =
      (event.nativeEvent as SubmitEvent).submitter?.getAttribute('value') === 'preview';
    mutation.mutate({ input, preview });
  }
  const sent = Boolean(message);
  return (
    <form
      className="live-form"
      onSubmit={submit}
      onChange={() => {
        setQuote(null);
        setLastInput('');
      }}
    >
      {kind === 'sell-in' ? (
        <label>
          Mã định danh nhà sản xuất (do quản trị viên cung cấp)
          <input name="nsxTenantId" required />
        </label>
      ) : (
        <>
          {can(me, 'npp:view') ? (
            <Lookup
              path="/npps"
              name="nppTenantId"
              label="Nhà phân phối"
              value={npp}
              onChange={(id) => {
                setNpp(id);
                setQuote(null);
              }}
            />
          ) : (
            <label>
              Nhà phân phối (mã định danh)
              <input
                name="nppTenantId"
                value={npp}
                onChange={(event) => setNpp(event.target.value)}
                required
              />
            </label>
          )}
          {can(me, 'outlet:view') ? (
            <Lookup
              path="/outlets"
              name="outletId"
              label="Điểm bán"
              value={outlet}
              onChange={(id) => {
                setOutlet(id);
                setQuote(null);
              }}
            />
          ) : (
            <label>
              Điểm bán (mã định danh)
              <input name="outletId" required />
            </label>
          )}
        </>
      )}
      <fieldset>
        <legend>Sản phẩm đặt hàng</legend>
        {lines.map((line, index) => (
          <div key={index}>
            <ProductLine
              line={line}
              change={(next) => {
                setLines(lines.map((item, i) => (i === index ? next : item)));
                setQuote(null);
              }}
            />
            <button
              type="button"
              disabled={lines.length === 1}
              onClick={() => {
                setLines(lines.filter((_, i) => i !== index));
                setQuote(null);
              }}
            >
              Xóa dòng
            </button>
          </div>
        ))}
        <button
          type="button"
          onClick={() => {
            setLines([...lines, { productId: '', unitCode: '', quantity: 1 }]);
            setQuote(null);
          }}
        >
          Thêm dòng
        </button>
      </fieldset>
      {kind === 'sell-out' && (
        <label>
          Voucher (tùy chọn)
          <input name="voucherCode" />
        </label>
      )}
      <label>
        Ghi chú
        <input name="note" maxLength={2000} />
      </label>
      <label>
        Lý do
        <input name="reason" required maxLength={500} />
      </label>
      {quote && <DataView data={quote} />}
      {error !== null && <ErrorState error={error} />}
      {message && <p role="status">{message}</p>}
      <div className="live-actions">
        {kind === 'sell-out' && can(me, 'price_sellout:view') && (
          <button name="action" value="preview" disabled={mutation.isPending || sent}>
            Xem giá máy chủ
          </button>
        )}
        <button name="action" value="send" disabled={mutation.isPending || sent}>
          {mutation.isPending ? 'Đang xử lý…' : mobile ? 'Lưu đơn chờ gửi' : 'Gửi đơn'}
        </button>
        {sent && (
          <button type="button" onClick={done}>
            Đóng
          </button>
        )}
      </div>
    </form>
  );
}
function ProductLine({
  line,
  change,
}: {
  line: OrderInput['lines'][number];
  change: (line: OrderInput['lines'][number]) => void;
}) {
  const product = useQuery({
    queryKey: ['product-units', line.productId],
    enabled: !!line.productId,
    queryFn: ({ signal }) =>
      getRecord<{ units: { unitCode: string; unitName: string }[] }>(
        `/products/${line.productId}`,
        signal,
      ),
  });
  return (
    <div className="live-line">
      <Lookup
        path="/products"
        label="Sản phẩm"
        value={line.productId}
        onChange={(id) => change({ ...line, productId: id, unitCode: '' })}
      />
      <label>
        Đơn vị
        <select
          required
          value={line.unitCode}
          onChange={(event) => change({ ...line, unitCode: event.target.value })}
        >
          <option value="">Chọn đơn vị</option>
          {product.data?.units.map((unit) => (
            <option key={unit.unitCode} value={unit.unitCode}>
              {unit.unitName}
            </option>
          ))}
        </select>
      </label>
      <label>
        Số lượng
        <input
          type="number"
          min="1"
          step="1"
          required
          value={line.quantity}
          onChange={(event) => change({ ...line, quantity: Number(event.target.value) })}
        />
      </label>
      {product.isError && <ErrorState error={product.error} retry={() => void product.refetch()} />}
    </div>
  );
}
