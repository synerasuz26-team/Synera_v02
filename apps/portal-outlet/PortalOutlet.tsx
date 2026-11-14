import { useMemo, useState } from 'react';
import {
  ArrowLeft,
  ArrowRight,
  Bell,
  CalendarDays,
  Check,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  CircleHelp,
  Clock3,
  Heart,
  Home,
  ImagePlus,
  MapPin,
  Minus,
  Package,
  Phone,
  Plus,
  Search,
  Send,
  ShieldCheck,
  ShoppingBag,
  ShoppingCart,
  Store,
  Ticket,
  Truck,
  UserRound,
  Users,
  X,
} from 'lucide-react';
import {
  orders as demoOrders,
  people,
  products,
  stores,
  tickets as demoTickets,
} from '../../packages/mock-data';

type Screen = 'home' | 'shop' | 'orders' | 'feedback' | 'account';
type UserRole = 'owner' | 'staff';
type ReceiveCondition = 'full' | 'short' | 'damaged';
type CartLine = { sku: string; name: string; quantity: number };
type OutletOrder = {
  id: string;
  lines: CartLine[];
  ordered: number;
  accepted: number;
  shipped: number;
  received: number;
  receiptConfirmed: boolean;
  status: string;
  createdAt: string;
  note: string;
  freeGoods?: CartLine[];
};
type Feedback = {
  id: string;
  title: string;
  orderId: string;
  status: string;
  resolution: string;
  expected: string;
  assignee: string;
  attachment?: string;
  attachmentPreview?: string;
};

const shop = stores.find((item) => item.name === 'Tạp hóa Lan')!;
const deliveryPerson = people.sales;
const nppContact = people.distributor;
const productName = (sku: string) => products.find((item) => item.sku === sku)?.name ?? sku;
const fmt = new Intl.NumberFormat('vi-VN');
const money = (value: number) => `${fmt.format(value)}đ`;
const productSubtotal = (sku: string, quantity: number) => {
  const product = products.find((item) => item.sku === sku);
  if (!product) return 0;
  const discount = sku === 'TX330' && quantity >= 8 ? 0.03 : 0;
  return Math.round(product.sellOut * quantity * (1 - discount));
};
const initialOrders: OutletOrder[] = demoOrders
  .filter((order) => order.id === 'SO-24081' || order.id === 'SO-24076')
  .map((order) => ({
    id: order.id,
    lines:
      order.lines.length > 0
        ? order.lines.map((line) => ({ sku: line.sku, name: line.name, quantity: line.quantity }))
        : [{ sku: 'NS150', name: productName('NS150'), quantity: 9 }],
    ordered: order.ordered,
    accepted: order.confirmed,
    shipped: order.shipped,
    received: 0,
    receiptConfirmed: false,
    status: order.id === 'SO-24076' ? 'Giao thiếu · bù 13/11' : 'Chờ NPP xác nhận lượng giao',
    createdAt: order.createdAt,
    note:
      order.id === 'SO-24076'
        ? 'NPP xác nhận 7 thùng; còn 2 thùng hẹn giao bù ngày 13/11.'
        : 'Đơn đã gửi tới NPP Minh Phát. Chờ xác nhận lượng giao.',
    freeGoods: order.freeGoods?.map((line) => ({
      sku: line.sku,
      name: line.name,
      quantity: line.quantity,
    })),
  }));
const initialFeedback: Feedback[] = [
  {
    id: demoTickets.find((ticket) => ticket.id === 'TK-331')?.id ?? 'TK-331',
    title: 'Giao thiếu 2 thùng NS150',
    orderId: 'SO-24076',
    status: 'Đang xử lý',
    resolution: 'NPP Minh Phát xác nhận giao bù 2 thùng',
    expected: '13/11/2026',
    assignee: nppContact.name,
  },
];

const navItems: { id: Screen; label: string; icon: typeof Home }[] = [
  { id: 'home', label: 'Trang chủ', icon: Home },
  { id: 'shop', label: 'Đặt hàng', icon: ShoppingBag },
  { id: 'orders', label: 'Đơn của tôi', icon: Package },
  { id: 'feedback', label: 'Phản ánh', icon: Heart },
  { id: 'account', label: 'Tài khoản', icon: UserRound },
];

function ProductArt({ sku }: { sku: string }) {
  const isTea = sku === 'TX330';
  const isLarge = sku === 'NS150';
  const body = isTea ? '#91c98f' : '#c9e8ff';
  const cap = isTea ? '#1e8c67' : '#2f5bea';
  return (
    <svg
      aria-hidden="true"
      className={`outlet-product-art ${isTea ? 'tea' : ''}`}
      viewBox="0 0 120 120"
    >
      {isTea ? (
        <>
          <ellipse cx="60" cy="104" fill="#cbd5e1" opacity=".34" rx="35" ry="6" />
          <path
            d="M43 18h34l6 10v61c0 5-4 9-9 9H46c-5 0-9-4-9-9V28z"
            fill={body}
            stroke="#4c956c"
            strokeWidth="2"
          />
          <path d="M43 35h40v37H43z" fill="#f3f8ed" opacity=".94" />
          <path d="M47 13h26v11H47z" fill={cap} />
          <path
            d="M50 46h26M50 53h21M50 60h25"
            stroke="#347955"
            strokeLinecap="round"
            strokeWidth="3"
          />
          <path d="M60 26v-5" stroke="#bce6ce" strokeLinecap="round" strokeWidth="3" />
        </>
      ) : (
        <>
          <ellipse cx="60" cy="105" fill="#cbd5e1" opacity=".34" rx="34" ry="6" />
          <path
            d={
              isLarge
                ? 'M48 22h24l3 15 7 11v45c0 6-5 10-11 10H49c-6 0-11-4-11-10V48l7-11z'
                : 'M49 28h22l3 12 7 10v43c0 6-5 10-11 10H50c-6 0-11-4-11-10V50l7-10z'
            }
            fill={body}
            stroke="#76a9d8"
            strokeWidth="2"
          />
          <path d="M48 17h24v13H48z" fill={cap} />
          <path d="M42 53h36v29H42z" fill="#fff" opacity=".94" />
          <path
            d="M48 61h24M48 68h19M48 75h14"
            stroke="#2f5bea"
            strokeLinecap="round"
            strokeWidth="3"
          />
          <path d="M50 36v-5M70 36v-5" stroke="#fff" strokeLinecap="round" strokeWidth="2" />
        </>
      )}
    </svg>
  );
}

function Pill({ children, tone = 'blue' }: { children: React.ReactNode; tone?: string }) {
  return <span className={`outlet-pill ${tone}`}>{children}</span>;
}

function QuantityStepper({
  value,
  label,
  onChange,
  min = 0,
  max,
}: {
  value: number;
  label: string;
  onChange: (value: number) => void;
  min?: number;
  max?: number;
}) {
  const clamp = (quantity: number) =>
    Math.min(max ?? Number.POSITIVE_INFINITY, Math.max(min, quantity));
  return (
    <div className="outlet-quantity">
      <button aria-label={`Giảm ${label}`} onClick={() => onChange(clamp(value - 1))} type="button">
        <Minus size={17} />
      </button>
      <input
        aria-label={label}
        max={max}
        min={min}
        onChange={(event) => onChange(clamp(Number(event.target.value) || 0))}
        type="number"
        value={value}
      />
      <button aria-label={`Tăng ${label}`} onClick={() => onChange(clamp(value + 1))} type="button">
        <Plus size={17} />
      </button>
    </div>
  );
}

function OrderStepper({ order }: { order: OutletOrder }) {
  const labels = ['Đã gửi', 'NPP xác nhận lượng', 'Đang giao', 'Tôi xác nhận nhận'];
  const active = order.received > 0 ? 3 : order.shipped > 0 ? 2 : order.accepted > 0 ? 1 : 0;
  return (
    <div className="outlet-order-stepper">
      {labels.map((label, index) => (
        <div className={`outlet-step${index <= active ? ' active' : ''}`} key={label}>
          <i>{index < active ? <Check size={14} /> : index + 1}</i>
          <span>{label}</span>
        </div>
      ))}
    </div>
  );
}

export default function PortalOutlet() {
  const [screen, setScreen] = useState<Screen>('home');
  const [role, setRole] = useState<UserRole>('owner');
  const [cart, setCart] = useState<Record<string, number>>({});
  const [orders, setOrders] = useState<OutletOrder[]>(initialOrders);
  const [feedback, setFeedback] = useState<Feedback[]>(initialFeedback);
  const [search, setSearch] = useState('');
  const [confirmation, setConfirmation] = useState<OutletOrder | null>(null);
  const [selectedOrder, setSelectedOrder] = useState<OutletOrder | null>(null);
  const [receiveOrder, setReceiveOrder] = useState<OutletOrder | null>(null);
  const [receiveCondition, setReceiveCondition] = useState<ReceiveCondition | null>(null);
  const [issueQty, setIssueQty] = useState(1);
  const [photoName, setPhotoName] = useState('');
  const [photoPreview, setPhotoPreview] = useState('');
  const [ticketOrderId, setTicketOrderId] = useState('');
  const [ticketTitle, setTicketTitle] = useState('');
  const [employeeName, setEmployeeName] = useState('');
  const [employees, setEmployees] = useState([
    { name: 'Linh Nguyễn', role: 'Nhân viên cửa hàng', active: true },
  ]);
  const [toast, setToast] = useState('');
  const [drawerOpen, setDrawerOpen] = useState(false);

  const cartQty = products.reduce((sum, product) => sum + (cart[product.sku] ?? 0), 0);
  const cartSubtotal = products.reduce(
    (sum, product) => sum + productSubtotal(product.sku, cart[product.sku] ?? 0),
    0,
  );
  const cartGiftQty = Math.floor((cart.NS500 ?? 0) / 10);
  const filteredProducts = useMemo(
    () =>
      products.filter((product) =>
        `${product.sku} ${product.name}`.toLowerCase().includes(search.trim().toLowerCase()),
      ),
    [search],
  );
  const currentUser =
    role === 'owner'
      ? people.storeOwner
      : employees.find((employee) => employee.active)?.name
        ? { name: employees.find((employee) => employee.active)!.name, title: 'Nhân viên cửa hàng' }
        : { name: 'Nhân viên cửa hàng', title: 'Nhân viên cửa hàng' };

  const notify = (message: string) => {
    setToast(message);
    window.setTimeout(() => setToast(''), 3200);
  };
  const selectPhoto = (file?: File) => {
    if (!file) {
      setPhotoName('');
      setPhotoPreview('');
      return;
    }
    const reader = new FileReader();
    reader.onload = () => {
      if (typeof reader.result !== 'string') {
        notify('Không đọc được ảnh. Vui lòng chọn ảnh khác.');
        return;
      }
      setPhotoName(file.name);
      setPhotoPreview(reader.result);
    };
    reader.onerror = () => notify('Không đọc được ảnh. Vui lòng chọn ảnh khác.');
    reader.readAsDataURL(file);
  };
  const setQuantity = (sku: string, value: number) =>
    setCart((current) => ({ ...current, [sku]: Math.max(0, value) }));
  const openFeedbackForOrder = (orderId: string, title = '') => {
    setTicketOrderId(orderId);
    setTicketTitle(title);
    setScreen('feedback');
    setSelectedOrder(null);
  };
  const sendOrder = () => {
    const lines = products
      .filter((product) => (cart[product.sku] ?? 0) > 0)
      .map((product) => ({
        sku: product.sku,
        name: product.name,
        quantity: cart[product.sku],
      }));
    const ordered = lines.reduce((sum, line) => sum + line.quantity, 0);
    if (!ordered) {
      notify('Chọn số lượng sản phẩm trước khi gửi đơn.');
      return;
    }
    const number =
      Math.max(24081, ...orders.map((order) => Number(order.id.replace('SO-', '')))) + 1;
    const nextOrder: OutletOrder = {
      id: `SO-${number}`,
      lines,
      ordered,
      accepted: 0,
      shipped: 0,
      received: 0,
      receiptConfirmed: false,
      status: 'Chờ NPP xác nhận lượng giao',
      createdAt: '12/11/2026 · vừa gửi',
      note: `Đã gửi bởi ${currentUser.name}. Chờ NPP Minh Phát xác nhận lượng giao.`,
      freeGoods:
        cartGiftQty > 0
          ? [{ sku: 'NS500', name: productName('NS500'), quantity: cartGiftQty }]
          : [],
    };
    setOrders((current) => [nextOrder, ...current]);
    setCart({});
    setConfirmation(nextOrder);
    notify(`Đã gửi đơn ${nextOrder.id} tới NPP Minh Phát.`);
  };
  const reorderLast = () => {
    const previous = orders.find((order) => order.id === 'SO-24076') ?? orders[0];
    if (!previous) return;
    setCart(Object.fromEntries(previous.lines.map((line) => [line.sku, line.quantity])));
    setConfirmation(null);
    setScreen('shop');
  };
  const openReceive = (order: OutletOrder) => {
    setReceiveOrder(order);
    setReceiveCondition(null);
    setIssueQty(1);
    setPhotoName('');
    setPhotoPreview('');
  };
  const confirmReceipt = () => {
    if (!receiveOrder || !receiveCondition) return;
    if (receiveCondition !== 'full' && (!photoName || !photoPreview)) {
      notify('Vui lòng chụp ảnh hàng thiếu hoặc hỏng để gửi phản ánh.');
      return;
    }
    const delivered = receiveOrder.shipped || receiveOrder.accepted;
    if (receiveCondition !== 'full' && (issueQty < 1 || issueQty > delivered)) {
      notify(`Số thùng thiếu hoặc hỏng phải từ 1 đến ${delivered}.`);
      return;
    }
    const received = receiveCondition === 'full' ? delivered : Math.max(0, delivered - issueQty);
    const nextStatus =
      receiveCondition === 'full'
        ? 'Đã nhận đủ'
        : receiveCondition === 'short'
          ? `Đã nhận thiếu ${issueQty}`
          : `Đã nhận hàng hỏng ${issueQty}`;
    setOrders((current) =>
      current.map((order) =>
        order.id === receiveOrder.id
          ? {
              ...order,
              received,
              receiptConfirmed: true,
              status: nextStatus,
              note: `${currentUser.name} xác nhận ${
                receiveCondition === 'full'
                  ? `nhận đủ ${received}/${delivered} thùng thực xuất`
                  : `${receiveCondition === 'short' ? 'thiếu' : 'hỏng'} ${issueQty} thùng · thực nhận ${received}/${delivered}`
              } · 12/11/2026${receiveOrder.status.includes('thiếu') ? `. ${receiveOrder.note}` : ''}`,
            }
          : order,
      ),
    );
    if (receiveCondition !== 'full') {
      const newFeedback: Feedback = {
        id: `PA-${feedback.length + 240}`,
        title:
          receiveCondition === 'short'
            ? `Báo thiếu ${issueQty} thùng · ${receiveOrder.id}`
            : `Báo hỏng ${issueQty} thùng · ${receiveOrder.id}`,
        orderId: receiveOrder.id,
        status: 'Đã gửi tới NPP',
        resolution: 'NPP đang kiểm tra và đề xuất xử lý',
        expected: '13/11/2026',
        assignee: nppContact.name,
        attachment: photoName,
        attachmentPreview: photoPreview,
      };
      setFeedback((current) => [newFeedback, ...current]);
      notify(`Đã xác nhận nhận hàng và tạo phản ánh ${newFeedback.id}.`);
    } else {
      notify(`Đã xác nhận nhận hàng đơn ${receiveOrder.id}.`);
    }
    setReceiveOrder(null);
    setPhotoName('');
    setPhotoPreview('');
  };
  const submitFeedback = () => {
    if (!ticketTitle.trim()) {
      notify('Vui lòng nhập nội dung phản ánh.');
      return;
    }
    const nextFeedback: Feedback = {
      id: `PA-${feedback.length + 240}`,
      title: ticketTitle.trim(),
      orderId: ticketOrderId || orders[0]?.id || 'Chưa gắn đơn',
      status: 'Đã gửi tới NPP',
      resolution: 'NPP Minh Phát sẽ phản hồi',
      expected: '13/11/2026',
      assignee: nppContact.name,
      attachment: photoName || undefined,
      attachmentPreview: photoPreview || undefined,
    };
    setFeedback((current) => [nextFeedback, ...current]);
    setTicketTitle('');
    setTicketOrderId('');
    setPhotoName('');
    setPhotoPreview('');
    notify(`Đã gửi phản ánh ${nextFeedback.id} tới NPP.`);
  };
  const addEmployee = () => {
    if (!employeeName.trim()) {
      notify('Nhập tên nhân viên trước khi mời.');
      return;
    }
    setEmployees((current) => [
      ...current,
      { name: employeeName.trim(), role: 'Nhân viên cửa hàng', active: true },
    ]);
    setEmployeeName('');
    notify('Đã mời nhân viên vào cửa hàng.');
  };

  const renderHome = () => {
    const latest = orders.find((order) => order.id === 'SO-24076') ?? orders[0];
    const pending = orders.find((order) => order.status.startsWith('Chờ'));
    return (
      <>
        <section className="outlet-greeting">
          <div className="outlet-greeting-copy">
            <span className="outlet-overline">TẠP HÓA LAN</span>
            <h1>Chào {people.storeOwner.name}</h1>
            <p>Mình cần đặt gì hôm nay?</p>
          </div>
          <div className="outlet-greeting-art">
            <Store size={32} />
          </div>
        </section>
        <section className="outlet-reorder-card">
          <div className="outlet-card-title">
            <span className="outlet-icon-bubble blue">
              <RotateIcon />
            </span>
            <div>
              <span className="outlet-overline">ĐẶT LẠI ĐƠN GẦN NHẤT</span>
              <strong>Đơn {latest?.id ?? 'SO-24076'}</strong>
            </div>
            <Pill tone="soft-green">Giao thiếu 2</Pill>
          </div>
          <div className="outlet-reorder-lines">
            {(latest?.lines ?? [{ sku: 'NS150', name: productName('NS150'), quantity: 9 }]).map(
              (line) => (
                <div className="outlet-reorder-line" key={line.sku}>
                  <span>
                    <strong>{line.name}</strong>
                    <small>
                      {line.sku} ·{' '}
                      {money(products.find((item) => item.sku === line.sku)?.sellOut ?? 0)}/thùng
                    </small>
                  </span>
                  <span className="outlet-reorder-qty">{line.quantity} thùng</span>
                </div>
              ),
            )}
          </div>
          <button className="outlet-button green full" onClick={reorderLast}>
            <RotateIcon /> Đặt lại đơn này <ArrowRight size={18} />
          </button>
          <small className="outlet-tap-hint">Số lượng có thể chỉnh trước khi gửi</small>
        </section>
        <section className="outlet-home-order">
          <div className="outlet-section-heading">
            <h2>Đơn đang chờ</h2>
            <button onClick={() => setScreen('orders')}>
              Tất cả <ChevronRight size={16} />
            </button>
          </div>
          {pending ? (
            <button className="outlet-pending-order" onClick={() => setSelectedOrder(pending)}>
              <span className="outlet-order-icon">
                <ShoppingBag size={19} />
              </span>
              <span>
                <strong>
                  {pending.id} · {pending.ordered} thùng
                </strong>
                <small>{pending.status}</small>
              </span>
              <ChevronRight size={18} />
            </button>
          ) : (
            <p className="outlet-empty-small">Chưa có đơn nào đang chờ.</p>
          )}
        </section>
        <section className="outlet-contact-card">
          <div className="outlet-section-heading">
            <h2>Người phụ trách của tôi</h2>
            <span className="outlet-online-dot">Đang phụ trách</span>
          </div>
          <div className="outlet-contact-person">
            <span className="outlet-avatar">TH</span>
            <span>
              <strong>{deliveryPerson.name}</strong>
              <small>Sales · {deliveryPerson.title.split('·')[1]?.trim()}</small>
            </span>
            <a className="outlet-call-button" href="tel:+84900000000" aria-label="Gọi Trần Hà">
              <Phone size={19} />
              <span>Gọi</span>
            </a>
          </div>
          <div className="outlet-contact-divider" />
          <div className="outlet-contact-person compact">
            <span className="outlet-avatar npp">MP</span>
            <span>
              <strong>NPP Minh Phát</strong>
              <small>Đầu mối: {nppContact.name}</small>
            </span>
            <a
              className="outlet-call-button"
              href="tel:+84900000001"
              aria-label="Gọi NPP Minh Phát"
            >
              <Phone size={19} />
              <span>Gọi</span>
            </a>
          </div>
        </section>
        <section className="outlet-appointment">
          <div className="outlet-appointment-icon">
            <CalendarDays size={18} />
          </div>
          <div>
            <span className="outlet-overline">LỜI HẸN SẮP TỚI · 13/11</span>
            <strong>“Chốt lại trà xanh nếu còn CTKM”</strong>
            <small>{deliveryPerson.name} · Tuyến Quận 3</small>
          </div>
          <ChevronRight size={17} />
        </section>
      </>
    );
  };

  const renderShop = () =>
    confirmation ? (
      <section className="outlet-confirmation-screen">
        <span className="outlet-confirmation-icon">
          <CheckCircle2 size={37} />
        </span>
        <span className="outlet-overline">GỬI ĐƠN THÀNH CÔNG</span>
        <h1>Đơn đã gửi</h1>
        <strong className="outlet-confirmation-id">{confirmation.id}</strong>
        <p>
          Đơn đã gửi, chờ NPP xác nhận lượng giao.
          <br />
          <b>Đơn chưa đồng nghĩa hàng đã được giao.</b>
        </p>
        <div className="outlet-confirmation-summary">
          <span>{confirmation.ordered} thùng đã đặt</span>
          <span>
            {money(
              confirmation.lines.reduce(
                (sum, line) => sum + productSubtotal(line.sku, line.quantity),
                0,
              ),
            )}
          </span>
        </div>
        <button
          className="outlet-button green full"
          onClick={() => {
            setConfirmation(null);
            setScreen('orders');
          }}
        >
          Theo dõi đơn hàng <ArrowRight size={18} />
        </button>
        <button
          className="outlet-text-link full"
          onClick={() => {
            setConfirmation(null);
            setScreen('home');
          }}
        >
          Về trang chủ
        </button>
      </section>
    ) : (
      <>
        <div className="outlet-page-heading">
          <button className="outlet-back-button" onClick={() => setScreen('home')}>
            <ArrowLeft size={18} /> Trang chủ
          </button>
          <span className="outlet-overline">ĐẶT HÀNG CHO TẠP HÓA LAN</span>
          <h1>Chọn món cần lấy</h1>
          <p>Giá bán lẻ đã áp dụng ưu đãi của Suối Việt.</p>
        </div>
        <label className="outlet-product-search">
          <Search size={19} />
          <input
            onChange={(event) => setSearch(event.target.value)}
            placeholder="Tìm nước suối, trà xanh..."
            value={search}
          />
          <button aria-label="Xóa tìm kiếm" onClick={() => setSearch('')}>
            <X size={16} />
          </button>
        </label>
        <div className="outlet-product-list">
          {filteredProducts.map((product) => {
            const quantity = cart[product.sku] ?? 0;
            const gift = product.sku === 'NS500' ? Math.floor(quantity / 10) : 0;
            return (
              <article className="outlet-product-card" key={product.sku}>
                <div className={`outlet-product-image ${product.sku.toLowerCase()}`}>
                  <ProductArt sku={product.sku} />
                </div>
                <div className="outlet-product-info">
                  <span className="outlet-product-sku">
                    {product.sku} · {product.pack}
                  </span>
                  <h2>{product.name}</h2>
                  <strong className="outlet-product-price">
                    {money(product.sellOut)} <small>/ thùng</small>
                  </strong>
                  {product.promotion && (
                    <Pill tone="promo">
                      <Ticket size={13} />
                      {product.promotion}
                    </Pill>
                  )}
                  {product.sku === 'TX330' && quantity >= 8 && (
                    <small className="outlet-gift-inline">Đã áp dụng chiết khấu 3%</small>
                  )}
                  {gift > 0 && (
                    <small className="outlet-gift-inline">
                      Tặng {gift} thùng · không tính tiền
                    </small>
                  )}
                  <div className="outlet-product-bottom">
                    <small>
                      {product.sku === 'NS500'
                        ? 'Thùng 24 chai'
                        : product.sku === 'NS150'
                          ? 'Thùng 12 chai'
                          : 'Thùng 24 chai'}
                    </small>
                    <QuantityStepper
                      label={`số lượng ${product.sku}`}
                      onChange={(value) => setQuantity(product.sku, value)}
                      value={quantity}
                    />
                  </div>
                </div>
              </article>
            );
          })}
          {filteredProducts.length === 0 && (
            <div className="outlet-empty-state">
              <Search size={24} />
              <strong>Chưa tìm thấy sản phẩm</strong>
              <span>Thử tìm theo tên hoặc mã sản phẩm nhé.</span>
            </div>
          )}
        </div>
        <div className="outlet-sticky-cart">
          {cartQty > 0 && (
            <div className="outlet-cart-details">
              <span>
                {cartQty} thùng{cartGiftQty > 0 ? ` · tặng ${cartGiftQty} thùng` : ''}
              </span>
              <strong>{money(cartSubtotal)}</strong>
            </div>
          )}
          <button className="outlet-button green full" disabled={cartQty === 0} onClick={sendOrder}>
            <ShoppingCart size={19} /> Gửi đơn cho NPP{' '}
            <span>
              {cartQty > 0 ? `${cartQty} thùng · ${money(cartSubtotal)}` : 'Chọn sản phẩm trước'}
            </span>
            <ArrowRight size={18} />
          </button>
        </div>
      </>
    );

  const renderOrders = () => (
    <>
      <div className="outlet-page-heading">
        <span className="outlet-overline">TẠP HÓA LAN</span>
        <h1>Đơn của tôi</h1>
        <p>Đơn hàng và lượng nhận được luôn được ghi riêng.</p>
      </div>
      <div className="outlet-order-list">
        {orders.map((order) => (
          <article className="outlet-order-card" key={order.id}>
            <div className="outlet-order-card-head">
              <div>
                <span className="outlet-order-id">{order.id}</span>
                <small>{order.createdAt}</small>
              </div>
              <Pill
                tone={
                  order.status.includes('Chờ')
                    ? 'waiting'
                    : order.status.includes('thiếu') || order.status.includes('hỏng')
                      ? 'warn'
                      : 'soft-green'
                }
              >
                {order.status}
              </Pill>
            </div>
            <OrderStepper order={order} />
            <div className="outlet-order-counts">
              <span>
                ĐÃ ĐẶT<strong>{order.ordered}</strong>
              </span>
              <span>
                NPP XÁC NHẬN<strong>{order.accepted}</strong>
              </span>
              <span>
                NPP THỰC XUẤT<strong>{order.shipped}</strong>
              </span>
              <span>
                TÔI ĐÃ NHẬN<strong>{order.received}</strong>
              </span>
            </div>
            <div className="outlet-order-card-footer">
              <span>{order.lines.map((line) => `${line.quantity} × ${line.sku}`).join(' · ')}</span>
              <button className="outlet-text-link" onClick={() => setSelectedOrder(order)}>
                Chi tiết <ChevronRight size={15} />
              </button>
            </div>
            {!order.receiptConfirmed && order.shipped > 0 && (
              <button
                className="outlet-button outline full outlet-receive-cta"
                onClick={() => openReceive(order)}
              >
                <CheckCircle2 size={18} /> XÁC NHẬN NHẬN HÀNG
              </button>
            )}
            {order.status.startsWith('Chờ') && (
              <p className="outlet-order-note">
                <Clock3 size={14} /> {order.note}
              </p>
            )}
            {(order.status.includes('thiếu') || order.status.includes('hỏng')) && (
              <p className="outlet-order-note warn">
                <Truck size={14} /> {order.note}
              </p>
            )}
          </article>
        ))}
        {orders.length === 0 && (
          <div className="outlet-empty-state">
            <Package size={24} />
            <strong>Chưa có đơn hàng</strong>
            <span>Đơn bạn đặt sẽ hiển thị ở đây.</span>
            <button className="outlet-button green" onClick={() => setScreen('shop')}>
              Đặt hàng <ArrowRight size={16} />
            </button>
          </div>
        )}
      </div>
      <div className="outlet-order-reminder">
        <ShieldCheck size={17} />
        <span>Không tự xác nhận nhận đủ. Chị Lan xác nhận khi đã kiểm hàng thực tế.</span>
      </div>
    </>
  );

  const renderFeedback = () => (
    <>
      <div className="outlet-page-heading">
        <span className="outlet-overline">TẠP HÓA LAN · HỖ TRỢ</span>
        <h1>Phản ánh & hỗ trợ</h1>
        <p>Theo dõi phương án xử lý và ngày giao dự kiến.</p>
      </div>
      <section className="outlet-feedback-form">
        <div className="outlet-section-heading">
          <h2>Gửi phản ánh</h2>
          <span className="outlet-form-step">2 phút</span>
        </div>
        <label>
          Nội dung phản ánh
          <textarea
            onChange={(event) => setTicketTitle(event.target.value)}
            placeholder="Ví dụ: giao thiếu, hàng bị hỏng..."
            rows={3}
            value={ticketTitle}
          />
        </label>
        <label>
          Gắn với đơn hàng
          <select onChange={(event) => setTicketOrderId(event.target.value)} value={ticketOrderId}>
            <option value="">Chọn đơn hàng (nếu có)</option>
            {orders.map((order) => (
              <option key={order.id} value={order.id}>
                {order.id}
              </option>
            ))}
          </select>
        </label>
        <button className="outlet-photo-upload">
          <ImagePlus size={19} />
          <span>{photoName || 'Thêm ảnh nếu cần'}</span>
          <input
            accept="image/*"
            capture="environment"
            onChange={(event) => selectPhoto(event.target.files?.[0])}
            type="file"
          />
        </button>
        <button className="outlet-button green full" onClick={submitFeedback}>
          <Send size={17} /> Gửi phản ánh
        </button>
      </section>
      <section className="outlet-feedback-list">
        <div className="outlet-section-heading">
          <h2>Phản ánh của cửa hàng</h2>
          <Pill tone="soft-green">{feedback.length} phiếu</Pill>
        </div>
        {feedback.map((item) => (
          <article className="outlet-feedback-card" key={item.id}>
            <div className="outlet-feedback-top">
              <span className="outlet-feedback-id">
                {item.id} · {item.orderId}
              </span>
              <Pill tone={item.status.includes('Đang') ? 'waiting' : 'soft-green'}>
                {item.status}
              </Pill>
            </div>
            <h3>{item.title}</h3>
            <div className="outlet-resolution">
              <span className="outlet-resolution-icon">
                <Truck size={16} />
              </span>
              <span>
                <small>Phương án xử lý</small>
                <strong>{item.resolution}</strong>
              </span>
            </div>
            <div className="outlet-feedback-meta">
              <span>
                <CalendarDays size={14} /> Dự kiến {item.expected}
              </span>
              <span>
                <UserRound size={14} /> {item.assignee}
              </span>
              {item.attachment && (
                <span>
                  <ImagePlus size={14} /> Ảnh: {item.attachment}
                </span>
              )}
            </div>
            {item.attachmentPreview && (
              <img
                alt={`Ảnh đính kèm ${item.attachment ?? ''}`}
                className="outlet-feedback-attachment"
                src={item.attachmentPreview}
              />
            )}
          </article>
        ))}
        {feedback.length === 0 && (
          <div className="outlet-empty-state">
            <Heart size={24} />
            <strong>Chưa có phản ánh</strong>
            <span>Nếu cần hỗ trợ, gửi phản ánh để NPP gọi lại.</span>
          </div>
        )}
      </section>
    </>
  );

  const renderAccount = () => (
    <>
      <div className="outlet-page-heading">
        <span className="outlet-overline">THÔNG TIN CỬA HÀNG</span>
        <h1>Tài khoản</h1>
        <p>Cập nhật thông tin để giao hàng đúng địa chỉ.</p>
      </div>
      <section className="outlet-account-profile">
        <span className="outlet-account-avatar">{role === 'owner' ? 'L' : 'NV'}</span>
        <span>
          <strong>{currentUser.name}</strong>
          <small>{role === 'owner' ? 'Chủ cửa hàng' : 'Nhân viên cửa hàng'} · Tạp hóa Lan</small>
        </span>
        <Pill tone="soft-green">Đang hoạt động</Pill>
      </section>
      <section className="outlet-account-card">
        <div className="outlet-section-heading">
          <h2>Thông tin nhận hàng</h2>
          <button
            className="outlet-text-link"
            onClick={() => notify('Thông tin cửa hàng đã sẵn sàng chỉnh sửa.')}
          >
            Sửa
          </button>
        </div>
        <div className="outlet-account-row">
          <span>
            <Store size={17} /> Tên cửa hàng
          </span>
          <strong>Tạp hóa Lan</strong>
        </div>
        <div className="outlet-account-row">
          <span>
            <MapPin size={17} /> Địa chỉ giao hàng
          </span>
          <strong>{shop.address}</strong>
        </div>
        <div className="outlet-account-row">
          <span>
            <Phone size={17} /> Số điện thoại
          </span>
          <strong>0903 123 456</strong>
        </div>
        <div className="outlet-account-row">
          <span>
            <UserRound size={17} /> Người nhận
          </span>
          <strong>{people.storeOwner.name}</strong>
        </div>
      </section>
      <section className="outlet-account-card outlet-npp-account">
        <div className="outlet-section-heading">
          <h2>NPP giao hàng</h2>
          <Pill tone="blue">Đang kết nối</Pill>
        </div>
        <div className="outlet-account-row">
          <span>
            <Truck size={17} /> Nhà phân phối
          </span>
          <strong>Minh Phát · Q.1</strong>
        </div>
        <div className="outlet-account-row">
          <span>
            <Phone size={17} /> Liên hệ khi cần
          </span>
          <a href="tel:+84900000001">
            Gọi Minh Phát <Phone size={14} />
          </a>
        </div>
        <div className="outlet-private-note">
          <ShieldCheck size={16} /> Cổng này chỉ hiển thị giá bán cho cửa hàng. Không hiển thị giá
          nhập của NPP hay công nợ với nhà sản xuất.
        </div>
      </section>
      {role === 'owner' && (
        <section className="outlet-account-card">
          <div className="outlet-section-heading">
            <div>
              <h2>Nhân viên cửa hàng</h2>
              <small>Chị Lan có thể mời hoặc khóa tài khoản</small>
            </div>
            <Pill tone="blue">{employees.length} người</Pill>
          </div>
          <div className="outlet-employee-list">
            {employees.map((employee) => (
              <div className="outlet-employee" key={employee.name}>
                <span className="outlet-employee-avatar">{employee.name.slice(0, 1)}</span>
                <span>
                  <strong>{employee.name}</strong>
                  <small>{employee.role}</small>
                </span>
                <button
                  className={employee.active ? 'outlet-lock-button' : 'outlet-unlock-button'}
                  onClick={() => {
                    setEmployees((current) =>
                      current.map((item) =>
                        item.name === employee.name ? { ...item, active: !item.active } : item,
                      ),
                    );
                    notify(
                      employee.active
                        ? 'Đã khóa tài khoản nhân viên.'
                        : 'Đã mở lại tài khoản nhân viên.',
                    );
                  }}
                >
                  {employee.active ? 'Khóa' : 'Mở lại'}
                </button>
              </div>
            ))}
          </div>
          <div className="outlet-invite-row">
            <input
              onChange={(event) => setEmployeeName(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter') addEmployee();
              }}
              placeholder="Tên nhân viên mới"
              value={employeeName}
            />
            <button className="outlet-button green" onClick={addEmployee}>
              <Plus size={17} /> Mời
            </button>
          </div>
        </section>
      )}
      <button
        className="outlet-role-switch"
        onClick={() => {
          setRole((current) => (current === 'owner' ? 'staff' : 'owner'));
          notify(
            role === 'owner'
              ? 'Đang xem với vai trò nhân viên cửa hàng.'
              : 'Đang xem với vai trò chủ cửa hàng.',
          );
        }}
      >
        <Users size={17} /> Xem thử giao diện {role === 'owner' ? 'nhân viên' : 'chủ cửa hàng'}{' '}
        <ChevronRight size={16} />
      </button>
      <button
        className="outlet-help-link"
        onClick={() => notify('Nhân viên NPP Minh Phát sẽ hỗ trợ cửa hàng.')}
      >
        <CircleHelp size={17} /> Cần trợ giúp? Gọi NPP Minh Phát
      </button>
    </>
  );

  const renderScreen = () => {
    switch (screen) {
      case 'home':
        return renderHome();
      case 'shop':
        return renderShop();
      case 'orders':
        return renderOrders();
      case 'feedback':
        return renderFeedback();
      case 'account':
        return renderAccount();
    }
  };

  return (
    <div className="outlet-app">
      <aside className={`outlet-sidebar${drawerOpen ? ' open' : ''}`}>
        <div className="outlet-brand">
          <span className="outlet-brand-mark">S</span>
          <span>
            <strong>SYNERA</strong>
            <small>TẠP HÓA LAN</small>
          </span>
          <button
            aria-label="Đóng menu"
            className="outlet-sidebar-close"
            onClick={() => setDrawerOpen(false)}
          >
            <X size={19} />
          </button>
        </div>
        <div className="outlet-store-switch">
          <span className="outlet-store-avatar">L</span>
          <span>
            <strong>Tạp hóa Lan</strong>
            <small>Q.3 · TP. Hồ Chí Minh</small>
          </span>
          <ChevronDown size={16} />
        </div>
        <nav className="outlet-side-nav">
          {navItems.map((item) => {
            const Icon = item.icon;
            return (
              <button
                className={screen === item.id ? 'active' : ''}
                key={item.id}
                onClick={() => {
                  setScreen(item.id);
                  setDrawerOpen(false);
                }}
              >
                <Icon size={20} />
                <span>{item.label}</span>
              </button>
            );
          })}
        </nav>
        <div className="outlet-sidebar-footer">
          <span className="outlet-user-dot">{currentUser.name.slice(0, 1)}</span>
          <span>
            <strong>{currentUser.name}</strong>
            <small>{role === 'owner' ? 'Chủ cửa hàng' : 'Nhân viên cửa hàng'}</small>
          </span>
          <button
            aria-label="Mở tài khoản"
            onClick={() => {
              setScreen('account');
              setDrawerOpen(false);
            }}
          >
            <ChevronRight size={17} />
          </button>
        </div>
      </aside>
      {drawerOpen && (
        <button
          className="outlet-sidebar-backdrop"
          aria-label="Đóng menu"
          onClick={() => setDrawerOpen(false)}
        />
      )}
      <div className="outlet-main">
        <header className="outlet-topbar">
          <button
            className="outlet-menu-button"
            aria-label="Mở menu"
            onClick={() => setDrawerOpen(true)}
          >
            <MenuIcon />
          </button>
          <div className="outlet-topbar-brand">
            <span className="outlet-brand-mark">S</span>
            <span>
              <strong>SYNERA</strong>
              <small>TẠP HÓA LAN</small>
            </span>
          </div>
          <div className="outlet-topbar-spacer" />
          <a className="outlet-header-call" href="tel:+84900000000">
            <Phone size={17} />
            <span>Gọi Sales</span>
          </a>
          <button
            aria-label="Thông báo"
            className="outlet-header-icon"
            onClick={() => notify('Chưa có thông báo mới.')}
          >
            <Bell size={19} />
          </button>
          <button
            aria-label="Mở tài khoản"
            className="outlet-header-avatar"
            onClick={() => setScreen('account')}
          >
            {currentUser.name.slice(0, 1)}
          </button>
        </header>
        <main
          className={`outlet-content${screen === 'shop' && !confirmation ? ' has-sticky-cart' : ''}`}
        >
          {renderScreen()}
        </main>
      </div>
      <nav className="outlet-bottom-nav" aria-label="Điều hướng chính">
        {navItems.map((item) => {
          const Icon = item.icon;
          return (
            <button
              className={screen === item.id ? 'active' : ''}
              key={item.id}
              onClick={() => setScreen(item.id)}
            >
              <Icon size={21} />
              <span>{item.label}</span>
            </button>
          );
        })}
      </nav>
      {toast && (
        <div className="outlet-toast" role="status">
          <CheckCircle2 size={18} />
          {toast}
          <button aria-label="Đóng thông báo" onClick={() => setToast('')}>
            <X size={15} />
          </button>
        </div>
      )}
      {selectedOrder && (
        <div className="outlet-modal-backdrop" onMouseDown={() => setSelectedOrder(null)}>
          <section className="outlet-order-drawer" onMouseDown={(event) => event.stopPropagation()}>
            <div className="outlet-drawer-header">
              <div>
                <span className="outlet-overline">CHI TIẾT ĐƠN HÀNG</span>
                <h2>{selectedOrder.id}</h2>
              </div>
              <button
                aria-label="Đóng chi tiết"
                className="outlet-close-button"
                onClick={() => setSelectedOrder(null)}
              >
                <X size={20} />
              </button>
            </div>
            <div className="outlet-drawer-body">
              <Pill
                tone={
                  selectedOrder.status.includes('Chờ')
                    ? 'waiting'
                    : selectedOrder.status.includes('thiếu')
                      ? 'warn'
                      : 'soft-green'
                }
              >
                {selectedOrder.status}
              </Pill>
              <OrderStepper order={selectedOrder} />
              <div className="outlet-drawer-qty">
                <span>
                  Đã đặt<strong>{selectedOrder.ordered} thùng</strong>
                </span>
                <span>
                  NPP xác nhận<strong>{selectedOrder.accepted} thùng</strong>
                </span>
                <span>
                  NPP thực xuất<strong>{selectedOrder.shipped} thùng</strong>
                </span>
                <span>
                  Tôi đã nhận<strong>{selectedOrder.received} thùng</strong>
                </span>
              </div>
              <h3>Sản phẩm trong đơn</h3>
              {selectedOrder.lines.map((line) => (
                <div className="outlet-drawer-line" key={line.sku}>
                  <span>
                    <strong>{line.name}</strong>
                    <small>{line.sku}</small>
                  </span>
                  <b>{line.quantity} thùng</b>
                </div>
              ))}
              {selectedOrder.freeGoods?.map((line) => (
                <p className="outlet-gift-detail" key={line.sku}>
                  <Ticket size={15} /> Tặng {line.quantity} thùng {line.name} · không tính tiền
                </p>
              ))}
              <p className="outlet-drawer-note">{selectedOrder.note}</p>
              {!selectedOrder.receiptConfirmed && selectedOrder.shipped > 0 && (
                <button
                  className="outlet-button green full"
                  onClick={() => {
                    openReceive(selectedOrder);
                    setSelectedOrder(null);
                  }}
                >
                  <CheckCircle2 size={18} /> XÁC NHẬN NHẬN HÀNG
                </button>
              )}
              <button
                className="outlet-button outline full"
                onClick={() => openFeedbackForOrder(selectedOrder.id)}
              >
                Tạo phản ánh từ đơn hàng
              </button>
            </div>
          </section>
        </div>
      )}
      {receiveOrder && (
        <div className="outlet-modal-backdrop" onMouseDown={() => setReceiveOrder(null)}>
          <section
            aria-labelledby="outlet-receive-title"
            aria-modal="true"
            className="outlet-receive-dialog"
            onMouseDown={(event) => event.stopPropagation()}
            role="dialog"
          >
            <div className="outlet-drawer-header">
              <div>
                <span className="outlet-overline">ĐƠN {receiveOrder.id}</span>
                <h2 id="outlet-receive-title">Chị nhận hàng thế nào?</h2>
              </div>
              <button
                aria-label="Đóng"
                className="outlet-close-button"
                onClick={() => setReceiveOrder(null)}
              >
                <X size={20} />
              </button>
            </div>
            <p className="outlet-receive-copy">
              NPP xác nhận giao {receiveOrder.shipped || receiveOrder.accepted} thùng. Chị kiểm hàng
              rồi chọn nhé.
            </p>
            <div className="outlet-condition-buttons">
              <button
                className={
                  receiveCondition === 'full' ? 'selected full-condition' : 'full-condition'
                }
                onClick={() => setReceiveCondition('full')}
              >
                <CheckCircle2 size={21} />
                <span>
                  <strong>Đủ hàng</strong>
                  <small>Đã nhận đủ, hàng tốt</small>
                </span>
              </button>
              <button
                className={
                  receiveCondition === 'short' ? 'selected short-condition' : 'short-condition'
                }
                onClick={() => setReceiveCondition('short')}
              >
                <Package size={21} />
                <span>
                  <strong>Bị thiếu</strong>
                  <small>Thiếu thùng so với đơn</small>
                </span>
              </button>
              <button
                className={
                  receiveCondition === 'damaged' ? 'selected damage-condition' : 'damage-condition'
                }
                onClick={() => setReceiveCondition('damaged')}
              >
                <AlertMark />
                <span>
                  <strong>Hàng bị hỏng</strong>
                  <small>Báo để NPP đổi / xử lý</small>
                </span>
              </button>
            </div>
            {receiveCondition && receiveCondition !== 'full' && (
              <div className="outlet-issue-details">
                <label>
                  Số thùng {receiveCondition === 'short' ? 'bị thiếu' : 'bị hỏng'}
                  <QuantityStepper
                    label="số thùng"
                    max={receiveOrder.shipped || receiveOrder.accepted}
                    min={1}
                    onChange={setIssueQty}
                    value={issueQty}
                  />
                </label>
                <label className="outlet-photo-upload">
                  <ImagePlus size={20} />
                  <span>{photoName || 'Chụp ảnh hàng hóa (bắt buộc)'}</span>
                  <input
                    accept="image/*"
                    capture="environment"
                    onChange={(event) => selectPhoto(event.target.files?.[0])}
                    type="file"
                  />
                </label>
              </div>
            )}
            <div className="outlet-receive-note">
              <ShieldCheck size={17} /> Chỉ xác nhận đúng với số hàng chị đã kiểm thực tế.
            </div>
            <button
              className="outlet-button green full"
              disabled={
                !receiveCondition ||
                (receiveCondition !== 'full' &&
                  (!photoName ||
                    !photoPreview ||
                    issueQty < 1 ||
                    issueQty > (receiveOrder?.shipped || receiveOrder?.accepted || 0)))
              }
              onClick={confirmReceipt}
            >
              {receiveCondition === 'full'
                ? 'Xác nhận đã nhận đủ'
                : receiveCondition
                  ? photoName && photoPreview
                    ? 'Xác nhận & gửi phản ánh'
                    : 'Chụp ảnh để gửi phản ánh'
                  : 'Chọn tình trạng hàng'}{' '}
              <ArrowRight size={18} />
            </button>
          </section>
        </div>
      )}
    </div>
  );
}

function RotateIcon() {
  return <span className="outlet-rotate-icon">↻</span>;
}

function MenuIcon() {
  return (
    <span className="outlet-menu-lines">
      <i />
      <i />
      <i />
    </span>
  );
}

function AlertMark() {
  return <span className="outlet-alert-mark">!</span>;
}
