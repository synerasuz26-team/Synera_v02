import { useEffect, useMemo, useState } from 'react';
import {
  AlertCircle,
  ArrowDownRight,
  ArrowRight,
  ArrowUpRight,
  Bell,
  Boxes,
  CalendarDays,
  Check,
  CheckCheck,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  CircleCheck,
  ClipboardList,
  Clock3,
  FileSpreadsheet,
  HandCoins,
  Headphones,
  LayoutDashboard,
  Menu,
  Minus,
  Moon,
  Package,
  Plus,
  RotateCcw,
  Search,
  Send,
  Settings,
  ShieldCheck,
  ShoppingCart,
  Sun,
  Ticket,
  Truck,
  Users,
  Wallet,
  X,
  type LucideIcon,
} from 'lucide-react';
import {
  distributors,
  orders as demoOrders,
  overview,
  people,
  products as demoProducts,
  tickets as demoTickets,
} from '../../packages/mock-data';

type Role = 'lead' | 'warehouse' | 'accounting';
type Screen =
  'overview' | 'place' | 'fulfill' | 'inventory' | 'policies' | 'requests' | 'planning' | 'users';
type OrderType = 'sell-in' | 'sell-out';
type OrderRow = {
  id: string;
  type: OrderType;
  customer: string;
  owner: string;
  createdAt: string;
  lines: { sku: string; name: string; quantity: number }[];
  ordered: number;
  confirmed: number;
  shipped: number;
  received: number;
  status: string;
  creditRemaining: number | null;
  note: string;
  due?: string;
  freeGoods?: { sku: string; name: string; quantity: number }[];
};
type TicketRow = {
  id: string;
  title: string;
  category: string;
  status: string;
  due: string;
  owner: string;
  related: string;
  updatedAt: string;
};

const distributor = distributors.find((item) => item.name === 'Minh Phát')!;
const roles: { id: Role; label: string; name: string; initials: string }[] = [
  { id: 'lead', label: 'Đầu mối NPP', name: people.distributor.name, initials: 'M' },
  { id: 'warehouse', label: 'Nhân viên đơn / kho', name: 'Phạm Đức', initials: 'Đ' },
  { id: 'accounting', label: 'Kế toán NPP', name: 'Nguyễn Thu', initials: 'T' },
];
const navItems: { id: Screen; label: string; icon: LucideIcon; roles: Role[] }[] = [
  {
    id: 'overview',
    label: 'Tổng quan',
    icon: LayoutDashboard,
    roles: ['lead', 'warehouse', 'accounting'],
  },
  {
    id: 'place',
    label: 'Đặt hàng từ NSX',
    icon: ShoppingCart,
    roles: ['lead', 'warehouse', 'accounting'],
  },
  {
    id: 'fulfill',
    label: 'Đáp ứng đơn điểm bán',
    icon: ClipboardList,
    roles: ['lead', 'warehouse'],
  },
  {
    id: 'inventory',
    label: 'Tồn và sell-out',
    icon: Boxes,
    roles: ['lead', 'warehouse', 'accounting'],
  },
  { id: 'policies', label: 'Chính sách & công nợ', icon: HandCoins, roles: ['lead', 'accounting'] },
  {
    id: 'requests',
    label: 'Yêu cầu tới NSX',
    icon: Headphones,
    roles: ['lead', 'warehouse', 'accounting'],
  },
  { id: 'planning', label: 'Kế hoạch nhập', icon: CalendarDays, roles: ['lead', 'warehouse'] },
  { id: 'users', label: 'Người dùng', icon: Users, roles: ['lead'] },
];
const fmt = new Intl.NumberFormat('vi-VN');
const money = (value: number) => `${fmt.format(value)}đ`;
const stamp = () =>
  new Intl.DateTimeFormat('vi-VN', { dateStyle: 'short', timeStyle: 'short' }).format(new Date());
const initialOrders: OrderRow[] = demoOrders
  .filter(
    (order) => order.customer.includes('Minh Phát') || ['SO-24081', 'SO-24076'].includes(order.id),
  )
  .map((order) => ({
    ...order,
    type: order.type as OrderType,
    lines:
      order.lines.length > 0
        ? order.lines
        : [{ sku: 'NS500', name: 'Nước suối 500ml', quantity: order.ordered }],
    due: order.id === 'SO-24076' ? '13/11/2026' : undefined,
  }));
const initialTickets: TicketRow[] = demoTickets
  .filter((ticket) => ticket.id === 'TK-331' || ticket.id === 'TK-328')
  .map((ticket) => ({
    id: ticket.id,
    title: ticket.title,
    category: ticket.kind === 'POSM' ? 'Đề xuất Trade marketing' : 'Giao thiếu / hỏng',
    status: ticket.id === 'TK-328' ? 'Chờ Trade NSX nhận' : ticket.status,
    due: ticket.due,
    owner: ticket.assignee,
    related: ticket.customer,
    updatedAt: '12/11/2026 · 09:20',
  }));

function Badge({ children, tone = 'neutral' }: { children: React.ReactNode; tone?: string }) {
  return <span className={`npp-badge ${tone}`}>{children}</span>;
}

function QuantityControl({
  value,
  onChange,
  label,
}: {
  value: number;
  onChange: (value: number) => void;
  label: string;
}) {
  return (
    <div className="npp-quantity" aria-label={label}>
      <button
        aria-label={`Giảm ${label}`}
        onClick={() => onChange(Math.max(0, value - 1))}
        type="button"
      >
        <Minus size={14} />
      </button>
      <input
        aria-label={label}
        min={0}
        onChange={(event) => onChange(Math.max(0, Number(event.target.value) || 0))}
        type="number"
        value={value}
      />
      <button aria-label={`Tăng ${label}`} onClick={() => onChange(value + 1)} type="button">
        <Plus size={14} />
      </button>
    </div>
  );
}

function Metric({
  label,
  value,
  detail,
  icon: Icon,
  tone,
  action,
  progress,
}: {
  label: string;
  value: string;
  detail: string;
  icon: LucideIcon;
  tone: string;
  action: () => void;
  progress?: number;
}) {
  return (
    <button className="npp-metric" onClick={action}>
      <span className={`npp-metric-icon ${tone}`}>
        <Icon size={18} />
      </span>
      <span className="npp-metric-copy">
        <span className="npp-eyebrow">{label}</span>
        <strong>{value}</strong>
        <small>{detail}</small>
        {progress !== undefined && (
          <span className="npp-progress">
            <i style={{ width: `${progress}%` }} />
          </span>
        )}
      </span>
      <ChevronRight className="npp-metric-arrow" size={16} />
    </button>
  );
}

function Stepper({ order }: { order: OrderRow }) {
  const isShortSellOut = order.type === 'sell-out' && order.status.includes('thiếu');
  const steps =
    order.type === 'sell-in'
      ? ['Chờ NSX duyệt', 'Đã duyệt', 'Đang giao', 'NPP xác nhận nhận']
      : [
          'Chờ NPP xác nhận',
          'Đã xác nhận',
          'Đã xuất kho',
          isShortSellOut ? 'Điểm bán nhận một phần' : 'Điểm bán nhận đủ',
        ];
  const active =
    order.type === 'sell-in'
      ? order.status.includes('Giữ') || order.status.includes('Chờ')
        ? 0
        : order.status.includes('Đang giao')
          ? 2
          : 3
      : order.status.includes('Chờ') || order.status.includes('Từ chối')
        ? 0
        : isShortSellOut || order.status.includes('nhận đủ')
          ? 3
          : order.status.includes('Đã xuất kho')
            ? 2
            : 1;
  return (
    <div className="npp-stepper" aria-label={`Trạng thái ${order.status}`}>
      {steps.map((step, index) => (
        <span className={index <= active ? 'active' : ''} key={step}>
          <i>{index < active ? <Check size={11} /> : index + 1}</i>
          <small>{step}</small>
        </span>
      ))}
    </div>
  );
}

export default function PortalNpp() {
  const [role, setRole] = useState<Role>('lead');
  const [screen, setScreen] = useState<Screen>('overview');
  const [orders, setOrders] = useState<OrderRow[]>(initialOrders);
  const [tickets, setTickets] = useState<TicketRow[]>(initialTickets);
  const [cart, setCart] = useState<Record<string, number>>({});
  const [stock, setStock] = useState<Record<string, number>>(
    Object.fromEntries(demoProducts.map((product) => [product.sku, product.stock])),
  );
  const [sellOutQty, setSellOutQty] = useState<Record<string, number>>({});
  const [selectedOrders, setSelectedOrders] = useState<string[]>([]);
  const [receiptOrder, setReceiptOrder] = useState<OrderRow | null>(null);
  const [detailsOrder, setDetailsOrder] = useState<OrderRow | null>(null);
  const [rejectOrder, setRejectOrder] = useState<OrderRow | null>(null);
  const [rejectReason, setRejectReason] = useState('');
  const [receivedQty, setReceivedQty] = useState(0);
  const [damagedQty, setDamagedQty] = useState(0);
  const [returnedQty, setReturnedQty] = useState(0);
  const [selectedMonth, setSelectedMonth] = useState('Tháng 11/2026');
  const [toast, setToast] = useState('');
  const [dark, setDark] = useState(false);
  const [collapsed, setCollapsed] = useState(false);
  const [mobileMenu, setMobileMenu] = useState(false);
  const [globalSearch, setGlobalSearch] = useState('');
  const [searchOpen, setSearchOpen] = useState(false);
  const [loading, setLoading] = useState(true);
  const [policySeen, setPolicySeen] = useState<Record<string, string>>({});
  const [paymentAmount, setPaymentAmount] = useState('');
  const [paymentReference, setPaymentReference] = useState('');
  const [paymentDeclared, setPaymentDeclared] = useState(false);
  const [ticketKind, setTicketKind] = useState('Giao thiếu / hỏng');
  const [ticketTitle, setTicketTitle] = useState('');
  const [ticketRelated, setTicketRelated] = useState('SO-24076');
  const [ticketFileName, setTicketFileName] = useState('');
  const [importPreview, setImportPreview] = useState<{
    name: string;
    rows: { sku: string; stock: number }[];
    errors: string[];
  } | null>(null);
  const [invitedUsers, setInvitedUsers] = useState([
    { name: people.distributor.name, role: 'Đầu mối NPP', status: 'Đang hoạt động' },
    { name: 'Phạm Đức', role: 'Nhân viên đơn / kho', status: 'Đang hoạt động' },
    { name: 'Nguyễn Thu', role: 'Kế toán NPP', status: 'Đang hoạt động' },
  ]);
  const [inviteName, setInviteName] = useState('');
  const [inviteRole, setInviteRole] = useState('Nhân viên đơn / kho');
  const [plan, setPlan] = useState<Record<string, number>>({ NS500: 68, NS150: 34, TX330: 22 });
  const [planSent, setPlanSent] = useState(false);
  const [fulfillFilter, setFulfillFilter] = useState<'all' | 'pending' | 'short'>('all');

  const activeRole = roles.find((item) => item.id === role)!;
  const visibleNav = navItems.filter((item) => item.roles.includes(role));
  const pageTitle = navItems.find((item) => item.id === screen)?.label ?? 'Tổng quan';
  const cartTotal = demoProducts.reduce((sum, product) => sum + (cart[product.sku] ?? 0), 0);
  const remainingCredit = distributor.creditRemaining! - cartTotal;
  const filteredOrders = useMemo(
    () =>
      orders.filter((order) => {
        if (order.type !== 'sell-out') return false;
        if (fulfillFilter === 'pending') return order.status.includes('Chờ');
        if (fulfillFilter === 'short') return order.status.includes('thiếu');
        return true;
      }),
    [fulfillFilter, orders],
  );

  const notify = (message: string) => {
    setToast(`${message} · ${stamp()}`);
    window.setTimeout(() => setToast(''), 3600);
  };
  const navigate = (next: Screen) => {
    if (!navItems.find((item) => item.id === next)?.roles.includes(role)) {
      notify('Vai trò hiện tại không có quyền mở mục này');
      return;
    }
    setScreen(next);
    setMobileMenu(false);
  };
  const changeRole = (next: Role) => {
    setRole(next);
    if (!navItems.find((item) => item.id === screen)?.roles.includes(next)) setScreen('overview');
    notify(`Đang xem với vai trò ${roles.find((item) => item.id === next)?.label}`);
  };

  useEffect(() => {
    const timer = window.setTimeout(() => setLoading(false), 350);
    const onKey = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        setSearchOpen(true);
      }
      if (event.key === 'Escape') {
        setSearchOpen(false);
        setDetailsOrder(null);
        setReceiptOrder(null);
        setRejectOrder(null);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => {
      window.clearTimeout(timer);
      window.removeEventListener('keydown', onKey);
    };
  }, []);

  const createSellIn = (lines: { sku: string; name: string; quantity: number }[]) => {
    const quantity = lines.reduce((sum, line) => sum + line.quantity, 0);
    if (!quantity) {
      notify('Chọn số lượng ít nhất một SKU trước khi đặt');
      return;
    }
    const nextNumber =
      Math.max(
        1182,
        ...orders
          .filter((order) => order.type === 'sell-in')
          .map((order) => Number(order.id.slice(3))),
      ) + 1;
    const held = quantity > distributor.creditRemaining!;
    const created: OrderRow = {
      id: `SI-${nextNumber}`,
      type: 'sell-in',
      customer: 'NPP Minh Phát',
      owner: activeRole.name,
      createdAt: stamp(),
      lines,
      ordered: quantity,
      confirmed: 0,
      shipped: 0,
      received: 0,
      status: held ? 'Giữ do vượt hạn mức' : 'Chờ NSX duyệt',
      creditRemaining: Math.max(0, remainingCredit),
      note: held
        ? 'Đơn vượt hạn mức hiện tại; chờ NSX xét duyệt.'
        : 'Giá và ưu đãi tự động áp theo bảng giá hiệu lực.',
    };
    setOrders((current) => [created, ...current]);
    setCart({});
    notify(
      held
        ? `${created.id} đã gửi, đang giữ do vượt hạn mức`
        : `${created.id} đã gửi tới Suối Việt`,
    );
  };

  const reorderLast = () => {
    const previous = orders.find((order) => order.type === 'sell-in' && order.lines.length > 0);
    const lines = previous?.lines ?? [{ sku: 'NS500', name: 'Nước suối 500ml', quantity: 40 }];
    createSellIn(lines);
  };

  const confirmSellOut = (order: OrderRow) => {
    const quantity = Math.min(
      order.ordered,
      Math.max(0, sellOutQty[order.id] ?? (order.confirmed || order.ordered)),
    );
    if (!quantity) {
      notify('Nhập lượng đáp ứng trước khi xác nhận');
      return;
    }
    setOrders((current) =>
      current.map((item) =>
        item.id === order.id
          ? {
              ...item,
              confirmed: quantity,
              status:
                quantity < item.ordered
                  ? `Giao thiếu · bù ${item.due ?? 'chưa hẹn'}`
                  : 'Đã xác nhận giao',
              note: `NPP ${activeRole.name} xác nhận ${quantity}/${item.ordered} thùng · ${stamp()}`,
            }
          : item,
      ),
    );
    setSellOutQty((current) => ({ ...current, [order.id]: quantity }));
    notify(`${order.id} đã xác nhận ${quantity} thùng`);
  };

  const rejectSellOut = () => {
    if (!rejectOrder || !rejectReason.trim()) return;
    setOrders((current) =>
      current.map((order) =>
        order.id === rejectOrder.id
          ? {
              ...order,
              status: 'Từ chối · có lý do',
              note: `Từ chối bởi ${activeRole.name} · ${rejectReason.trim()} · ${stamp()}`,
            }
          : order,
      ),
    );
    notify(`${rejectOrder.id} đã từ chối, lý do được lưu`);
    setRejectOrder(null);
    setRejectReason('');
  };

  const submitReceipt = () => {
    if (!receiptOrder) return;
    const missing = Math.max(0, receiptOrder.shipped - receivedQty);
    setOrders((current) =>
      current.map((order) =>
        order.id === receiptOrder.id
          ? {
              ...order,
              received: receivedQty,
              status: missing > 0 ? `Nhận thiếu ${missing} thùng` : 'NPP xác nhận nhận',
              note: `Thực nhận ${receivedQty} · thiếu ${missing} · hỏng ${damagedQty} · hoàn ${returnedQty} · ${activeRole.name} · ${stamp()}`,
            }
          : order,
      ),
    );
    notify(`Đã ghi nhận thực nhận đơn ${receiptOrder.id}`);
    setReceiptOrder(null);
  };

  const createTicket = () => {
    if (!ticketTitle.trim()) {
      notify('Nhập tiêu đề yêu cầu để tiếp tục');
      return;
    }
    const item: TicketRow = {
      id: `TK-${332 + tickets.length - 2}`,
      title: ticketTitle.trim(),
      category: ticketKind,
      status: 'Đã gửi NSX',
      due: '13/11/2026',
      owner: activeRole.name,
      related: ticketRelated || 'Chưa gắn đơn',
      updatedAt: stamp(),
    };
    setTickets((current) => [item, ...current]);
    setTicketTitle('');
    setTicketFileName('');
    notify(`Đã gửi ${item.id} tới Suối Việt`);
  };

  const submitInvite = () => {
    if (!inviteName.trim()) {
      notify('Nhập tên nhân viên trước khi mời');
      return;
    }
    setInvitedUsers((current) => [
      ...current,
      { name: inviteName.trim(), role: inviteRole, status: 'Đã mời' },
    ]);
    setInviteName('');
    notify('Đã gửi lời mời nhân viên');
  };

  const savePayment = () => {
    if (!paymentAmount || !paymentReference.trim()) {
      notify('Nhập số tiền và mã chứng từ để khai báo');
      return;
    }
    setPaymentDeclared(true);
    notify('Đã khai báo thanh toán, chờ Suối Việt đối chiếu');
  };

  const parseInventoryCsv = async (file: File) => {
    const text = await file.text();
    const lines = text.split(/\r?\n/).filter((line) => line.trim());
    if (lines.length < 2) {
      setImportPreview({
        name: file.name,
        rows: [],
        errors: ['Tệp cần có hàng tiêu đề và ít nhất một dòng dữ liệu.'],
      });
      return;
    }
    const delimiter = lines[0].includes(';') ? ';' : ',';
    const splitLine = (line: string) =>
      line.split(delimiter).map((cell) => cell.trim().replace(/^"|"$/g, ''));
    const header = splitLine(lines[0]).map((cell) => cell.toLowerCase());
    const skuColumn = header.findIndex((cell) => cell.includes('sku') || cell.includes('mã hàng'));
    const stockColumn = header.findIndex(
      (cell) => cell.includes('tồn') || cell.includes('số lượng') || cell.includes('stock'),
    );
    if (skuColumn < 0 || stockColumn < 0) {
      setImportPreview({
        name: file.name,
        rows: [],
        errors: ['Không tìm thấy hai cột bắt buộc: SKU và Tồn kho. Hãy lưu bảng tính thành CSV.'],
      });
      return;
    }
    const rows: { sku: string; stock: number }[] = [];
    const errors: string[] = [];
    const seen = new Set<string>();
    lines.slice(1).forEach((line, index) => {
      const cells = splitLine(line);
      const sku = cells[skuColumn]?.toUpperCase() ?? '';
      const quantity = Number(cells[stockColumn]);
      if (!demoProducts.some((product) => product.sku === sku)) {
        errors.push(`Dòng ${index + 2}: SKU "${sku || '(trống)'}" không có trong danh mục.`);
      } else if (seen.has(sku)) {
        errors.push(`Dòng ${index + 2}: SKU ${sku} bị lặp.`);
      } else if (!Number.isInteger(quantity) || quantity < 0) {
        errors.push(`Dòng ${index + 2}: tồn kho cần là số nguyên không âm.`);
      } else {
        seen.add(sku);
        rows.push({ sku, stock: quantity });
      }
    });
    setImportPreview({ name: file.name, rows, errors });
  };

  const applyInventoryImport = () => {
    if (!importPreview?.rows.length) return;
    setStock((current) => ({
      ...current,
      ...Object.fromEntries(importPreview.rows.map((row) => [row.sku, row.stock])),
    }));
    setImportPreview(null);
    notify('Đã cập nhật tồn kho từ CSV; các dòng lỗi không được nhập');
  };

  const renderOverview = () => (
    <>
      <div className="npp-page-heading">
        <div>
          <span className="npp-eyebrow">VIỆC VỚI SUỐI VIỆT</span>
          <h1>Tổng quan</h1>
          <p>Xin chào {people.distributor.name}, đây là những việc cần xử lý tại Minh Phát.</p>
        </div>
        <label className="npp-month-picker">
          <CalendarDays size={16} />
          <select onChange={(event) => setSelectedMonth(event.target.value)} value={selectedMonth}>
            <option>Tháng 11/2026</option>
            <option>Tháng 10/2026</option>
            <option>Tháng 09/2026</option>
          </select>
          <ChevronDown size={14} />
        </label>
      </div>
      <div className="npp-metric-grid">
        <Metric
          action={() => navigate('fulfill')}
          detail="Cần xác nhận lượng giao"
          icon={ClipboardList}
          label="ĐƠN ĐIỂM BÁN CHỜ"
          tone="blue"
          value="1 đơn"
        />
        <Metric
          action={() => navigate('inventory')}
          detail="NS150, TX330"
          icon={Boxes}
          label="SKU DƯỚI 15 THÙNG"
          tone="orange"
          value="2 SKU"
        />
        <Metric
          action={() => navigate(role === 'warehouse' ? 'place' : 'policies')}
          detail={
            role === 'warehouse'
              ? 'Hóa đơn chi tiết chỉ dành cho Kế toán'
              : 'Đối chiếu chứng từ NSX'
          }
          icon={Wallet}
          label="CÔNG NỢ THEO DÕI"
          tone="teal"
          value={role === 'warehouse' ? '••••••' : '186 triệu'}
        />
        <Metric
          action={() => navigate('planning')}
          detail="420/800 thùng nhập"
          icon={ArrowUpRight}
          label="MỤC TIÊU THÁNG"
          tone="green"
          value="52%"
          progress={52}
        />
      </div>
      <section className="npp-panel npp-today-panel">
        <div className="npp-panel-heading">
          <div>
            <span className="npp-eyebrow">THỨ NĂM · 12/11/2026</span>
            <h2>Việc cần làm hôm nay</h2>
          </div>
          <Badge tone="info">3 việc</Badge>
        </div>
        <div className="npp-today-list">
          {role === 'accounting' ? (
            <div className="npp-today-item">
              <span className="npp-today-icon teal">
                <HandCoins size={17} />
              </span>
              <div>
                <strong>Đối chiếu chứng từ Suối Việt</strong>
                <small>Công nợ theo dõi 186 triệu · cập nhật 12/11</small>
              </div>
              <button className="npp-button primary" onClick={() => navigate('policies')}>
                Đối chiếu <ArrowRight size={15} />
              </button>
            </div>
          ) : (
            <div className="npp-today-item">
              <span className="npp-today-icon orange">
                <ClipboardList size={17} />
              </span>
              <div>
                <strong>Xác nhận đơn SO-24081</strong>
                <small>Tạp hóa Lan · 12 thùng đang chờ lượng đáp ứng</small>
              </div>
              <button className="npp-button primary" onClick={() => navigate('fulfill')}>
                Xác nhận đơn <ArrowRight size={15} />
              </button>
            </div>
          )}
          <div className="npp-today-item">
            <span className="npp-today-icon blue">
              <Package size={17} />
            </span>
            <div>
              <strong>Bổ sung hàng sắp thấp</strong>
              <small>NS150 còn 8 thùng · TX330 còn 14 thùng</small>
            </div>
            <button className="npp-button secondary" onClick={() => navigate('place')}>
              Đặt hàng <ArrowRight size={15} />
            </button>
          </div>
          <div className="npp-today-item">
            <span className="npp-today-icon teal">
              <HandCoins size={17} />
            </span>
            <div>
              <strong>
                {role === 'warehouse' ? 'Kiểm tra hạn mức còn lại' : 'Đối chiếu công nợ tháng'}
              </strong>
              <small>
                {role === 'warehouse'
                  ? '120 thùng còn lại · không hiển thị hóa đơn chi tiết'
                  : 'Công nợ theo dõi 186 triệu · xem chứng từ mới nhất'}
              </small>
            </div>
            <button
              className="npp-button secondary"
              onClick={() => navigate(role === 'warehouse' ? 'place' : 'policies')}
            >
              {role === 'warehouse' ? 'Đặt hàng' : 'Đối chiếu'} <ArrowRight size={15} />
            </button>
          </div>
        </div>
      </section>
      <div className="npp-dashboard-grid">
        <section className="npp-panel">
          <div className="npp-panel-heading">
            <div>
              <span className="npp-eyebrow">ĐƠN HÀNG GẦN ĐÂY</span>
              <h2>Theo dõi đơn</h2>
            </div>
            <button className="npp-text-button" onClick={() => navigate('place')}>
              Tất cả đơn <ArrowRight size={14} />
            </button>
          </div>
          <div className="npp-order-mini-list">
            {orders.slice(0, 3).map((order) => (
              <button
                className="npp-order-mini"
                key={order.id}
                onClick={() => setDetailsOrder(order)}
              >
                <span className={`npp-kind ${order.type}`}>
                  {order.type === 'sell-in' ? 'SELL-IN' : 'SELL-OUT'}
                </span>
                <span>
                  <strong>{order.id}</strong>
                  <small>{order.customer}</small>
                </span>
                <span className="npp-order-mini-status">
                  {order.status}
                  <small>{order.ordered} thùng đặt</small>
                </span>
                <ChevronRight size={15} />
              </button>
            ))}
          </div>
        </section>
        <section className="npp-panel npp-credit-panel">
          <div className="npp-panel-heading">
            <div>
              <span className="npp-eyebrow">HẠN MỨC CÒN LẠI</span>
              <h2>Đặt hàng trong hạn mức</h2>
            </div>
            <span className="npp-credit-icon">
              <ShieldCheck size={19} />
            </span>
          </div>
          <strong className="npp-credit-number">
            120 <small>thùng</small>
          </strong>
          <div className="npp-credit-track">
            <i />
          </div>
          <div className="npp-inline-meta">
            <span>Đã sử dụng</span>
            <strong>Đang theo dõi cùng Suối Việt</strong>
          </div>
          <button className="npp-button primary full" onClick={() => navigate('place')}>
            Tạo đơn sell-in <ArrowRight size={15} />
          </button>
        </section>
      </div>
      <div className="npp-source-note">
        <CheckCheck size={15} /> Dữ liệu đơn, tồn và bán ra được chia sẻ với Suối Việt — không cần
        nhập lại.
      </div>
    </>
  );

  const renderPlaceOrders = () => (
    <>
      <div className="npp-page-heading">
        <div>
          <span className="npp-eyebrow">SELL-IN · NPP ĐẶT HÀNG TỪ NSX</span>
          <h1>Đặt hàng từ Suối Việt</h1>
          <p>Giá và ưu đãi được tự áp theo bảng giá hiệu lực đến {overview.priceValidThrough}.</p>
        </div>
        <button className="npp-button secondary" onClick={reorderLast}>
          <RotateCcw size={16} /> Đặt lại đơn cũ một chạm
        </button>
      </div>
      <div className="npp-order-banner">
        <div className="npp-limit-summary">
          <span className="npp-eyebrow">HẠN MỨC CÒN LẠI</span>
          <strong className={remainingCredit < 0 ? 'danger-text' : ''}>
            {fmt.format(remainingCredit)} <small>thùng</small>
          </strong>
          <span>Sau đơn hàng đang tạo · cập nhật tức thì</span>
        </div>
        <div className="npp-limit-meter">
          <span>
            <i
              style={{
                width: `${Math.min(100, Math.max(0, (remainingCredit / distributor.creditRemaining!) * 100))}%`,
              }}
            />
          </span>
          <small>{cartTotal} thùng trong giỏ · giới hạn 120 thùng</small>
        </div>
        {remainingCredit < 0 && (
          <div className="npp-inline-alert">
            <AlertCircle size={16} /> Đơn có thể bị giữ do vượt hạn mức. Có thể gửi yêu cầu NSX xem
            xét.
          </div>
        )}
      </div>
      <section className="npp-panel">
        <div className="npp-panel-heading">
          <div>
            <span className="npp-eyebrow">BẢNG GIÁ NPP MINH PHÁT</span>
            <h2>Chọn sản phẩm và số lượng</h2>
          </div>
          <Badge tone="success">Giá hiệu lực đến 30/11</Badge>
        </div>
        <div className="npp-table-wrap">
          <table className="npp-table npp-product-order-table">
            <thead>
              <tr>
                <th>Sản phẩm</th>
                <th>Tồn của mình</th>
                <th>Gợi ý bổ sung</th>
                <th>Giá sell-in</th>
                <th>Ưu đãi</th>
                <th>Số lượng đặt</th>
                <th>Tạm tính</th>
              </tr>
            </thead>
            <tbody>
              {demoProducts.map((product) => {
                const quantity = cart[product.sku] ?? 0;
                const gift = product.sku === 'NS500' ? Math.floor(quantity / 10) : 0;
                return (
                  <tr key={product.sku}>
                    <td>
                      <div className="npp-product-cell">
                        <span className="npp-product-icon">
                          <Package size={18} />
                        </span>
                        <span>
                          <strong>{product.name}</strong>
                          <small>
                            {product.sku} · {product.pack}
                          </small>
                        </span>
                      </div>
                    </td>
                    <td>
                      <strong>{stock[product.sku]} thùng</strong>
                      <small
                        className={`npp-stock-state ${product.stockHealth === 'Cần nhập' ? 'warning' : product.stockHealth === 'Sắp thấp' ? 'low' : ''}`}
                      >
                        {product.stockHealth}
                      </small>
                    </td>
                    <td>
                      <button
                        className="npp-suggestion"
                        onClick={() =>
                          setCart((current) => ({
                            ...current,
                            [product.sku]: Math.max(
                              current[product.sku] ?? 0,
                              Math.max(1, product.sales7d * 2 - stock[product.sku]),
                            ),
                          }))
                        }
                      >
                        +{Math.max(1, product.sales7d * 2 - stock[product.sku])} thùng{' '}
                        <small>theo sell-out</small>
                      </button>
                    </td>
                    <td>
                      <strong>{money(product.sellIn)}</strong>
                      <small>/ thùng</small>
                    </td>
                    <td>
                      {product.promotion ? (
                        <Badge tone="info">{product.promotion}</Badge>
                      ) : (
                        <span className="npp-muted">—</span>
                      )}
                      {gift > 0 && (
                        <small className="npp-gift-note">Tặng {gift} thùng · không tính tiền</small>
                      )}
                    </td>
                    <td>
                      <QuantityControl
                        label={`số lượng ${product.sku}`}
                        onChange={(value) =>
                          setCart((current) => ({ ...current, [product.sku]: value }))
                        }
                        value={quantity}
                      />
                    </td>
                    <td>
                      <strong>{money(quantity * product.sellIn)}</strong>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
        <div className="npp-cart-footer">
          <span>{cartTotal} thùng đặt · hàng tặng không tính vào số thu</span>
          <strong>
            {money(
              demoProducts.reduce(
                (sum, product) => sum + (cart[product.sku] ?? 0) * product.sellIn,
                0,
              ),
            )}
          </strong>
          <button
            className="npp-button primary"
            disabled={cartTotal === 0}
            onClick={() =>
              createSellIn(
                demoProducts
                  .filter((product) => (cart[product.sku] ?? 0) > 0)
                  .map((product) => ({
                    sku: product.sku,
                    name: product.name,
                    quantity: cart[product.sku],
                  })),
              )
            }
          >
            <ShoppingCart size={16} /> Gửi đơn sell-in
          </button>
        </div>
      </section>
      <section className="npp-panel">
        <div className="npp-panel-heading">
          <div>
            <span className="npp-eyebrow">ĐƠN ĐÃ ĐẶT</span>
            <h2>Trạng thái giao hàng</h2>
          </div>
          <Badge tone="info">Sell-in</Badge>
        </div>
        <div className="npp-orders-list">
          {orders
            .filter((order) => order.type === 'sell-in')
            .map((order) => (
              <article className="npp-order-card" key={order.id}>
                <div className="npp-order-card-head">
                  <div>
                    <span className="npp-kind sell-in">SELL-IN</span>
                    <strong>{order.id}</strong>
                    <small>
                      {order.createdAt} · {order.ordered} thùng đặt
                    </small>
                  </div>
                  <Badge
                    tone={
                      order.status.includes('Giữ')
                        ? 'warning'
                        : order.status.includes('Nhận thiếu')
                          ? 'warning'
                          : 'info'
                    }
                  >
                    {order.status}
                  </Badge>
                  <button
                    className="npp-icon-button"
                    aria-label={`Chi tiết ${order.id}`}
                    onClick={() => setDetailsOrder(order)}
                  >
                    <ArrowUpRight size={16} />
                  </button>
                </div>
                <Stepper order={order} />
                <div className="npp-four-counts">
                  <span>
                    ĐẶT<strong>{order.ordered}</strong>
                  </span>
                  <span>
                    ĐÁP ỨNG<strong>{order.confirmed}</strong>
                  </span>
                  <span>
                    THỰC XUẤT<strong>{order.shipped}</strong>
                  </span>
                  <span>
                    THỰC NHẬN<strong>{order.received}</strong>
                  </span>
                </div>
                <div className="npp-order-card-actions">
                  <small>{order.note}</small>
                  {order.shipped > 0 && order.status !== 'NPP xác nhận nhận' && (
                    <button
                      className="npp-button secondary small"
                      onClick={() => {
                        setReceiptOrder(order);
                        setReceivedQty(order.received || order.shipped);
                        setDamagedQty(0);
                        setReturnedQty(0);
                      }}
                    >
                      Xác nhận thực nhận
                    </button>
                  )}
                </div>
              </article>
            ))}
        </div>
      </section>
    </>
  );

  const renderFulfillOrders = () => {
    const pendingCount = orders.filter(
      (order) => order.type === 'sell-out' && order.status.includes('Chờ'),
    ).length;
    return (
      <>
        <div className="npp-page-heading">
          <div>
            <span className="npp-eyebrow">SELL-OUT · NPP BÁN CHO ĐIỂM BÁN</span>
            <h1>Đáp ứng đơn điểm bán</h1>
            <p>Xác nhận lượng NPP giao được; Sales và điểm bán không thấy giá sell-in.</p>
          </div>
          <button
            className="npp-button primary"
            disabled={selectedOrders.length === 0}
            onClick={() => {
              const selected = orders.filter((order) => selectedOrders.includes(order.id));
              selected.forEach(confirmSellOut);
              setSelectedOrders([]);
            }}
          >
            Xác nhận hàng loạt <CheckCheck size={16} />
          </button>
        </div>
        <div className="npp-fixed-warning">
          <Truck size={16} />
          <strong>Đã xuất kho không đồng nghĩa điểm bán đã nhận đủ</strong>
          <span>Ghi riêng số đã đáp ứng và số điểm bán thực nhận.</span>
        </div>
        <section className="npp-panel">
          <div className="npp-filter-row">
            <div className="npp-filter-chips">
              {(
                [
                  ['all', 'Tất cả'],
                  ['pending', `Chờ xác nhận · ${pendingCount}`],
                  ['short', 'Giao thiếu'],
                ] as const
              ).map(([id, label]) => (
                <button
                  className={fulfillFilter === id ? 'selected' : ''}
                  key={id}
                  onClick={() => setFulfillFilter(id)}
                >
                  {label}
                </button>
              ))}
            </div>
            <span className="npp-muted">2 đơn sell-out · chỉ của Minh Phát</span>
          </div>
          <div className="npp-table-wrap">
            <table className="npp-table">
              <thead>
                <tr>
                  <th>
                    <input
                      aria-label="Chọn tất cả đơn"
                      checked={
                        filteredOrders.length > 0 &&
                        filteredOrders.every((order) => selectedOrders.includes(order.id))
                      }
                      onChange={(event) =>
                        setSelectedOrders(
                          event.target.checked ? filteredOrders.map((order) => order.id) : [],
                        )
                      }
                      type="checkbox"
                    />
                  </th>
                  <th>Mã / Điểm bán</th>
                  <th>Sales</th>
                  <th>Đã đặt</th>
                  <th>Xác nhận giao</th>
                  <th>Trạng thái</th>
                  <th>Hành động</th>
                </tr>
              </thead>
              <tbody>
                {filteredOrders.length === 0 ? (
                  <tr>
                    <td colSpan={7}>
                      <div className="npp-empty">
                        <CircleCheck size={22} />
                        <strong>Không có đơn trong bộ lọc này</strong>
                        <span>Đơn điểm bán mới sẽ tự hiển thị tại đây.</span>
                      </div>
                    </td>
                  </tr>
                ) : (
                  filteredOrders.map((order) => (
                    <tr key={order.id}>
                      <td>
                        <input
                          aria-label={`Chọn ${order.id}`}
                          checked={selectedOrders.includes(order.id)}
                          onChange={(event) =>
                            setSelectedOrders((current) =>
                              event.target.checked
                                ? [...current, order.id]
                                : current.filter((id) => id !== order.id),
                            )
                          }
                          type="checkbox"
                        />
                      </td>
                      <td>
                        <button className="npp-table-link" onClick={() => setDetailsOrder(order)}>
                          {order.id}
                        </button>
                        <small>{order.customer}</small>
                      </td>
                      <td>{order.owner.replace(' · Sales', '')}</td>
                      <td>
                        <strong>{order.ordered}</strong> thùng
                      </td>
                      <td>
                        <div className="npp-inline-quantity">
                          <QuantityControl
                            label={`lượng đáp ứng ${order.id}`}
                            onChange={(value) =>
                              setSellOutQty((current) => ({ ...current, [order.id]: value }))
                            }
                            value={sellOutQty[order.id] ?? (order.confirmed || order.ordered)}
                          />
                          <span>thùng</span>
                        </div>
                      </td>
                      <td>
                        <Badge
                          tone={
                            order.status.includes('thiếu')
                              ? 'warning'
                              : order.status.includes('Chờ')
                                ? 'info'
                                : order.status.includes('Từ chối')
                                  ? 'danger'
                                  : 'success'
                          }
                        >
                          {order.status}
                        </Badge>
                        {order.due && <small>Giao bù {order.due}</small>}
                      </td>
                      <td>
                        <div className="npp-row-actions">
                          <button
                            className="npp-button primary small"
                            disabled={order.status.includes('Từ chối')}
                            onClick={() => confirmSellOut(order)}
                          >
                            Xác nhận
                          </button>
                          <button
                            className="npp-icon-button danger"
                            aria-label={`Từ chối ${order.id}`}
                            onClick={() => {
                              setRejectOrder(order);
                              setRejectReason('');
                            }}
                          >
                            <X size={15} />
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
          {orders.find((order) => order.id === 'SO-24076') && (
            <div className="npp-backfill-note">
              <Clock3 size={15} />
              <span>
                <strong>SO-24076 · Quán Bình:</strong> đặt 9, NPP đã xác nhận 7; còn thiếu 2 thùng,
                hẹn bù 13/11.
              </span>
              <button
                className="npp-text-button"
                onClick={() => setDetailsOrder(orders.find((order) => order.id === 'SO-24076')!)}
              >
                Xem chi tiết
              </button>
            </div>
          )}
        </section>
      </>
    );
  };

  const renderInventory = () => (
    <>
      <div className="npp-page-heading">
        <div>
          <span className="npp-eyebrow">MINH PHÁT · CẬP NHẬT 12/11/2026 09:42</span>
          <h1>Tồn và sell-out</h1>
          <p>Số bán ra và đơn hàng đã đồng bộ với Suối Việt — không cần nhập lại.</p>
        </div>
        <label className="npp-button secondary file-button">
          <FileSpreadsheet size={16} /> Nhập Excel (CSV)
          <input
            accept=".csv,text/csv"
            onChange={(event) => {
              const file = event.target.files?.[0];
              if (file) {
                void parseInventoryCsv(file).catch(() =>
                  notify('Không đọc được tệp CSV. Hãy kiểm tra định dạng rồi thử lại.'),
                );
              }
            }}
            type="file"
          />
        </label>
      </div>
      <section className="npp-panel">
        <div className="npp-panel-heading">
          <div>
            <span className="npp-eyebrow">TỒN KHO NPP</span>
            <h2>Kiểm tra và sửa nhanh tại ô</h2>
          </div>
          <span className="npp-source-note">
            <CheckCheck size={14} /> Đồng bộ 12/11 · 09:42
          </span>
        </div>
        <div className="npp-table-wrap">
          <table className="npp-table">
            <thead>
              <tr>
                <th>SKU</th>
                <th>Tồn kho</th>
                <th>Sell-out 7 ngày</th>
                <th>Tình trạng</th>
                <th>Cập nhật lúc</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {demoProducts.map((product) => (
                <tr key={product.sku}>
                  <td>
                    <div className="npp-product-cell">
                      <span className="npp-product-icon">
                        <Package size={17} />
                      </span>
                      <span>
                        <strong>{product.sku}</strong>
                        <small>{product.name}</small>
                      </span>
                    </div>
                  </td>
                  <td>
                    <div className="npp-inline-quantity">
                      <QuantityControl
                        label={`tồn kho ${product.sku}`}
                        onChange={(value) =>
                          setStock((current) => ({ ...current, [product.sku]: value }))
                        }
                        value={stock[product.sku]}
                      />
                      <span>thùng</span>
                    </div>
                  </td>
                  <td>
                    {product.sales7d} thùng <small>· 7 ngày</small>
                  </td>
                  <td>
                    <Badge
                      tone={
                        stock[product.sku] < 10
                          ? 'warning'
                          : stock[product.sku] < 15
                            ? 'low'
                            : 'success'
                      }
                    >
                      {stock[product.sku] < 10
                        ? 'Cần nhập'
                        : stock[product.sku] < 15
                          ? 'Sắp thấp'
                          : 'Ổn'}
                    </Badge>
                  </td>
                  <td>12/11/2026 · 09:42</td>
                  <td>
                    <button className="npp-text-button" onClick={() => navigate('place')}>
                      Đặt bổ sung <ArrowRight size={14} />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {importPreview && (
          <div className="npp-import-preview">
            <div>
              <FileSpreadsheet size={18} />
              <span>
                <strong>{importPreview.name}</strong>
                <small>
                  Xem trước · {importPreview.rows.length} dòng hợp lệ ·{' '}
                  {importPreview.errors.length} dòng lỗi
                </small>
              </span>
            </div>
            <div className="npp-import-errors">
              {importPreview.errors.map((error) => (
                <small key={error}>{error}</small>
              ))}
            </div>
            <div>
              <button className="npp-button secondary small" onClick={() => setImportPreview(null)}>
                Hủy nhập
              </button>
              <button
                className="npp-button primary small"
                disabled={!importPreview.rows.length}
                onClick={applyInventoryImport}
              >
                Áp dụng dòng hợp lệ
              </button>
            </div>
          </div>
        )}
      </section>
      <section className="npp-panel">
        <div className="npp-panel-heading">
          <div>
            <span className="npp-eyebrow">TỐC ĐỘ TIÊU THỤ</span>
            <h2>Sell-out theo SKU</h2>
          </div>
          <select className="npp-select">
            <option>Minh Phát · Q.1</option>
            <option>So sánh khu vực</option>
          </select>
        </div>
        <div className="npp-consumption-list">
          {demoProducts.map((product) => {
            const daily = Math.max(1, Math.round(product.sales7d / 7));
            const days = Math.floor(stock[product.sku] / daily);
            return (
              <div className="npp-consumption-row" key={product.sku}>
                <div>
                  <strong>{product.sku}</strong>
                  <small>{product.name}</small>
                </div>
                <div className="npp-consumption-track">
                  <i style={{ width: `${Math.min(100, product.sales7d * 1.4)}%` }} />
                </div>
                <strong>
                  {product.sales7d}
                  <small> thùng / tuần</small>
                </strong>
                <Badge tone={days < 4 ? 'warning' : 'success'}>Còn khoảng {days} ngày</Badge>
              </div>
            );
          })}
        </div>
      </section>
    </>
  );

  const renderPolicies = () => (
    <>
      <div className="npp-page-heading">
        <div>
          <span className="npp-eyebrow">DÀNH CHO NPP MINH PHÁT</span>
          <h1>Chính sách & công nợ</h1>
          <p>Chính sách, chứng từ và đối soát theo cùng một nguồn dữ liệu.</p>
        </div>
        <Badge tone="success">Kế toán NPP</Badge>
      </div>
      <div className="npp-finance-summary">
        <section className="npp-finance-main">
          <span className="npp-eyebrow">CÔNG NỢ THEO DÕI</span>
          <strong>
            186 <small>triệu đồng</small>
          </strong>
          <p>Đối chiếu chứng từ NSX · cập nhật 12/11/2026 09:42</p>
          <div className="npp-finance-bars">
            <span>
              <i style={{ width: '68%' }} />
            </span>
            <small>Số liệu tạm tính; vui lòng đối chiếu trước khi thanh toán.</small>
          </div>
        </section>
        <section className="npp-finance-limit">
          <span className="npp-eyebrow">HẠN MỨC CÒN LẠI</span>
          <strong>
            120 <small>thùng</small>
          </strong>
          <p>Hiển thị cho cả vai trò kho và kế toán.</p>
          <button className="npp-button secondary small" onClick={() => navigate('place')}>
            Đặt hàng trong hạn mức <ArrowRight size={14} />
          </button>
        </section>
      </div>
      <div className="npp-dashboard-grid">
        <section className="npp-panel">
          <div className="npp-panel-heading">
            <div>
              <span className="npp-eyebrow">CHỨNG TỪ · CHỈ KẾ TOÁN / ĐẦU MỐI</span>
              <h2>Đối chiếu hai bên</h2>
            </div>
            <Badge tone="warning">Cần đối chiếu</Badge>
          </div>
          <div className="npp-table-wrap">
            <table className="npp-table">
              <thead>
                <tr>
                  <th>Loại chứng từ</th>
                  <th>Mã</th>
                  <th>Số tiền</th>
                  <th>Hạn thanh toán</th>
                  <th>Trạng thái</th>
                </tr>
              </thead>
              <tbody>
                <tr>
                  <td>Hóa đơn NSX</td>
                  <td>HD-SV-1182</td>
                  <td>186.000.000đ</td>
                  <td>20/11/2026</td>
                  <td>
                    <Badge tone="warning">Đang đối chiếu</Badge>
                  </td>
                </tr>
                <tr>
                  <td>Thanh toán</td>
                  <td>Chưa khai báo</td>
                  <td>—</td>
                  <td>—</td>
                  <td>
                    <Badge tone="neutral">Chưa có dữ liệu</Badge>
                  </td>
                </tr>
                <tr>
                  <td>Khấu trừ / chiết khấu</td>
                  <td>Q4-2026</td>
                  <td>Chưa chốt</td>
                  <td>31/12/2026</td>
                  <td>
                    <Badge tone="info">Theo tiến độ</Badge>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <div className="npp-payment-form">
            <label>
              Số tiền thanh toán
              <input
                min="0"
                onChange={(event) => setPaymentAmount(event.target.value)}
                placeholder="Nhập số tiền"
                type="number"
                value={paymentAmount}
              />
            </label>
            <label>
              Mã chứng từ / tham chiếu
              <input
                onChange={(event) => setPaymentReference(event.target.value)}
                placeholder="Ví dụ: UNC-2026-001"
                value={paymentReference}
              />
            </label>
            <button className="npp-button primary" disabled={paymentDeclared} onClick={savePayment}>
              <Send size={15} /> Khai báo thanh toán
            </button>
          </div>
          {paymentDeclared && (
            <div className="npp-success-line">
              <CheckCircleIcon /> {activeRole.name} đã khai báo {fmt.format(Number(paymentAmount))}đ
              · {paymentReference} · {stamp()}
            </div>
          )}
        </section>
        <section className="npp-panel npp-reward-panel">
          <span className="npp-eyebrow">CHÍNH SÁCH THƯỞNG</span>
          <h2>Chiết khấu quý 4</h2>
          <strong>2,5%</strong>
          <p>Khi đạt mục tiêu nhập hàng quý 4</p>
          <div className="npp-reward-progress">
            <span>
              <i style={{ width: '52%' }} />
            </span>
            <div>
              <b>420 / 800 thùng</b>
              <small>Đạt 52% mục tiêu</small>
            </div>
          </div>
          <button className="npp-button secondary full" onClick={() => navigate('planning')}>
            Xem kế hoạch nhập <ArrowRight size={14} />
          </button>
        </section>
      </div>
      <section className="npp-panel">
        <div className="npp-panel-heading">
          <div>
            <span className="npp-eyebrow">POLICY & PROMOTION CENTER</span>
            <h2>Chính sách và ưu đãi</h2>
          </div>
          <span className="npp-muted">Áp dụng cho NPP Minh Phát</span>
        </div>
        <div className="npp-policy-list">
          {[
            {
              id: 'price-30',
              title: 'Bảng giá bán buôn Suối Việt',
              detail: 'Giá sell-in theo SKU · hiệu lực đến 30/11/2026',
              tag: 'Bảng giá',
            },
            {
              id: 'promo-ns500',
              title: 'Mua 10 thùng NS500 tặng 1',
              detail: 'Hàng tặng không tính vào số thu · áp dụng đến 30/11',
              tag: 'CTKM',
            },
            {
              id: 'discount-q4',
              title: 'Chiết khấu quý 4 · 2,5%',
              detail: 'Áp dụng khi đạt mục tiêu nhập hàng quý 4',
              tag: 'Điều kiện thưởng',
            },
          ].map((policy) => (
            <article className="npp-policy-item" key={policy.id}>
              <span className="npp-policy-icon">
                <Ticket size={17} />
              </span>
              <div>
                <Badge tone="info">{policy.tag}</Badge>
                <strong>{policy.title}</strong>
                <small>{policy.detail}</small>
              </div>
              {policySeen[policy.id] ? (
                <Badge tone="success">Đã tiếp nhận · {policySeen[policy.id]}</Badge>
              ) : (
                <button
                  className="npp-button secondary small"
                  onClick={() => {
                    const acceptedAt = `${activeRole.name} · ${stamp()}`;
                    setPolicySeen((current) => ({ ...current, [policy.id]: acceptedAt }));
                    notify(`Đã tiếp nhận chính sách ${policy.tag}`);
                  }}
                >
                  Đã tiếp nhận
                </button>
              )}
            </article>
          ))}
        </div>
      </section>
      <section className="npp-panel npp-reconciliation">
        <div>
          <span className="npp-eyebrow">ĐỐI SOÁT</span>
          <h2>Đặt · xuất · nhận</h2>
          <p>Đơn đã tạo không đồng nghĩa giao đủ. Ghi nhận sai lệch bằng yêu cầu tới NSX.</p>
        </div>
        <div className="npp-recon-values">
          <span>
            Đặt<strong>40</strong>
          </span>
          <ArrowRight size={15} />
          <span>
            Xuất<strong>24</strong>
          </span>
          <ArrowRight size={15} />
          <span>
            Nhận<strong>0</strong>
          </span>
          <Badge tone="warning">Đang giao</Badge>
        </div>
        <button className="npp-button secondary" onClick={() => navigate('requests')}>
          Gửi yêu cầu đối soát
        </button>
      </section>
    </>
  );

  const renderRequests = () => (
    <>
      <div className="npp-page-heading">
        <div>
          <span className="npp-eyebrow">MỘT ĐẦU MỐI TỚI SUỐI VIỆT</span>
          <h1>Yêu cầu tới NSX</h1>
          <p>Gửi và theo dõi giao thiếu, chính sách, trade marketing hoặc phản hồi thị trường.</p>
        </div>
        <Badge tone="info">{tickets.length} yêu cầu</Badge>
      </div>
      <div className="npp-request-layout">
        <section className="npp-panel npp-request-form">
          <div className="npp-panel-heading">
            <div>
              <span className="npp-eyebrow">TẠO YÊU CẦU</span>
              <h2>Gửi tới Suối Việt</h2>
            </div>
            <span className="npp-panel-icon">
              <Send size={17} />
            </span>
          </div>
          <label>
            Loại yêu cầu
            <select onChange={(event) => setTicketKind(event.target.value)} value={ticketKind}>
              <option>Giao thiếu / hỏng</option>
              <option>Cận hạn / sai quy cách</option>
              <option>Công nợ / đối soát</option>
              <option>Đổi trả</option>
              <option>Đề xuất Trade marketing</option>
              <option>Phản hồi thị trường</option>
            </select>
          </label>
          <label>
            Tiêu đề
            <input
              onChange={(event) => setTicketTitle(event.target.value)}
              placeholder={
                ticketKind.includes('Trade')
                  ? 'Ví dụ: Xin POSM kệ nước suối'
                  : 'Mô tả ngắn gọn vấn đề'
              }
              value={ticketTitle}
            />
          </label>
          <div className="npp-form-grid">
            <label>
              Đơn / mã liên quan
              <select
                onChange={(event) => setTicketRelated(event.target.value)}
                value={ticketRelated}
              >
                <option>SO-24076</option>
                <option>SO-24081</option>
                <option>SI-1182</option>
                <option>Không gắn đơn</option>
              </select>
            </label>
            <label>
              SKU / lô
              <input placeholder="Ví dụ: NS150 · lô 12A" />
            </label>
          </div>
          <div className="npp-form-grid">
            <label>
              Số lượng liên quan
              <input min="0" placeholder="Số thùng" type="number" />
            </label>
            <label>
              Khu vực / mức ảnh hưởng
              <input placeholder="Q.1 · ảnh hưởng cao" />
            </label>
          </div>
          <label>
            Thông tin bổ sung
            <textarea
              placeholder={
                ticketKind.includes('Trade')
                  ? 'POSM, trưng bày, kích cầu, ngân sách hoặc đào tạo mong muốn'
                  : 'Mô tả chi tiết, số lượng giao thiếu / hỏng, thời gian phát hiện'
              }
              rows={3}
            />
          </label>
          <label className="npp-file-input">
            Ảnh / chứng từ
            <input
              accept="image/*,.pdf"
              onChange={(event) => setTicketFileName(event.target.files?.[0]?.name ?? '')}
              type="file"
            />
            <span>
              <FileSpreadsheet size={16} /> {ticketFileName || 'Chọn ảnh hoặc chứng từ'}
            </span>
          </label>
          <button className="npp-button primary full" onClick={createTicket}>
            <Send size={16} /> Gửi yêu cầu
          </button>
        </section>
        <section className="npp-panel npp-ticket-list-panel">
          <div className="npp-panel-heading">
            <div>
              <span className="npp-eyebrow">THEO DÕI TRẠNG THÁI & HẠN PHẢN HỒI</span>
              <h2>Yêu cầu của Minh Phát</h2>
            </div>
            <button
              className="npp-icon-button"
              aria-label="Làm mới danh sách"
              onClick={() => notify('Danh sách đã được làm mới')}
            >
              <RotateCcw size={16} />
            </button>
          </div>
          <div className="npp-ticket-list">
            {tickets.length === 0 ? (
              <div className="npp-empty">
                <Headphones size={22} />
                <strong>Chưa có yêu cầu nào</strong>
                <span>Tạo yêu cầu đầu tiên ở biểu mẫu bên cạnh.</span>
              </div>
            ) : (
              tickets.map((ticket) => (
                <article className="npp-ticket-row" key={ticket.id}>
                  <span className="npp-ticket-icon">
                    <Ticket size={17} />
                  </span>
                  <div>
                    <div className="npp-ticket-title">
                      <strong>{ticket.title}</strong>
                      <Badge
                        tone={
                          ticket.status.includes('Chờ')
                            ? 'warning'
                            : ticket.status.includes('Đang')
                              ? 'info'
                              : 'success'
                        }
                      >
                        {ticket.status}
                      </Badge>
                    </div>
                    <small>
                      {ticket.id} · {ticket.category}
                    </small>
                    <small>
                      {ticket.related} · Phụ trách: {ticket.owner}
                    </small>
                  </div>
                  <div className="npp-ticket-due">
                    <span>Hạn phản hồi</span>
                    <strong>{ticket.due}</strong>
                    <small>{ticket.updatedAt}</small>
                  </div>
                </article>
              ))
            )}
          </div>
          <div className="npp-ticket-hint">
            <Clock3 size={15} /> TK-328 · Xin POSM kệ nước suối · chờ Trade NSX nhận · hạn
            13/11/2026.
          </div>
        </section>
      </div>
    </>
  );

  const renderPlanning = () => (
    <>
      <div className="npp-page-heading">
        <div>
          <span className="npp-eyebrow">KẾ HOẠCH NHẬP HÀNG · THÁNG 11/2026</span>
          <h1>Kế hoạch nhập</h1>
          <p>
            Gợi ý dựa trên sell-out và tồn kho đã đồng bộ. Điều chỉnh trước khi gửi cho Suối Việt.
          </p>
        </div>
        <Badge tone="info">Mục tiêu 800 thùng</Badge>
      </div>
      <section className="npp-panel">
        <div className="npp-panel-heading">
          <div>
            <span className="npp-eyebrow">SKU × TUẦN</span>
            <h2>Kế hoạch dự kiến</h2>
          </div>
          <span className="npp-source-note">
            <CheckCheck size={14} /> Dựa trên sell-out 7 ngày và tồn hiện tại
          </span>
        </div>
        <div className="npp-table-wrap">
          <table className="npp-table npp-plan-table">
            <thead>
              <tr>
                <th>Sản phẩm</th>
                <th>Tồn hiện tại</th>
                <th>Gợi ý từ hệ thống</th>
                <th>
                  Tuần 3<br />
                  <small>16–22/11</small>
                </th>
                <th>
                  Tuần 4<br />
                  <small>23–30/11</small>
                </th>
                <th>Tổng kế hoạch</th>
              </tr>
            </thead>
            <tbody>
              {demoProducts.map((product) => (
                <tr key={product.sku}>
                  <td>
                    <strong>{product.sku}</strong>
                    <small>{product.name}</small>
                  </td>
                  <td>{stock[product.sku]} thùng</td>
                  <td>
                    <Badge tone={stock[product.sku] < 15 ? 'warning' : 'success'}>
                      {Math.max(1, product.sales7d * 2 - stock[product.sku])} thùng
                    </Badge>
                  </td>
                  <td>
                    <QuantityControl
                      label={`tuần 3 ${product.sku}`}
                      onChange={(value) =>
                        setPlan((current) => ({ ...current, [`${product.sku}-3`]: value }))
                      }
                      value={plan[`${product.sku}-3`] ?? Math.ceil((plan[product.sku] ?? 0) / 2)}
                    />
                  </td>
                  <td>
                    <QuantityControl
                      label={`tuần 4 ${product.sku}`}
                      onChange={(value) =>
                        setPlan((current) => ({ ...current, [`${product.sku}-4`]: value }))
                      }
                      value={plan[`${product.sku}-4`] ?? Math.floor((plan[product.sku] ?? 0) / 2)}
                    />
                  </td>
                  <td>
                    <strong>
                      {(plan[`${product.sku}-3`] ?? Math.ceil((plan[product.sku] ?? 0) / 2)) +
                        (plan[`${product.sku}-4`] ?? Math.floor((plan[product.sku] ?? 0) / 2))}{' '}
                      thùng
                    </strong>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="npp-plan-footer">
          <div>
            <span>Tổng kế hoạch nhập</span>
            <strong>
              {demoProducts.reduce(
                (sum, product) =>
                  sum +
                  (plan[`${product.sku}-3`] ?? Math.ceil((plan[product.sku] ?? 0) / 2)) +
                  (plan[`${product.sku}-4`] ?? Math.floor((plan[product.sku] ?? 0) / 2)),
                0,
              )}{' '}
              thùng
            </strong>
            <small>
              Tiến độ mục tiêu tháng: {overview.sellInTarget}/{overview.sellInTargetTotal} thùng ·
              52%
            </small>
          </div>
          <button
            className="npp-button primary"
            onClick={() => {
              setPlanSent(true);
              notify(`Đã gửi kế hoạch nhập ${selectedMonth}`);
            }}
          >
            <Send size={15} /> Gửi kế hoạch nhập
          </button>
        </div>
        {planSent && (
          <div className="npp-success-line">
            <CheckCircleIcon /> {activeRole.name} đã gửi kế hoạch tới Suối Việt lúc {stamp()} · có
            thể cập nhật khi nhu cầu thay đổi.
          </div>
        )}
      </section>
      <section className="npp-panel npp-tip-panel">
        <span className="npp-tip-icon">
          <ArrowDownRight size={20} />
        </span>
        <div>
          <strong>Gợi ý nhập theo nhịp bán</strong>
          <p>
            NS150 còn 8 thùng, bán ra 21 thùng/tuần — ưu tiên nhập trong tuần này. TX330 còn khoảng
            5 ngày tồn.
          </p>
        </div>
        <button className="npp-button secondary" onClick={() => navigate('place')}>
          Tạo đơn từ kế hoạch <ArrowRight size={14} />
        </button>
      </section>
    </>
  );

  const renderUsers = () => (
    <>
      <div className="npp-page-heading">
        <div>
          <span className="npp-eyebrow">CHỈ ĐẦU MỐI NPP</span>
          <h1>Người dùng Minh Phát</h1>
          <p>Mời nhân viên và chọn đúng vai trò theo công việc.</p>
        </div>
        <Badge tone="success">Quản lý bởi {people.distributor.name}</Badge>
      </div>
      <section className="npp-panel npp-invite-panel">
        <div className="npp-panel-heading">
          <div>
            <span className="npp-eyebrow">MỜI NHÂN VIÊN</span>
            <h2>Thêm người vào NPP</h2>
          </div>
          <span className="npp-panel-icon">
            <Users size={17} />
          </span>
        </div>
        <div className="npp-invite-form">
          <label>
            Họ tên
            <input
              onChange={(event) => setInviteName(event.target.value)}
              placeholder="Tên nhân viên"
              value={inviteName}
            />
          </label>
          <label>
            Vai trò
            <select onChange={(event) => setInviteRole(event.target.value)} value={inviteRole}>
              <option>Nhân viên đơn / kho</option>
              <option>Kế toán NPP</option>
            </select>
          </label>
          <button className="npp-button primary" onClick={submitInvite}>
            <Plus size={16} /> Mời nhân viên
          </button>
        </div>
      </section>
      <section className="npp-panel">
        <div className="npp-panel-heading">
          <div>
            <span className="npp-eyebrow">TÀI KHOẢN CÓ QUYỀN VÀO NPP MINH PHÁT</span>
            <h2>Nhân sự & vai trò</h2>
          </div>
          <Badge tone="neutral">{invitedUsers.length} người</Badge>
        </div>
        <div className="npp-table-wrap">
          <table className="npp-table">
            <thead>
              <tr>
                <th>Người dùng</th>
                <th>Vai trò</th>
                <th>Phạm vi dữ liệu</th>
                <th>Trạng thái</th>
              </tr>
            </thead>
            <tbody>
              {invitedUsers.map((user) => (
                <tr key={`${user.name}-${user.role}`}>
                  <td>
                    <div className="npp-user-cell">
                      <span>{user.name.slice(0, 1)}</span>
                      <strong>{user.name}</strong>
                    </div>
                  </td>
                  <td>{user.role}</td>
                  <td>NPP Minh Phát</td>
                  <td>
                    <Badge tone={user.status === 'Đang hoạt động' ? 'success' : 'info'}>
                      {user.status}
                    </Badge>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <p className="npp-permission-note">
          <ShieldCheck size={15} /> Nhân viên đơn/kho không xem được hóa đơn chi tiết. Kế toán NPP
          có quyền xem công nợ và khai báo thanh toán.
        </p>
      </section>
    </>
  );

  const renderScreen = () => {
    if (loading)
      return (
        <div className="npp-skeleton-screen">
          <i />
          <i />
          <i />
          <i />
        </div>
      );
    switch (screen) {
      case 'overview':
        return renderOverview();
      case 'place':
        return renderPlaceOrders();
      case 'fulfill':
        return renderFulfillOrders();
      case 'inventory':
        return renderInventory();
      case 'policies':
        return renderPolicies();
      case 'requests':
        return renderRequests();
      case 'planning':
        return renderPlanning();
      case 'users':
        return renderUsers();
    }
  };

  return (
    <div className={`npp-app${dark ? ' dark' : ''}`} data-theme={dark ? 'dark' : 'light'}>
      <aside
        className={`npp-sidebar${collapsed ? ' collapsed' : ''}${mobileMenu ? ' mobile-open' : ''}`}
      >
        <div className="npp-brand">
          <span className="npp-brand-mark">S</span>
          {!collapsed && (
            <span>
              <strong>SYNERA</strong>
              <small>PORTAL NHÀ PHÂN PHỐI</small>
            </span>
          )}
          <button
            aria-label="Thu gọn menu"
            className="npp-collapse-button"
            onClick={() => setCollapsed((value) => !value)}
          >
            <ChevronLeft size={17} />
          </button>
        </div>
        {!collapsed && (
          <div className="npp-workspace">
            <span className="npp-workspace-mark">MP</span>
            <span>
              <strong>NPP Minh Phát</strong>
              <small>Q.1 · TP. Hồ Chí Minh</small>
            </span>
            <ChevronDown size={14} />
          </div>
        )}
        <span className="npp-nav-caption">{!collapsed ? 'VIỆC VỚI SUỐI VIỆT' : 'NPP'}</span>
        <nav className="npp-nav">
          {visibleNav.map((item) => {
            const Icon = item.icon;
            return (
              <button
                className={`npp-nav-link${screen === item.id ? ' active' : ''}`}
                key={item.id}
                onClick={() => navigate(item.id)}
                title={collapsed ? item.label : undefined}
              >
                <Icon size={18} />
                <span>{!collapsed && item.label}</span>
                {item.id === 'fulfill' && !collapsed && <b>1</b>}
              </button>
            );
          })}
        </nav>
        <div className="npp-sidebar-bottom">
          {!collapsed && (
            <label className="npp-role-switch">
              <span>ĐANG XEM VỚI VAI TRÒ</span>
              <select onChange={(event) => changeRole(event.target.value as Role)} value={role}>
                {roles.map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.label}
                  </option>
                ))}
              </select>
              <ChevronDown size={14} />
            </label>
          )}
          {collapsed && (
            <button
              className="npp-role-mini"
              title="Chọn vai trò"
              onClick={() => setCollapsed(false)}
            >
              {activeRole.initials}
            </button>
          )}
          <div className="npp-side-user">
            <span>{activeRole.initials}</span>
            {!collapsed && (
              <div>
                <strong>{activeRole.name}</strong>
                <small>{activeRole.label}</small>
              </div>
            )}
            <button
              aria-label="Thiết lập"
              onClick={() =>
                role === 'lead'
                  ? navigate('users')
                  : notify('Liên hệ đầu mối NPP để cập nhật tài khoản')
              }
            >
              <Settings size={16} />
            </button>
          </div>
        </div>
      </aside>
      {mobileMenu && (
        <button
          className="npp-mobile-backdrop"
          aria-label="Đóng menu"
          onClick={() => setMobileMenu(false)}
        />
      )}
      <div className="npp-main">
        <header className="npp-topbar">
          <button
            className="npp-mobile-menu"
            aria-label="Mở menu"
            onClick={() => setMobileMenu(true)}
          >
            <Menu size={19} />
          </button>
          <div className="npp-breadcrumb">
            <span>NPP MINH PHÁT</span>
            <ChevronRight size={14} />
            <strong>{pageTitle}</strong>
          </div>
          <label className="npp-search" onClick={() => setSearchOpen(true)}>
            <Search size={16} />
            <input
              onChange={(event) => setGlobalSearch(event.target.value)}
              onFocus={() => setSearchOpen(true)}
              placeholder="Tìm mã đơn, điểm bán, SKU..."
              value={globalSearch}
            />
            <kbd>Ctrl K</kbd>
          </label>
          <button
            className="npp-top-icon"
            aria-label="Thông báo"
            onClick={() => notify('Không có thông báo mới')}
          >
            <Bell size={18} />
            <i />
          </button>
          <button
            className="npp-top-icon"
            aria-label={dark ? 'Bật giao diện sáng' : 'Bật giao diện tối'}
            onClick={() => setDark((value) => !value)}
          >
            {dark ? <Sun size={18} /> : <Moon size={18} />}
          </button>
          <div className="npp-top-user">
            <span>{activeRole.initials}</span>
            <div>
              <strong>{activeRole.name}</strong>
              <small>{activeRole.label}</small>
            </div>
          </div>
        </header>
        <main className="npp-content">{renderScreen()}</main>
      </div>
      {toast && (
        <div className="npp-toast">
          <Check size={16} />
          {toast}
          <button aria-label="Đóng thông báo" onClick={() => setToast('')}>
            <X size={14} />
          </button>
        </div>
      )}
      {searchOpen && (
        <div className="npp-modal-backdrop" onMouseDown={() => setSearchOpen(false)}>
          <section className="npp-command" onMouseDown={(event) => event.stopPropagation()}>
            <div className="npp-command-input">
              <Search size={18} />
              <input
                autoFocus
                onChange={(event) => setGlobalSearch(event.target.value)}
                placeholder="Tìm mã đơn, điểm bán, SKU..."
                value={globalSearch}
              />
              <kbd>ESC</kbd>
            </div>
            <div className="npp-command-results">
              {globalSearch.trim() ? (
                orders
                  .filter((order) =>
                    `${order.id} ${order.customer} ${order.lines.map((line) => line.sku).join(' ')}`
                      .toLowerCase()
                      .includes(globalSearch.toLowerCase()),
                  )
                  .map((order) => (
                    <button
                      key={order.id}
                      onClick={() => {
                        setDetailsOrder(order);
                        setSearchOpen(false);
                      }}
                    >
                      <ClipboardList size={16} />
                      <span>
                        <strong>{order.id}</strong>
                        <small>
                          {order.customer} · {order.status}
                        </small>
                      </span>
                      <ArrowRight size={14} />
                    </button>
                  ))
              ) : (
                <div>
                  <Search size={16} /> Tìm đơn hàng hoặc SKU của NPP Minh Phát
                </div>
              )}
              {globalSearch.trim() &&
                !orders.some((order) =>
                  `${order.id} ${order.customer} ${order.lines.map((line) => line.sku).join(' ')}`
                    .toLowerCase()
                    .includes(globalSearch.toLowerCase()),
                ) && <p>Không tìm thấy kết quả trong dữ liệu Minh Phát.</p>}
            </div>
          </section>
        </div>
      )}
      {detailsOrder && (
        <div className="npp-drawer-backdrop" onMouseDown={() => setDetailsOrder(null)}>
          <aside className="npp-drawer" onMouseDown={(event) => event.stopPropagation()}>
            <div className="npp-drawer-head">
              <div>
                <span className="npp-eyebrow">
                  {detailsOrder.type === 'sell-in'
                    ? 'SELL-IN · NPP ĐẶT NSX'
                    : 'SELL-OUT · NPP GIAO ĐIỂM BÁN'}
                </span>
                <h2>{detailsOrder.id}</h2>
                <small>
                  {detailsOrder.customer} · {detailsOrder.createdAt}
                </small>
              </div>
              <button
                className="npp-icon-button"
                aria-label="Đóng chi tiết"
                onClick={() => setDetailsOrder(null)}
              >
                <X size={18} />
              </button>
            </div>
            <div className="npp-drawer-body">
              <Badge tone={detailsOrder.status.includes('thiếu') ? 'warning' : 'info'}>
                {detailsOrder.status}
              </Badge>
              <Stepper order={detailsOrder} />
              <section className="npp-drawer-section">
                <h3>Số lượng theo từng bước</h3>
                <div className="npp-four-counts">
                  <span>
                    ĐẶT<strong>{detailsOrder.ordered}</strong>
                  </span>
                  <span>
                    ĐÁP ỨNG<strong>{detailsOrder.confirmed}</strong>
                  </span>
                  <span>
                    THỰC XUẤT<strong>{detailsOrder.shipped}</strong>
                  </span>
                  <span>
                    THỰC NHẬN<strong>{detailsOrder.received}</strong>
                  </span>
                </div>
              </section>
              <section className="npp-drawer-section">
                <h3>Sản phẩm</h3>
                {detailsOrder.lines.map((line) => (
                  <div className="npp-drawer-line" key={line.sku}>
                    <span>
                      <strong>{line.sku}</strong>
                      <small>{line.name}</small>
                    </span>
                    <b>{line.quantity} thùng</b>
                  </div>
                ))}
              </section>
              {detailsOrder.freeGoods?.map((line) => (
                <div className="npp-gift-row" key={line.sku}>
                  <Package size={15} /> Hàng tặng: {line.quantity} thùng {line.name} · tách khỏi số
                  thu
                </div>
              ))}
              <section className="npp-drawer-section">
                <h3>Lịch sử xử lý</h3>
                <p className="npp-history-line">
                  <i /> {detailsOrder.note}
                </p>
                <p className="npp-history-line">
                  <i /> Tạo bởi {detailsOrder.owner} · {detailsOrder.createdAt}
                </p>
              </section>
              {detailsOrder.type === 'sell-in' && detailsOrder.shipped > 0 && (
                <button
                  className="npp-button primary full"
                  onClick={() => {
                    setReceiptOrder(detailsOrder);
                    setReceivedQty(detailsOrder.received || detailsOrder.shipped);
                    setDetailsOrder(null);
                  }}
                >
                  Xác nhận thực nhận <CheckCheck size={15} />
                </button>
              )}
              {detailsOrder.type === 'sell-in' && detailsOrder.shipped === 0 && (
                <div className="npp-receipt-pending">
                  <Truck size={15} /> Mục xác nhận thực nhận sẽ mở khi Suối Việt cập nhật số thực
                  xuất.
                </div>
              )}
            </div>
          </aside>
        </div>
      )}
      {receiptOrder && (
        <div className="npp-modal-backdrop" onMouseDown={() => setReceiptOrder(null)}>
          <section className="npp-dialog" onMouseDown={(event) => event.stopPropagation()}>
            <div className="npp-dialog-head">
              <div>
                <span className="npp-eyebrow">XÁC NHẬN THỰC NHẬN · {receiptOrder.id}</span>
                <h2>Ghi nhận hàng nhận được</h2>
              </div>
              <button
                className="npp-icon-button"
                aria-label="Đóng"
                onClick={() => setReceiptOrder(null)}
              >
                <X size={17} />
              </button>
            </div>
            <p>
              Đã giao {receiptOrder.shipped} thùng. Ghi riêng số thực nhận, thiếu, hỏng và hoàn.
            </p>
            <div className="npp-receipt-fields">
              <label>
                Thực nhận
                <QuantityControl
                  label="số thực nhận"
                  onChange={setReceivedQty}
                  value={receivedQty}
                />
              </label>
              <label>
                Hàng hỏng
                <QuantityControl label="số hàng hỏng" onChange={setDamagedQty} value={damagedQty} />
              </label>
              <label>
                Hàng hoàn
                <QuantityControl
                  label="số hàng hoàn"
                  onChange={setReturnedQty}
                  value={returnedQty}
                />
              </label>
              <div className="npp-receipt-missing">
                Thiếu ghi nhận
                <strong>{Math.max(0, receiptOrder.shipped - receivedQty)} thùng</strong>
              </div>
            </div>
            <div className="npp-dialog-actions">
              <button className="npp-button secondary" onClick={() => setReceiptOrder(null)}>
                Để sau
              </button>
              <button className="npp-button primary" onClick={submitReceipt}>
                Lưu thực nhận <Check size={15} />
              </button>
            </div>
          </section>
        </div>
      )}
      {rejectOrder && (
        <div className="npp-modal-backdrop" onMouseDown={() => setRejectOrder(null)}>
          <section className="npp-dialog" onMouseDown={(event) => event.stopPropagation()}>
            <div className="npp-dialog-head">
              <div>
                <span className="npp-eyebrow">TỪ CHỐI ĐƠN · {rejectOrder.id}</span>
                <h2>Lý do từ chối</h2>
              </div>
              <button
                className="npp-icon-button"
                aria-label="Đóng"
                onClick={() => setRejectOrder(null)}
              >
                <X size={17} />
              </button>
            </div>
            <label className="npp-dialog-label">
              Nhập lý do
              <textarea
                autoFocus
                onChange={(event) => setRejectReason(event.target.value)}
                placeholder="Ví dụ: Điểm bán tạm đóng cửa, vui lòng xác nhận lại lịch giao..."
                rows={4}
                value={rejectReason}
              />
            </label>
            <div className="npp-dialog-actions">
              <button className="npp-button secondary" onClick={() => setRejectOrder(null)}>
                Hủy
              </button>
              <button
                className="npp-button danger"
                disabled={!rejectReason.trim()}
                onClick={rejectSellOut}
              >
                Gửi từ chối
              </button>
            </div>
          </section>
        </div>
      )}
    </div>
  );
}

function CheckCircleIcon() {
  return <CircleCheck size={16} />;
}
