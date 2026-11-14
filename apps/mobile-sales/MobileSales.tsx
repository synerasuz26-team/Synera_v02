import { useEffect, useMemo, useRef, useState } from 'react';
import {
  AlertTriangle,
  ArrowDown,
  ArrowLeft,
  ArrowRight,
  Bell,
  CalendarDays,
  Camera,
  Check,
  CheckCircle2,
  ChevronRight,
  ClipboardCheck,
  Clock3,
  Cloud,
  CloudOff,
  FileText,
  Headphones,
  Home,
  ImagePlus,
  MapPin,
  MessageCircle,
  Minus,
  MoreHorizontal,
  Navigation,
  Package,
  Phone,
  Plus,
  RefreshCw,
  Route,
  Send,
  ShieldCheck,
  ShoppingBag,
  ShoppingCart,
  Sparkles,
  Store,
  Ticket,
  UserRound,
  Users,
  Wallet,
  X,
  type LucideIcon,
} from 'lucide-react';
import {
  featureFlags,
  orders as demoOrders,
  people,
  products,
  salesAppointments,
  salesRoute,
  stores,
  tickets as demoTickets,
} from '../../packages/mock-data';

type Role = 'sales' | 'sup';
type Tab = 'home' | 'customers' | 'orders' | 'calendar' | 'more' | 'team';
type Screen = Tab | 'customer-detail' | 'visit' | 'order-form' | 'order-detail' | 'report';
type SyncState = 'sent' | 'queued';
type OrderLine = { sku: string; name: string; quantity: number; unitPrice: number };
type OrderStep = {
  label: string;
  actor: string;
  time: string;
  state: 'done' | 'current' | 'waiting';
};
type SalesOrder = {
  id: string;
  outlet: string;
  createdAt: string;
  lines: OrderLine[];
  ordered: number;
  accepted: number;
  shipped: number;
  received: number;
  freeGoods: number;
  total: number;
  status: string;
  syncState: SyncState;
  idempotencyKey: string;
  steps: OrderStep[];
  resolution?: string;
};
type Appointment = {
  id: string;
  outlet: string;
  title: string;
  time: string;
  owner: string;
  status: string;
};
type Ticket = {
  id: string;
  title: string;
  outlet: string;
  orderId: string;
  assignee: string;
  status: string;
  due: string;
  kind: string;
};
type GpsException = {
  id: string;
  outlet: string;
  distance: number | null;
  status: 'Chờ Sup xác nhận' | 'Đã xác nhận';
  time: string;
  reason: string;
};
type VisitHistory = { outlet: string; time: string; summary: string };
type StorageRecovery = 'read' | 'write' | 'service-worker' | null;
type MobileData = {
  orders: SalesOrder[];
  draftLines: Record<string, number>;
  draftOutlet: string;
  tickets: Ticket[];
  appointments: Appointment[];
  activeOutlet: string | null;
  checkInAt: number | null;
  gpsStatus: 'inside' | 'pending' | 'approved' | null;
  completedStops: string[];
  checklist: Record<string, boolean>;
  stock: Record<string, number>;
  visitNotes: string;
  visitPhotoName: string;
  visitPhotoData: string;
  gpsExceptions: GpsException[];
  assignments: Record<string, string>;
  visitHistory: VisitHistory[];
  reportNote: string;
  reportSubmitted: boolean;
};

const checklistItems = [
  'Trưng bày đúng mặt',
  'Giá niêm yết theo bảng',
  'Kiểm tồn tối thiểu',
  'POSM còn sử dụng tốt',
];
const routeStops = salesRoute.map((stop) => ({
  ...stop,
  address: stores.find((item) => item.name === stop.outlet)?.address ?? '',
}));
const phoneNumbers: Record<string, string> = {
  'Tạp hóa Lan': '+84903123456',
  'Quán Bình': '+84903123457',
  'Cafe 24': '+84903123458',
};
const demoCoordinates: Record<string, { lat: number; lon: number }> = {
  'Tạp hóa Lan': { lat: 10.785, lon: 106.691 },
  'Quán Bình': { lat: 10.782, lon: 106.686 },
  'Cafe 24': { lat: 10.779, lon: 106.689 },
};
const fmt = new Intl.NumberFormat('vi-VN');
const money = (value: number) => `${fmt.format(value)}đ`;
const timeNow = () =>
  new Intl.DateTimeFormat('vi-VN', { hour: '2-digit', minute: '2-digit' }).format(new Date());
const fullStamp = () =>
  new Intl.DateTimeFormat('vi-VN', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date());
const uid = () =>
  typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
const getProduct = (sku: string) => products.find((item) => item.sku === sku);
const outletByName = (name: string) => stores.find((item) => item.name === name) ?? stores[0];
const sellOutUnit = (sku: string) => getProduct(sku)?.sellOut ?? 0;
const orderTotal = (lines: OrderLine[]) =>
  lines.reduce((sum, line) => {
    const discount = line.sku === 'TX330' && line.quantity >= 8 ? 0.03 : 0;
    return sum + Math.round(sellOutUnit(line.sku) * line.quantity * (1 - discount));
  }, 0);

function createInitialData(): MobileData {
  const salesOrders: SalesOrder[] = demoOrders
    .filter((order) => order.type === 'sell-out')
    .map((order) => {
      const sourceLines =
        order.lines.length > 0
          ? order.lines
          : [{ sku: 'NS150', name: 'Nước suối 1.5L', quantity: order.ordered }];
      const lines = sourceLines.map((line) => ({
        sku: line.sku,
        name: line.name,
        quantity: line.quantity,
        unitPrice: sellOutUnit(line.sku),
      }));
      const isBackorder = order.id === 'SO-24076';
      return {
        id: order.id,
        outlet: order.customer,
        createdAt: order.createdAt,
        lines,
        ordered: order.ordered,
        accepted: order.confirmed,
        shipped: order.shipped,
        received: order.received,
        freeGoods: order.freeGoods?.reduce((sum, line) => sum + line.quantity, 0) ?? 0,
        total: order.total ?? orderTotal(lines),
        status: order.status,
        syncState: 'sent',
        idempotencyKey: `seed-${order.id}`,
        steps: [
          { label: 'Sales gửi', actor: people.sales.name, time: order.createdAt, state: 'done' },
          {
            label: 'NPP xác nhận lượng',
            actor: isBackorder ? 'chị Mai · Minh Phát' : 'Đang chờ NPP',
            time: isBackorder ? '11/11 · 14:20' : 'Chưa xác nhận',
            state: isBackorder ? 'done' : 'current',
          },
          {
            label: 'Giao hàng',
            actor: isBackorder ? 'NPP Minh Phát' : 'Chưa phân công',
            time: isBackorder ? '11/11 · 16:10' : 'Chưa giao',
            state: isBackorder ? 'done' : 'waiting',
          },
          {
            label: 'Điểm bán xác nhận thực nhận',
            actor: isBackorder ? 'Anh Bình' : 'Chị Lan',
            time: isBackorder ? '11/11 · 17:00 · nhận 7/9' : 'Chưa xác nhận',
            state: isBackorder ? 'done' : 'waiting',
          },
        ],
        resolution: isBackorder ? 'NPP hẹn giao bù 2 thùng NS150 ngày 13/11.' : undefined,
      };
    });

  return {
    orders: salesOrders,
    draftLines: {},
    draftOutlet: 'Tạp hóa Lan',
    tickets: demoTickets.map((ticket) => ({
      id: ticket.id,
      title: ticket.title,
      outlet: ticket.customer.split(' · ')[0],
      orderId: ticket.customer.includes('SO-') ? 'SO-24076' : '',
      assignee: ticket.assignee,
      status: ticket.status,
      due: ticket.due,
      kind: ticket.kind,
    })),
    appointments: salesAppointments.map((item, index) => ({
      ...item,
      id: `AP-0${index + 1}`,
    })),
    activeOutlet: null,
    checkInAt: null,
    gpsStatus: null,
    completedStops: [],
    checklist: Object.fromEntries(checklistItems.map((item) => [item, false])),
    stock: { NS500: 0, NS150: 0, TX330: 0 },
    visitNotes: '',
    visitPhotoName: '',
    visitPhotoData: '',
    gpsExceptions: [],
    assignments: {},
    visitHistory: [],
    reportNote: '',
    reportSubmitted: false,
  };
}

function resetVisit(): Pick<
  MobileData,
  'checklist' | 'stock' | 'visitNotes' | 'visitPhotoName' | 'visitPhotoData'
> {
  return {
    checklist: Object.fromEntries(checklistItems.map((item) => [item, false])),
    stock: { NS500: 0, NS150: 0, TX330: 0 },
    visitNotes: '',
    visitPhotoName: '',
    visitPhotoData: '',
  };
}

function loadMobileData(): { data: MobileData; error: string; recovery: StorageRecovery } {
  const initial = createInitialData();
  try {
    const raw = localStorage.getItem('synera.mobile-sales.v1');
    if (!raw) return { data: initial, error: '', recovery: null };
    const parsed = JSON.parse(raw) as Partial<MobileData>;
    if (!Array.isArray(parsed.orders) || !Array.isArray(parsed.tickets)) {
      return {
        data: initial,
        error: 'Dữ liệu trên thiết bị không hợp lệ; đang dùng dữ liệu mẫu.',
        recovery: 'read',
      };
    }
    const seededCheckIn =
      parsed.activeOutlet === 'Tạp hóa Lan' &&
      parsed.gpsStatus === 'inside' &&
      parsed.visitHistory?.length === 0 &&
      parsed.completedStops?.length === 0 &&
      parsed.stock?.NS500 === 5 &&
      parsed.stock?.NS150 === 2 &&
      parsed.stock?.TX330 === 1;
    return {
      data: {
        ...initial,
        ...parsed,
        ...(seededCheckIn ? { activeOutlet: null, checkInAt: null, gpsStatus: null } : {}),
        checklist: { ...initial.checklist, ...parsed.checklist },
        stock: { ...initial.stock, ...parsed.stock },
      },
      error: '',
      recovery: null,
    };
  } catch {
    return {
      data: initial,
      error: 'Không đọc được dữ liệu trên thiết bị; đang dùng dữ liệu mẫu.',
      recovery: 'read',
    };
  }
}

const initialLoad = loadMobileData();

function Badge({ children, tone = 'neutral' }: { children: React.ReactNode; tone?: string }) {
  return <span className={`ms-badge ${tone}`}>{children}</span>;
}

function PrimaryButton({
  children,
  onClick,
  disabled = false,
}: {
  children: React.ReactNode;
  onClick: () => void;
  disabled?: boolean;
}) {
  return (
    <button className="ms-primary-button" disabled={disabled} onClick={onClick} type="button">
      {children}
    </button>
  );
}

function QuantityControl({
  value,
  label,
  onChange,
}: {
  value: number;
  label: string;
  onChange: (value: number) => void;
}) {
  return (
    <div className="ms-quantity" aria-label={label}>
      <button
        aria-label={`Giảm ${label}`}
        onClick={() => onChange(Math.max(0, value - 1))}
        type="button"
      >
        <Minus size={18} />
      </button>
      <input
        aria-label={label}
        min={0}
        onChange={(event) => onChange(Math.max(0, Number(event.target.value) || 0))}
        type="number"
        value={value}
      />
      <button aria-label={`Tăng ${label}`} onClick={() => onChange(value + 1)} type="button">
        <Plus size={18} />
      </button>
    </div>
  );
}

function OrderProgress({ order }: { order: SalesOrder }) {
  return (
    <div className="ms-order-progress">
      {order.steps.map((step, index) => (
        <div className={`ms-step ${step.state}`} key={step.label}>
          <span className="ms-step-dot">
            {step.state === 'done' ? <Check size={14} /> : index + 1}
          </span>
          <span className="ms-step-copy">
            <strong>{step.label}</strong>
            <small>{step.actor}</small>
            <small>{step.time}</small>
          </span>
        </div>
      ))}
    </div>
  );
}

const navItems: { id: Tab; label: string; icon: LucideIcon }[] = [
  { id: 'home', label: 'Hôm nay', icon: Home },
  { id: 'customers', label: 'Khách hàng', icon: Store },
  { id: 'orders', label: 'Đơn hàng', icon: ShoppingBag },
  { id: 'calendar', label: 'Lịch', icon: CalendarDays },
  { id: 'more', label: 'Thêm', icon: MoreHorizontal },
  { id: 'team', label: 'Đội', icon: Users },
];

export default function MobileSales() {
  const [data, setData] = useState<MobileData>(initialLoad.data);
  const [storageNotice, setStorageNotice] = useState(initialLoad.error);
  const [storageRecovery, setStorageRecovery] = useState<StorageRecovery>(initialLoad.recovery);
  const persistenceReady = useRef(!initialLoad.recovery);
  const [screen, setScreen] = useState<Screen>('home');
  const [role, setRole] = useState<Role>('sales');
  const [selectedCustomer, setSelectedCustomer] = useState('Tạp hóa Lan');
  const [selectedOrderId, setSelectedOrderId] = useState('SO-24081');
  const [customerSearch, setCustomerSearch] = useState('');
  const [online, setOnline] = useState(() => navigator.onLine);
  const [loading, setLoading] = useState(true);
  const [toast, setToast] = useState('');
  const [undoAction, setUndoAction] = useState<(() => void) | null>(null);
  const [seconds, setSeconds] = useState(0);
  const [refreshing, setRefreshing] = useState(false);
  const [ticketTitle, setTicketTitle] = useState('');
  const [ticketKind, setTicketKind] = useState('Giao thiếu / hỏng');
  const [appointmentTitle, setAppointmentTitle] = useState('');
  const [appointmentTime, setAppointmentTime] = useState('15:00');
  const [supervisorAssignee, setSupervisorAssignee] = useState('Lê Phúc');
  const [handoffOutlet, setHandoffOutlet] = useState('Tạp hóa Lan');
  const [notificationPermission, setNotificationPermission] = useState(
    typeof Notification === 'undefined' ? 'unsupported' : Notification.permission,
  );
  const syncedKeys = useRef(new Set<string>());
  const toastTimer = useRef<number | null>(null);

  const activeStore = data.activeOutlet ? outletByName(data.activeOutlet) : null;
  const customer = outletByName(selectedCustomer);
  const order = data.orders.find((item) => item.id === selectedOrderId) ?? data.orders[0];
  const draftQuantity = Object.values(data.draftLines).reduce((sum, quantity) => sum + quantity, 0);
  const draftProducts = products
    .filter((product) => (data.draftLines[product.sku] ?? 0) > 0)
    .map((product) => ({
      sku: product.sku,
      name: product.name,
      quantity: data.draftLines[product.sku],
      unitPrice: product.sellOut,
    }));
  const draftSubtotal = orderTotal(draftProducts);
  const freeGoods = Math.floor((data.draftLines.NS500 ?? 0) / 10);
  const ordersToday = data.orders.filter((item) => item.createdAt.startsWith('12/11'));
  const routeProgress = Math.min(
    routeStops.length,
    data.completedStops.length + (data.activeOutlet ? 1 : 0),
  );

  useEffect(() => {
    const timer = window.setTimeout(() => setLoading(false), 280);
    return () => window.clearTimeout(timer);
  }, []);

  useEffect(() => {
    const handleOnline = () => updateConnectivity(true);
    const handleOffline = () => updateConnectivity(false);
    function setOnlineState(value: boolean) {
      setOnline(value);
      if (value) notify('Đã có mạng. Đang kiểm tra các đơn chờ gửi.');
    }
    function updateConnectivity(value: boolean) {
      setOnlineState(value);
    }
    window.addEventListener('online', handleOnline);
    window.addEventListener('offline', handleOffline);
    return () => {
      window.removeEventListener('online', handleOnline);
      window.removeEventListener('offline', handleOffline);
    };
  }, []);

  useEffect(() => {
    if (!persistenceReady.current) return;
    try {
      localStorage.setItem('synera.mobile-sales.v1', JSON.stringify(data));
    } catch {
      setStorageNotice('Không thể lưu thêm dữ liệu trên thiết bị. Hãy giải phóng bộ nhớ.');
      setStorageRecovery('write');
    }
  }, [data]);

  useEffect(() => {
    if (!data.activeOutlet || !data.checkInAt) return;
    const timer = window.setInterval(
      () => setSeconds(Math.floor((Date.now() - data.checkInAt!) / 1000)),
      1000,
    );
    setSeconds(Math.floor((Date.now() - data.checkInAt) / 1000));
    return () => window.clearInterval(timer);
  }, [data.activeOutlet, data.checkInAt]);

  useEffect(() => {
    if (!online) return;
    const pending = data.orders.filter(
      (item) => item.syncState === 'queued' && !syncedKeys.current.has(item.idempotencyKey),
    );
    if (!pending.length) return;
    pending.forEach((item) => syncedKeys.current.add(item.idempotencyKey));
    const ids = new Set(pending.map((item) => item.id));
    setData((current) => ({
      ...current,
      orders: current.orders.map((item) => {
        if (!ids.has(item.id) || item.syncState !== 'queued') return item;
        const repricedLines = item.lines.map((line) => ({
          ...line,
          unitPrice: sellOutUnit(line.sku),
        }));
        return {
          ...item,
          lines: repricedLines,
          total: orderTotal(repricedLines),
          status: 'Chờ NPP xác nhận',
          syncState: 'sent',
          steps: [
            ...item.steps.slice(0, 1),
            {
              label: 'NPP xác nhận lượng',
              actor: 'Đang chờ NPP',
              time: 'Chờ xác nhận',
              state: 'current',
            },
            ...item.steps.slice(2),
          ],
        };
      }),
    }));
    notify(`Đã kiểm tra giá và gửi ${pending.length} đơn chờ trong bản demo.`);
  }, [data.orders, online]);

  useEffect(() => {
    if (!import.meta.env.PROD || !('serviceWorker' in navigator)) return;
    navigator.serviceWorker.register('/mobile-sales-sw.js').catch(() => {
      setStorageNotice('Không thể bật bộ nhớ ngoại tuyến. Đơn nháp vẫn lưu trên thiết bị.');
      setStorageRecovery('service-worker');
    });
  }, []);

  const notify = (message: string, undo?: () => void) => {
    setToast(message);
    setUndoAction(() => undo ?? null);
    if (toastTimer.current) window.clearTimeout(toastTimer.current);
    toastTimer.current = window.setTimeout(() => {
      setToast('');
      setUndoAction(null);
    }, 5000);
    if (notificationPermission === 'granted' && 'Notification' in window) {
      new Notification('SYNERA Sales', { body: message });
    }
  };

  const retryStorage = async () => {
    try {
      if (storageRecovery === 'read') {
        const result = loadMobileData();
        if (result.recovery) {
          setStorageNotice(result.error);
          return;
        }
        persistenceReady.current = true;
        setData(result.data);
      } else if (storageRecovery === 'service-worker') {
        await navigator.serviceWorker.register('/mobile-sales-sw.js');
      } else {
        localStorage.setItem('synera.mobile-sales.v1', JSON.stringify(data));
      }
      setStorageNotice('');
      setStorageRecovery(null);
      notify('Đã khôi phục lưu trữ trên thiết bị.');
    } catch {
      setStorageNotice('Thử lại chưa thành công. Kiểm tra bộ nhớ và kết nối rồi thử lại.');
    }
  };

  const undoLastAction = () => {
    const undo = undoAction;
    setToast('');
    setUndoAction(null);
    undo?.();
    if (undo) notify('Đã hoàn tác thao tác vừa rồi.');
  };

  const updateData = (updater: (current: MobileData) => MobileData) => setData(updater);
  const setTab = (tab: Tab) => setScreen(tab);
  const openCustomer = (name: string) => {
    setSelectedCustomer(name);
    setScreen('customer-detail');
  };
  const openVisit = (name: string) => {
    setSelectedCustomer(name);
    setScreen('visit');
  };
  const setDraftQuantity = (sku: string, quantity: number) =>
    updateData((current) => ({
      ...current,
      draftLines: { ...current.draftLines, [sku]: Math.max(0, quantity) },
    }));

  const startOrder = (outlet = data.activeOutlet ?? selectedCustomer, reorder = false) => {
    if (!data.activeOutlet || data.activeOutlet !== outlet) {
      notify('Hãy check-in điểm bán trước khi tạo đơn.');
      setSelectedCustomer(outlet);
      setScreen('visit');
      return;
    }
    if (data.gpsStatus === 'pending') {
      notify('Chờ Sup xác nhận vị trí GPS trước khi tạo đơn.');
      setSelectedCustomer(outlet);
      setScreen('visit');
      return;
    }
    const previous = data.orders.find((item) => item.outlet === outlet && item.lines.length > 0);
    const initialLines =
      reorder && previous
        ? Object.fromEntries(previous.lines.map((line) => [line.sku, line.quantity]))
        : { NS500: 10, TX330: 2 };
    updateData((current) => ({
      ...current,
      draftOutlet: outlet,
      draftLines:
        current.draftOutlet === outlet &&
        Object.values(current.draftLines).some((quantity) => quantity > 0)
          ? current.draftLines
          : initialLines,
    }));
    setScreen('order-form');
  };

  const sendOrder = () => {
    if (!draftProducts.length) {
      notify('Chọn ít nhất một sản phẩm trước khi gửi.');
      return;
    }
    if (!data.activeOutlet || data.activeOutlet !== data.draftOutlet) {
      notify('Đơn phải gắn với một lượt ghé đang mở.');
      return;
    }
    if (data.gpsStatus === 'pending') {
      notify('Chờ Sup xác nhận vị trí GPS trước khi gửi đơn.');
      return;
    }
    const maxId = Math.max(
      24081,
      ...data.orders.map((item) => Number(item.id.replace('SO-', '')) || 0),
    );
    const id = `SO-${maxId + 1}`;
    const key = uid();
    const sent = online;
    const nextOrder: SalesOrder = {
      id,
      outlet: data.draftOutlet,
      createdAt: `12/11/2026 · ${timeNow()}`,
      lines: draftProducts,
      ordered: draftQuantity,
      accepted: 0,
      shipped: 0,
      received: 0,
      freeGoods,
      total: draftSubtotal,
      status: sent ? 'Chờ NPP xác nhận' : 'Chờ gửi',
      syncState: sent ? 'sent' : 'queued',
      idempotencyKey: key,
      steps: [
        { label: 'Sales gửi', actor: people.sales.name, time: fullStamp(), state: 'done' },
        {
          label: 'NPP xác nhận lượng',
          actor: sent ? 'Đang chờ NPP' : 'Chờ mạng để gửi',
          time: sent ? 'Chờ xác nhận' : 'Chờ gửi',
          state: sent ? 'current' : 'waiting',
        },
        { label: 'Giao hàng', actor: 'Chưa phân công', time: 'Chưa giao', state: 'waiting' },
        {
          label: 'Điểm bán xác nhận thực nhận',
          actor: 'Chưa xác nhận',
          time: 'Chưa nhận',
          state: 'waiting',
        },
      ],
    };
    if (sent) syncedKeys.current.add(key);
    updateData((current) => ({
      ...current,
      orders: [nextOrder, ...current.orders],
      draftLines: {},
      reportSubmitted: false,
    }));
    setSelectedOrderId(id);
    setScreen('order-detail');
    navigator.vibrate?.(16);
    notify(
      sent
        ? `Đơn ${id} đã ghi nhận trong bản demo, chờ NPP Minh Phát xác nhận.`
        : `Đơn ${id} đã lưu trên máy · Chờ gửi.`,
      () => {
        updateData((current) => ({
          ...current,
          orders: current.orders.filter((item) => item.id !== id),
          draftOutlet: nextOrder.outlet,
          draftLines: Object.fromEntries(nextOrder.lines.map((line) => [line.sku, line.quantity])),
        }));
        setSelectedCustomer(nextOrder.outlet);
        setScreen('order-form');
      },
    );
  };

  const reorderFromLast = (outlet = data.draftOutlet || selectedCustomer) => {
    const previous = data.orders.find((item) => item.outlet === outlet && item.lines.length);
    if (!previous) {
      notify('Chưa có đơn cũ để đặt lại.');
      return;
    }
    if (data.activeOutlet !== previous.outlet) {
      setSelectedCustomer(previous.outlet);
      setScreen('visit');
      notify('Check-in đúng cửa hàng trước; đơn cũ đã được giữ để đặt lại.');
      return;
    }
    if (data.gpsStatus === 'pending') {
      setSelectedCustomer(previous.outlet);
      setScreen('visit');
      notify('Chờ Sup xác nhận vị trí GPS trước khi đặt lại đơn.');
      return;
    }
    updateData((current) => ({
      ...current,
      draftOutlet: previous.outlet,
      draftLines: Object.fromEntries(previous.lines.map((line) => [line.sku, line.quantity])),
    }));
    setScreen('order-form');
    notify('Đã lấy dòng hàng cũ; đơn vị thùng và giá sell-out hiện hành đã được kiểm tra lại.');
  };

  const createTicket = () => {
    if (!ticketTitle.trim()) {
      notify('Nhập nội dung phản ánh trước khi tạo phiếu.');
      return;
    }
    const count = data.tickets.reduce(
      (max, item) => Math.max(max, Number(item.id.replace('TK-', '')) || 0),
      331,
    );
    const next: Ticket = {
      id: `TK-${count + 1}`,
      title: ticketTitle.trim(),
      outlet: data.activeOutlet ?? selectedCustomer,
      orderId:
        data.orders.find((item) => item.outlet === (data.activeOutlet ?? selectedCustomer))?.id ??
        '',
      assignee: people.sales.name,
      status: 'Mới',
      due: '13/11/2026',
      kind: ticketKind,
    };
    updateData((current) => ({ ...current, tickets: [next, ...current.tickets] }));
    setTicketTitle('');
    notify(`Đã tạo ${next.id} và gắn với hồ sơ ${next.outlet}.`, () =>
      updateData((current) => ({
        ...current,
        tickets: current.tickets.filter((item) => item.id !== next.id),
      })),
    );
  };

  const createAppointment = () => {
    if (!appointmentTitle.trim()) {
      notify('Nhập nội dung lời hẹn trước khi lưu.');
      return;
    }
    const next: Appointment = {
      id: `AP-${uid().slice(0, 6)}`,
      outlet: data.activeOutlet ?? selectedCustomer,
      title: appointmentTitle.trim(),
      time: appointmentTime,
      owner: people.sales.name,
      status: 'Đã lên lịch',
    };
    updateData((current) => ({
      ...current,
      appointments: [...current.appointments, next],
    }));
    setAppointmentTitle('');
    notify(`Đã lưu lời hẹn ${next.time} · ${next.outlet}.`, () =>
      updateData((current) => ({
        ...current,
        appointments: current.appointments.filter((item) => item.id !== next.id),
      })),
    );
  };

  const checkIn = () => {
    if (data.activeOutlet && data.activeOutlet !== selectedCustomer) {
      notify(`Kết thúc lượt ghé ${data.activeOutlet} trước khi check-in điểm khác.`);
      return;
    }
    const previousData = data;
    const target = demoCoordinates[selectedCustomer];
    if (!navigator.geolocation) {
      requestGpsException(selectedCustomer, null, 'Thiết bị không hỗ trợ GPS.');
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (position) => {
        const distance = target
          ? distanceMeters(
              position.coords.latitude,
              position.coords.longitude,
              target.lat,
              target.lon,
            )
          : 1000;
        if (distance <= 200) {
          updateData((current) => ({
            ...current,
            ...(current.activeOutlet === selectedCustomer ? {} : resetVisit()),
            activeOutlet: selectedCustomer,
            checkInAt: Date.now(),
            gpsStatus: 'inside',
          }));
          notify(`Check-in thành công tại ${selectedCustomer} · ${Math.round(distance)}m.`, () =>
            updateData(() => previousData),
          );
        } else {
          requestGpsException(
            selectedCustomer,
            Math.round(distance),
            'Ngoài bán kính check-in 200m.',
          );
        }
      },
      () => requestGpsException(selectedCustomer, null, 'Không lấy được vị trí GPS.'),
      { enableHighAccuracy: true, timeout: 8000, maximumAge: 30000 },
    );
  };

  const requestGpsException = (outlet: string, distance: number | null, reason: string) => {
    const previousData = data;
    const exception: GpsException = {
      id: `GPS-${uid().slice(0, 6)}`,
      outlet,
      distance,
      status: 'Chờ Sup xác nhận',
      time: fullStamp(),
      reason,
    };
    updateData((current) => ({
      ...current,
      ...(current.activeOutlet === outlet ? {} : resetVisit()),
      activeOutlet: outlet,
      checkInAt: Date.now(),
      gpsStatus: 'pending',
      gpsExceptions: [exception, ...current.gpsExceptions],
    }));
    notify(`${reason} Đã gửi yêu cầu cho Sup; lượt ghé được lưu trên máy.`, () =>
      updateData(() => previousData),
    );
  };

  const checkOut = () => {
    if (!data.activeOutlet) return;
    const previousData = data;
    const checkedCount = Object.values(data.checklist).filter(Boolean).length;
    const history: VisitHistory = {
      outlet: data.activeOutlet,
      time: fullStamp(),
      summary: `${checkedCount}/4 mục · tồn đã ghi · ${data.visitNotes.trim() ? 'có ghi chú' : 'không có ghi chú'}`,
    };
    updateData((current) => ({
      ...current,
      activeOutlet: null,
      checkInAt: null,
      gpsStatus: null,
      completedStops: current.completedStops.includes(history.outlet)
        ? current.completedStops
        : [...current.completedStops, history.outlet],
      visitHistory: [history, ...current.visitHistory],
    }));
    setScreen('home');
    notify(`Đã kết thúc lượt ghé ${history.outlet}.`, () => {
      updateData(() => previousData);
      setSelectedCustomer(history.outlet);
      setScreen('visit');
    });
  };

  const approveGps = (id: string) => {
    const previousData = data;
    updateData((current) => ({
      ...current,
      gpsExceptions: current.gpsExceptions.map((item) =>
        item.id === id ? { ...item, status: 'Đã xác nhận' } : item,
      ),
      gpsStatus:
        current.gpsExceptions.find((item) => item.id === id)?.outlet === current.activeOutlet
          ? 'approved'
          : current.gpsStatus,
    }));
    notify('Đã xác nhận ngoại lệ GPS.', () => updateData(() => previousData));
  };

  const handoffVisit = () => {
    const previousData = data;
    const isActiveVisit = data.activeOutlet === handoffOutlet;
    const history: VisitHistory | null = isActiveVisit
      ? {
          outlet: handoffOutlet,
          time: fullStamp(),
          summary: `Đã bàn giao cho ${supervisorAssignee}`,
        }
      : null;
    updateData((current) => ({
      ...current,
      assignments: { ...current.assignments, [handoffOutlet]: supervisorAssignee },
      appointments: current.appointments.map((item) =>
        item.outlet === handoffOutlet ? { ...item, owner: supervisorAssignee } : item,
      ),
      ...(history
        ? {
            activeOutlet: null,
            checkInAt: null,
            gpsStatus: null,
            visitHistory: [history, ...current.visitHistory],
          }
        : {}),
    }));
    if (isActiveVisit) setScreen('home');
    notify(
      `Đã chuyển lượt ghé ${handoffOutlet} cho ${supervisorAssignee}; lịch sử vẫn được giữ.`,
      () => {
        updateData((current) => ({
          ...current,
          assignments: previousData.assignments,
          appointments: previousData.appointments,
          activeOutlet: previousData.activeOutlet,
          checkInAt: previousData.checkInAt,
          gpsStatus: previousData.gpsStatus,
          visitHistory: previousData.visitHistory,
        }));
        if (isActiveVisit) {
          setSelectedCustomer(handoffOutlet);
          setScreen('visit');
        }
      },
    );
  };

  const refresh = () => {
    setRefreshing(true);
    window.setTimeout(() => {
      setRefreshing(false);
      notify('Đã làm mới dữ liệu trên thiết bị.');
    }, 350);
  };

  const requestNotifications = async () => {
    if (!('Notification' in window)) {
      setNotificationPermission('unsupported');
      return;
    }
    try {
      const permission = await Notification.requestPermission();
      setNotificationPermission(permission);
      notify(
        permission === 'granted' ? 'Đã bật nhắc lịch trên thiết bị.' : 'Chưa bật thông báo đẩy.',
      );
    } catch {
      notify('Không thể bật thông báo trên thiết bị này.');
    }
  };

  const activeOrderCount = data.orders.filter((item) => item.status !== 'Đã hoàn tất').length;
  const selectedOrder = data.orders.find((item) => item.id === selectedOrderId) ?? order;
  const customerOrders = useMemo(
    () => data.orders.filter((item) => item.outlet === selectedCustomer),
    [data.orders, selectedCustomer],
  );

  const appTab: Tab =
    screen === 'customer-detail' || screen === 'visit'
      ? 'customers'
      : screen === 'order-form' || screen === 'order-detail'
        ? 'orders'
        : screen === 'report'
          ? 'more'
          : screen;
  const canShowTeam = role === 'sup';

  const startDirections = (outlet: string) => {
    const address = outletByName(outlet).address;
    window.open(
      `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(address)}`,
      '_blank',
      'noopener,noreferrer',
    );
  };

  const renderHome = () => {
    const nextStop = routeStops.find(
      (stop) => !data.completedStops.includes(stop.outlet) && !data.assignments[stop.outlet],
    );
    const remainingStopsHandedOff = routeStops.some(
      (stop) => !data.completedStops.includes(stop.outlet) && data.assignments[stop.outlet],
    );
    const openTickets = data.tickets.filter((item) => item.status !== 'Đã đóng').slice(0, 2);
    return (
      <>
        <section className="ms-greeting">
          <div>
            <span className="ms-eyebrow">THỨ NĂM · 12/11/2026</span>
            <h1>
              Chào Trần Hà <span>👋</span>
            </h1>
            <p>Hôm nay mình đi tuyến Quận 3 nhé.</p>
          </div>
          <button className="ms-icon-button" aria-label="Làm mới" onClick={refresh}>
            <RefreshCw className={refreshing ? 'ms-spin' : ''} size={20} />
          </button>
        </section>

        <section className="ms-metrics">
          <article className="ms-metric">
            <span>
              <ShoppingBag size={17} />
            </span>
            <small>ĐƠN HÔM NAY</small>
            <strong>{ordersToday.length}</strong>
          </article>
          <article className="ms-metric">
            <span>
              <Wallet size={17} />
            </span>
            <small>DOANH SỐ</small>
            <strong>{money(ordersToday.reduce((sum, item) => sum + item.total, 0))}</strong>
          </article>
          <article className="ms-metric">
            <span>
              <Route size={17} />
            </span>
            <small>TIẾN ĐỘ TUYẾN</small>
            <strong>
              {routeProgress}/{routeStops.length}
            </strong>
          </article>
        </section>

        {activeStore ? (
          <section className="ms-active-visit">
            <div className="ms-active-head">
              <span className="ms-live-dot" />
              <span>ĐANG GHÉ</span>
              <time>{formatElapsed(seconds)}</time>
            </div>
            <h2>{activeStore.name}</h2>
            <p>
              <MapPin size={15} /> {activeStore.address}
            </p>
            {data.gpsStatus === 'pending' && (
              <div className="ms-inline-warning">
                <AlertTriangle size={16} /> Chờ Sup xác nhận vị trí
              </div>
            )}
            <div className="ms-active-actions">
              <button onClick={() => startDirections(activeStore.name)}>
                <Navigation size={17} /> Chỉ đường
              </button>
              <button onClick={() => openVisit(activeStore.name)}>
                <ClipboardCheck size={17} /> Lượt ghé
              </button>
              <button onClick={() => startOrder(activeStore.name)}>
                <ShoppingCart size={17} /> Tạo đơn
              </button>
            </div>
          </section>
        ) : (
          <section className="ms-next-visit">
            <span className="ms-eyebrow">ĐIỂM CHƯA GHÉ</span>
            <h2>
              {nextStop?.outlet ??
                (remainingStopsHandedOff ? 'Đã bàn giao điểm còn lại' : 'Tuyến đã hoàn tất')}
            </h2>
            <p>
              <Clock3 size={15} /> {nextStop?.time ?? 'Hôm nay'} · Tuyến Quận 3
            </p>
            <button
              onClick={() => (nextStop ? openVisit(nextStop.outlet) : setScreen('customers'))}
            >
              {nextStop ? 'Chuẩn bị ghé' : 'Xem danh sách điểm'} <ArrowRight size={17} />
            </button>
          </section>
        )}

        <section className="ms-section">
          <div className="ms-section-title">
            <div>
              <h2>Lộ trình hôm nay</h2>
              <p>
                {routeProgress}/{routeStops.length} điểm đang thực hiện
              </p>
            </div>
            <Badge tone="blue">RS00301</Badge>
          </div>
          <div className="ms-route-list">
            {routeStops.map((stop) => {
              const isActive = data.activeOutlet === stop.outlet;
              const done = data.completedStops.includes(stop.outlet);
              const assignedTo = data.assignments[stop.outlet];
              const handedOff = !!assignedTo && assignedTo !== people.sales.name;
              return (
                <button
                  className={`ms-route-row ${isActive ? 'active' : ''}`}
                  key={stop.outlet}
                  onClick={() => openCustomer(stop.outlet)}
                >
                  <time>{stop.time}</time>
                  <span className={`ms-route-dot ${isActive ? 'active' : done ? 'done' : ''}`} />
                  <span className="ms-route-name">
                    <strong>{stop.outlet}</strong>
                    <small>{assignedTo ? `Người đi thay: ${assignedTo}` : stop.address}</small>
                  </span>
                  <Badge tone={isActive ? 'green' : done || handedOff ? 'neutral' : 'amber'}>
                    {isActive
                      ? 'Đang ghé'
                      : done
                        ? 'Đã ghé'
                        : handedOff
                          ? 'Đã bàn giao'
                          : 'Chưa ghé'}
                  </Badge>
                  <ChevronRight size={17} />
                </button>
              );
            })}
          </div>
        </section>

        <section className="ms-section">
          <div className="ms-section-title">
            <div>
              <h2>Việc cần xử lý</h2>
              <p>Lời hẹn và phiếu chăm sóc chưa hoàn tất</p>
            </div>
            <CalendarDays size={19} />
          </div>
          {data.appointments.slice(0, 2).map((item) => (
            <button className="ms-task-row" key={item.id} onClick={() => openCustomer(item.outlet)}>
              <span className="ms-task-time">{item.time}</span>
              <span>
                <strong>{item.title}</strong>
                <small>
                  {item.outlet} · {item.owner}
                </small>
              </span>
              <ChevronRight size={17} />
            </button>
          ))}
          {openTickets.map((item) => (
            <button className="ms-task-row" key={item.id} onClick={() => setScreen('more')}>
              <span className="ms-task-time">Phiếu</span>
              <span>
                <strong>{item.title}</strong>
                <small>
                  {item.assignee} · Hạn {item.due}
                </small>
              </span>
              <ChevronRight size={17} />
            </button>
          ))}
          {!data.appointments.length && !openTickets.length && (
            <div className="ms-empty compact">
              Chưa có việc đến hạn. Lưu lời hẹn từ lượt ghé để bắt đầu.
            </div>
          )}
          {data.tickets
            .filter((item) => item.status !== 'Đã đóng')
            .slice(0, 1)
            .map((item) => (
              <div className="ms-task-row" key={item.id}>
                <span className="ms-task-icon">
                  <Ticket size={17} />
                </span>
                <span>
                  <strong>{item.title}</strong>
                  <small>
                    {item.id} · Hạn {item.due} · {item.assignee}
                  </small>
                </span>
                <Badge tone="amber">{item.status}</Badge>
              </div>
            ))}
        </section>

        <section className="ms-section ms-quick-customer">
          <div className="ms-section-title">
            <div>
              <h2>Hồ sơ cần nhớ</h2>
              <p>Đơn và việc đang mở theo từng cửa hàng</p>
            </div>
            <button className="ms-text-button" onClick={() => setScreen('customers')}>
              Tất cả
            </button>
          </div>
          <button className="ms-customer-mini" onClick={() => openCustomer('Tạp hóa Lan')}>
            <span className="ms-avatar">L</span>
            <span>
              <strong>Tạp hóa Lan</strong>
              <small>SO-24081 · Chờ NPP xác nhận</small>
            </span>
            <ChevronRight size={18} />
          </button>
          <button className="ms-customer-mini" onClick={() => openCustomer('Quán Bình')}>
            <span className="ms-avatar orange">B</span>
            <span>
              <strong>Quán Bình</strong>
              <small>SO-24076 · Còn thiếu 2 thùng NS150</small>
            </span>
            <ChevronRight size={18} />
          </button>
        </section>
      </>
    );
  };

  const renderCustomers = () => (
    <>
      <section className="ms-page-intro">
        <span className="ms-eyebrow">Tuyến Quận 3 · 3 điểm bán</span>
        <h1>Khách hàng</h1>
        <p>Hồ sơ, đơn gần nhất và việc còn dở ở cùng một nơi.</p>
      </section>
      <label className="ms-search">
        <Store size={18} />
        <input
          onChange={(event) => setCustomerSearch(event.target.value)}
          placeholder="Tìm cửa hàng, người liên hệ"
          value={customerSearch}
        />
      </label>
      {routeStops
        .filter((stop) =>
          `${stop.outlet} ${stop.address} ${outletByName(stop.outlet).contact}`
            .toLowerCase()
            .includes(customerSearch.trim().toLowerCase()),
        )
        .map((stop) => {
          const record = outletByName(stop.outlet);
          const lastOrder = data.orders.find((item) => item.outlet === stop.outlet);
          return (
            <button
              className="ms-customer-card"
              key={stop.outlet}
              onClick={() => openCustomer(stop.outlet)}
            >
              <span className="ms-avatar">{stop.outlet[0]}</span>
              <span className="ms-customer-main">
                <strong>{stop.outlet}</strong>
                <small>
                  {record.contact} · {record.address}
                </small>
                <span className="ms-customer-tags">
                  {lastOrder ? (
                    <Badge tone={lastOrder.syncState === 'queued' ? 'amber' : 'blue'}>
                      {lastOrder.id} · {lastOrder.status}
                    </Badge>
                  ) : (
                    <Badge>Chưa có đơn gần đây</Badge>
                  )}
                </span>
              </span>
              <ChevronRight size={18} />
            </button>
          );
        })}
      {!routeStops.some((stop) =>
        `${stop.outlet} ${stop.address} ${outletByName(stop.outlet).contact}`
          .toLowerCase()
          .includes(customerSearch.trim().toLowerCase()),
      ) && (
        <div className="ms-empty compact">
          Không tìm thấy cửa hàng. Thử tên cửa hàng hoặc người liên hệ khác.
        </div>
      )}
      <div className="ms-info-card">
        <ShieldCheck size={19} />
        <span>Chỉ hiển thị cửa hàng trong phạm vi tuyến Quận 3 của bạn.</span>
      </div>
    </>
  );

  const renderCustomerDetail = () => {
    const lastOrder = customerOrders[0];
    const customerAppointments = data.appointments.filter((item) => item.outlet === customer.name);
    const hasOpenTicket = data.tickets.find(
      (item) => item.outlet === customer.name && item.status !== 'Đã đóng',
    );
    return (
      <>
        <button className="ms-back-link" onClick={() => setScreen('customers')}>
          <ArrowLeft size={17} /> Danh sách khách hàng
        </button>
        <section className="ms-profile-card">
          <div className="ms-profile-hero">
            <span className="ms-avatar large">{customer.contact[0]}</span>
            <div>
              <span className="ms-eyebrow">HỒ SƠ 360°</span>
              <h1>{customer.name}</h1>
              <p>
                {customer.route} · {customer.status}
              </p>
            </div>
          </div>
          <a
            className="ms-contact-row"
            href={`tel:${phoneNumbers[customer.name] ?? phoneNumbers['Tạp hóa Lan']}`}
          >
            <Phone size={18} />
            <span>
              <small>Người liên hệ</small>
              <strong>{customer.contact} · Gọi cửa hàng</strong>
            </span>
            <ChevronRight size={17} />
          </a>
          <div className="ms-contact-row">
            <MapPin size={18} />
            <span>
              <small>Địa chỉ</small>
              <strong>{customer.address}</strong>
            </span>
          </div>
          <div className="ms-contact-row">
            <Store size={18} />
            <span>
              <small>NPP phục vụ</small>
              <strong>Minh Phát · chị Mai</strong>
            </span>
          </div>
          <div className="ms-contact-row">
            <UserRound size={18} />
            <span>
              <small>Phụ trách</small>
              <strong>Trần Hà · Sales tuyến Quận 3</strong>
            </span>
          </div>
        </section>
        <section className="ms-section">
          <div className="ms-section-title">
            <div>
              <h2>Lời hẹn & việc còn dở</h2>
              <p>Việc cần nói với khách trong lần ghé này</p>
            </div>
            <MessageCircle size={19} />
          </div>
          {customerAppointments.map((item) => (
            <div className="ms-task-row" key={item.id}>
              <span className="ms-task-time">{item.time}</span>
              <span>
                <strong>{item.title}</strong>
                <small>
                  {item.owner} · {item.status}
                </small>
              </span>
            </div>
          ))}
          {hasOpenTicket && (
            <div className="ms-ticket-inline">
              <Ticket size={17} />
              <span>
                <strong>
                  {hasOpenTicket.id} · {hasOpenTicket.title}
                </strong>
                <small>
                  {hasOpenTicket.status} · Hạn {hasOpenTicket.due} · {hasOpenTicket.assignee}
                </small>
              </span>
            </div>
          )}
          {customer.name === 'Quán Bình' && (
            <div className="ms-ticket-inline">
              <Package size={17} />
              <span>
                <strong>SO-24076 · giao thiếu 2 thùng NS150</strong>
                <small>NPP Minh Phát đang chuẩn bị giao bù ngày 13/11</small>
              </span>
            </div>
          )}
          {!customerAppointments.length && !hasOpenTicket && customer.name !== 'Quán Bình' && (
            <div className="ms-empty compact">
              Chưa có lời hẹn hoặc việc đang mở cho cửa hàng này.
            </div>
          )}
        </section>
        <section className="ms-section">
          <div className="ms-section-title">
            <div>
              <h2>Đơn gần nhất</h2>
              <p>Tiến độ và lượng thực tế</p>
            </div>
            <button className="ms-text-button" onClick={() => setScreen('orders')}>
              Tất cả đơn
            </button>
          </div>
          {lastOrder ? (
            <button
              className="ms-order-mini"
              onClick={() => {
                setSelectedOrderId(lastOrder.id);
                setScreen('order-detail');
              }}
            >
              <span>
                <strong>{lastOrder.id}</strong>
                <small>
                  {lastOrder.lines.map((line) => `${line.quantity} × ${line.sku}`).join(' · ')}
                </small>
              </span>
              <Badge tone={lastOrder.status.includes('thiếu') ? 'amber' : 'blue'}>
                {lastOrder.status}
              </Badge>
            </button>
          ) : (
            <div className="ms-empty">Chưa có đơn gần đây. Có thể tạo đơn sau khi check-in.</div>
          )}
        </section>
        <section className="ms-section">
          <div className="ms-section-title">
            <div>
              <h2>Hoạt động gần đây</h2>
              <p>Lịch sử ghé và tương tác</p>
            </div>
          </div>
          <div className="ms-timeline">
            {customer.name === 'Tạp hóa Lan' ? (
              <>
                <div>
                  <i />
                  <span>
                    <strong>SO-24081 · Chờ NPP xác nhận</strong>
                    <small>12/11 · Sales Trần Hà gửi từ lượt ghé</small>
                  </span>
                </div>
                <div>
                  <i />
                  <span>
                    <strong>Chốt lại trà xanh nếu còn CTKM</strong>
                    <small>Lời hẹn hôm nay · trước 10:30</small>
                  </span>
                </div>
                <div>
                  <i />
                  <span>
                    <strong>Đã ghé cửa hàng</strong>
                    <small>12/11 · 08:15 · Trần Hà</small>
                  </span>
                </div>
              </>
            ) : customer.name === 'Quán Bình' ? (
              <>
                <div>
                  <i />
                  <span>
                    <strong>TK-331 · NPP xử lý giao bù</strong>
                    <small>Hạn 13/11 · Trần Hà theo dõi</small>
                  </span>
                </div>
                <div>
                  <i />
                  <span>
                    <strong>SO-24076 · Đã nhận 7/9 thùng</strong>
                    <small>11/11 · Thiếu 2 thùng NS150</small>
                  </span>
                </div>
              </>
            ) : (
              <div>
                <i />
                <span>
                  <strong>Chưa có hoạt động gần đây</strong>
                  <small>Đi ghé cửa hàng để bắt đầu chăm sóc.</small>
                </span>
              </div>
            )}
          </div>
        </section>
        {lastOrder && (
          <button
            className="ms-secondary-button ms-reorder-button"
            onClick={() => reorderFromLast(customer.name)}
          >
            <RefreshCw size={17} /> ĐẶT LẠI TỪ ĐƠN GẦN NHẤT
          </button>
        )}
      </>
    );
  };

  const renderVisit = () => (
    <>
      <button className="ms-back-link" onClick={() => setScreen('customer-detail')}>
        <ArrowLeft size={17} /> Hồ sơ {customer.name}
      </button>
      <section className="ms-visit-card">
        <div className="ms-visit-heading">
          <span className="ms-avatar">{customer.contact[0]}</span>
          <div>
            <span className="ms-eyebrow">
              {data.activeOutlet === customer.name ? 'ĐANG TRONG LƯỢT GHÉ' : 'CHUẨN BỊ GHÉ'}
            </span>
            <h1>{customer.name}</h1>
            <p>{customer.address}</p>
          </div>
        </div>
        {data.activeOutlet === customer.name ? (
          <div className="ms-checkin-status">
            <MapPin size={17} />
            <span>
              {data.gpsStatus === 'pending'
                ? 'Đã ghi lượt · chờ Sup xác nhận GPS'
                : data.gpsStatus === 'approved'
                  ? 'Sup đã xác nhận ngoại lệ GPS'
                  : 'Đã check-in · GPS trong bán kính 200m'}
            </span>
            <strong>{formatElapsed(seconds)}</strong>
          </div>
        ) : (
          <p className="ms-gps-demo-note">Tọa độ cửa hàng đang dùng dữ liệu mẫu trong bản demo.</p>
        )}
        {data.gpsExceptions.find(
          (item) => item.outlet === customer.name && item.status === 'Chờ Sup xác nhận',
        ) && (
          <div className="ms-inline-warning">
            <AlertTriangle size={16} /> Ngoài vùng GPS · đã gửi yêu cầu xác nhận cho Sup.
          </div>
        )}
      </section>
      <section className="ms-section">
        <div className="ms-section-title">
          <div>
            <h2>Checklist điểm bán</h2>
            <p>Chạm để đánh dấu sau khi kiểm tra</p>
          </div>
          <Badge tone="blue">{Object.values(data.checklist).filter(Boolean).length}/4</Badge>
        </div>
        <div className="ms-checklist">
          {checklistItems.map((item) => (
            <button
              className={`ms-check-row ${data.checklist[item] ? 'checked' : ''}`}
              key={item}
              onClick={() =>
                updateData((current) => ({
                  ...current,
                  checklist: { ...current.checklist, [item]: !current.checklist[item] },
                }))
              }
            >
              <span>{data.checklist[item] ? <Check size={17} /> : null}</span>
              {item}
            </button>
          ))}
        </div>
      </section>
      <section className="ms-section">
        <div className="ms-section-title">
          <div>
            <h2>Tồn tại điểm bán</h2>
            <p>Ghi nhanh số thùng còn trên kệ</p>
          </div>
          <Package size={19} />
        </div>
        {products.map((product) => (
          <div className="ms-stock-row" key={product.sku}>
            <span>
              <strong>{product.sku}</strong>
              <small>{product.name}</small>
            </span>
            <QuantityControl
              label={`tồn ${product.sku}`}
              value={data.stock[product.sku] ?? 0}
              onChange={(quantity) =>
                updateData((current) => ({
                  ...current,
                  stock: { ...current.stock, [product.sku]: quantity },
                }))
              }
            />
          </div>
        ))}
      </section>
      <section className="ms-section">
        <div className="ms-section-title">
          <div>
            <h2>Nhu cầu & ảnh kệ</h2>
            <p>Không cần ghi lại thông tin đã có trong hồ sơ</p>
          </div>
          <Camera size={19} />
        </div>
        <label className="ms-photo-picker">
          <ImagePlus size={20} />
          <span>{data.visitPhotoName || 'Chụp ảnh kệ (nếu cần)'}</span>
          <input
            accept="image/*"
            capture="environment"
            onChange={(event) => readVisitPhoto(event.target.files?.[0], updateData, notify)}
            type="file"
          />
        </label>
        {data.visitPhotoData && (
          <img className="ms-visit-photo" src={data.visitPhotoData} alt="Ảnh kệ tại điểm bán" />
        )}
        <label className="ms-field">
          Nhu cầu hoặc lý do chưa mua
          <textarea
            onChange={(event) =>
              updateData((current) => ({ ...current, visitNotes: event.target.value }))
            }
            placeholder="Ví dụ: còn tồn đủ, hẹn lấy trà xanh tuần sau"
            value={data.visitNotes}
          />
        </label>
      </section>
      <section className="ms-section">
        <div className="ms-section-title">
          <div>
            <h2>Tạo lời hẹn</h2>
            <p>Luôn có người phụ trách và giờ hẹn</p>
          </div>
          <CalendarDays size={19} />
        </div>
        <label className="ms-field">
          Nội dung hẹn
          <input
            onChange={(event) => setAppointmentTitle(event.target.value)}
            placeholder="Ví dụ: báo lại CTKM trà xanh"
            value={appointmentTitle}
          />
        </label>
        <div className="ms-form-row">
          <label className="ms-field">
            Giờ hẹn
            <input
              onChange={(event) => setAppointmentTime(event.target.value)}
              type="time"
              value={appointmentTime}
            />
          </label>
          <label className="ms-field">
            Phụ trách
            <input readOnly value={people.sales.name} />
          </label>
        </div>
        <button className="ms-secondary-button ms-full-button" onClick={createAppointment}>
          <CalendarDays size={17} /> LƯU LỜI HẸN
        </button>
      </section>
      <section className="ms-section">
        <div className="ms-section-title">
          <div>
            <h2>Phản ánh từ lượt ghé</h2>
            <p>Giao thiếu, hỏng hoặc POSM</p>
          </div>
          <Headphones size={19} />
        </div>
        <div className="ms-form-row">
          <label className="ms-field">
            Loại phản ánh
            <select onChange={(event) => setTicketKind(event.target.value)} value={ticketKind}>
              <option>Giao thiếu / hỏng</option>
              <option>POSM</option>
              <option>Chất lượng</option>
              <option>Khác</option>
            </select>
          </label>
          <label className="ms-field">
            Hạn xử lý
            <input readOnly value="13/11/2026" />
          </label>
        </div>
        <label className="ms-field">
          Nội dung phiếu
          <input
            onChange={(event) => setTicketTitle(event.target.value)}
            placeholder="Mô tả ngắn vấn đề cần hỗ trợ"
            value={ticketTitle}
          />
        </label>
        <button className="ms-secondary-button ms-full-button" onClick={createTicket}>
          <Ticket size={17} /> TẠO PHIẾU GẮN VỚI LƯỢT GHÉ
        </button>
      </section>
      {data.activeOutlet === customer.name && (
        <div className="ms-visit-summary">
          <strong>Tổng kết lượt ghé</strong>
          <span>
            {Object.values(data.checklist).filter(Boolean).length}/4 checklist ·{' '}
            {Object.values(data.stock).reduce((sum, count) => sum + count, 0)} thùng tồn đã ghi
          </span>
        </div>
      )}
    </>
  );

  const renderOrderForm = () => (
    <>
      <button className="ms-back-link" onClick={() => setScreen('home')}>
        <ArrowLeft size={17} /> Lượt ghé {data.draftOutlet}
      </button>
      <section className="ms-page-intro">
        <span className="ms-eyebrow">SELL-OUT · GỬI NPP MINH PHÁT</span>
        <h1>Đơn tại {data.draftOutlet}</h1>
        <p>Giá sell-out hiệu lực tự áp dụng · không nhập tay đơn giá.</p>
      </section>
      <div className="ms-order-context">
        <MapPin size={17} />
        <span>Gắn với lượt ghé đang mở</span>
        <Badge tone="green">Đã check-in</Badge>
      </div>
      <button className="ms-reorder-link" onClick={() => reorderFromLast(data.draftOutlet)}>
        <RefreshCw size={17} />
        <span>
          <strong>ĐẶT LẠI TỪ ĐƠN GẦN NHẤT</strong>
          <small>Lấy dòng cũ, kiểm tra lại thùng và giá hiện hành</small>
        </span>
        <ChevronRight size={17} />
      </button>
      {data.draftOutlet === 'Tạp hóa Lan' && (
        <div className="ms-inline-warning">
          <AlertTriangle size={16} />
          <span>Đơn cũ dùng đơn vị thùng. Kiểm tra thùng/lốc trước khi gửi.</span>
        </div>
      )}
      <section className="ms-section ms-product-list">
        <div className="ms-section-title">
          <div>
            <h2>Sản phẩm & ưu đãi</h2>
            <p>Bảng giá hiệu lực đến 30/11/2026</p>
          </div>
          <Badge tone="green">SELL-OUT</Badge>
        </div>
        {products.map((product) => {
          const quantity = data.draftLines[product.sku] ?? 0;
          const gift = product.sku === 'NS500' ? Math.floor(quantity / 10) : 0;
          const discount = product.sku === 'TX330' && quantity >= 8;
          return (
            <article className="ms-product-row" key={product.sku}>
              <div className={`ms-product-art ${product.sku.toLowerCase()}`}>
                <Package size={22} />
              </div>
              <div className="ms-product-info">
                <span>
                  {product.sku} · {product.pack}
                </span>
                <strong>{product.name}</strong>
                <b>
                  {money(product.sellOut)} <small>/ thùng</small>
                </b>
                {product.promotion && (
                  <Badge tone="amber">
                    <Ticket size={12} /> {product.promotion}
                  </Badge>
                )}
                {discount && (
                  <small className="ms-discount-applied">Đã áp dụng chiết khấu 3%</small>
                )}
                {gift > 0 && (
                  <small className="ms-free-goods">
                    Tặng {gift} thùng NS500 · hàng tặng không tính tiền
                  </small>
                )}
              </div>
              <QuantityControl
                label={`số lượng ${product.sku}`}
                value={quantity}
                onChange={(value) => setDraftQuantity(product.sku, value)}
              />
            </article>
          );
        })}
      </section>
      <div className="ms-order-summary">
        <div>
          <span>Tổng số lượng</span>
          <strong>{draftQuantity} thùng</strong>
        </div>
        {freeGoods > 0 && (
          <div>
            <span>Hàng tặng</span>
            <strong>{freeGoods} thùng · không tính tiền</strong>
          </div>
        )}
        <div className="total">
          <span>Thành tiền sell-out</span>
          <strong>{money(draftSubtotal)}</strong>
        </div>
      </div>
    </>
  );

  const renderOrders = () => (
    <>
      <section className="ms-page-intro">
        <span className="ms-eyebrow">SELL-OUT · NPP BÁN CHO ĐIỂM BÁN</span>
        <h1>Đơn hàng</h1>
        <p>
          {activeOrderCount} đơn đang theo dõi · lượng xác nhận, thực xuất và thực nhận luôn tách
          riêng.
        </p>
      </section>
      {data.orders.map((item) => (
        <button
          className="ms-order-card"
          key={item.id}
          onClick={() => {
            setSelectedOrderId(item.id);
            setScreen('order-detail');
          }}
        >
          <div className="ms-order-card-head">
            <span>
              <strong>{item.id}</strong>
              <small>
                {item.outlet} · {item.createdAt}
              </small>
            </span>
            <Badge
              tone={
                item.syncState === 'queued'
                  ? 'amber'
                  : item.status.includes('thiếu')
                    ? 'amber'
                    : 'blue'
              }
            >
              {item.syncState === 'queued' ? 'Chờ gửi' : item.status}
            </Badge>
          </div>
          <div className="ms-order-counts">
            <span>
              ĐÃ ĐẶT<strong>{item.ordered}</strong>
            </span>
            <span>
              NPP XÁC NHẬN<strong>{item.accepted}</strong>
            </span>
            <span>
              THỰC XUẤT<strong>{item.shipped}</strong>
            </span>
            <span>
              THỰC NHẬN<strong>{item.received}</strong>
            </span>
          </div>
          <div className="ms-order-card-bottom">
            <span>{item.lines.map((line) => `${line.quantity} × ${line.sku}`).join(' · ')}</span>
            <strong>{money(item.total)}</strong>
            <ChevronRight size={17} />
          </div>
          {item.syncState === 'queued' && (
            <div className="ms-queued-note">
              <CloudOff size={15} /> Lưu trên máy · tự gửi khi có mạng · mã chống trùng đã tạo
            </div>
          )}
          {item.resolution && (
            <div className="ms-queued-note">
              <TruckIcon /> {item.resolution}
            </div>
          )}
        </button>
      ))}
      {!data.orders.length && (
        <div className="ms-empty">
          <ShoppingBag size={26} />
          <strong>Chưa có đơn nào</strong>
          <span>Đơn tạo từ lượt ghé sẽ xuất hiện ở đây.</span>
        </div>
      )}
      <div className="ms-info-card">
        <ShieldCheck size={19} />
        <span>Đã tạo đơn ≠ đã giao đủ ≠ điểm bán đã nhận đủ.</span>
      </div>
    </>
  );

  const renderOrderDetail = () => {
    if (!selectedOrder) return <div className="ms-empty">Không tìm thấy đơn hàng.</div>;
    return (
      <>
        <button className="ms-back-link" onClick={() => setScreen('orders')}>
          <ArrowLeft size={17} /> Tất cả đơn hàng
        </button>
        <section className="ms-order-detail-head">
          <span className="ms-eyebrow">SELL-OUT · GỬI NPP MINH PHÁT</span>
          <div>
            <h1>{selectedOrder.id}</h1>
            <Badge tone={selectedOrder.syncState === 'queued' ? 'amber' : 'blue'}>
              {selectedOrder.syncState === 'queued' ? 'Chờ gửi' : selectedOrder.status}
            </Badge>
          </div>
          <p>
            {selectedOrder.outlet} · {selectedOrder.createdAt}
          </p>
          {selectedOrder.syncState === 'queued' && (
            <div className="ms-offline-callout">
              <CloudOff size={18} />
              <span>
                Đơn đang lưu trên máy. Có mạng sẽ kiểm tra lại giá sell-out rồi gửi; khóa trùng{' '}
                {selectedOrder.idempotencyKey.slice(0, 10)}…
              </span>
            </div>
          )}
        </section>
        <section className="ms-section">
          <div className="ms-section-title">
            <div>
              <h2>Tiến độ & người xử lý</h2>
              <p>Mỗi bước ghi nhận người và thời gian</p>
            </div>
            <Route size={19} />
          </div>
          <OrderProgress order={selectedOrder} />
        </section>
        <section className="ms-section">
          <div className="ms-section-title">
            <div>
              <h2>Số lượng theo từng bước</h2>
              <p>Đơn vị: thùng</p>
            </div>
          </div>
          <div className="ms-order-counts detail">
            <span>
              SỐ ĐẶT<strong>{selectedOrder.ordered}</strong>
            </span>
            <span>
              NPP XÁC NHẬN<strong>{selectedOrder.accepted}</strong>
            </span>
            <span>
              THỰC XUẤT<strong>{selectedOrder.shipped}</strong>
            </span>
            <span>
              THỰC NHẬN<strong>{selectedOrder.received}</strong>
            </span>
          </div>
        </section>
        <section className="ms-section">
          <div className="ms-section-title">
            <div>
              <h2>Sản phẩm trong đơn</h2>
              <p>Giá sell-out hiệu lực</p>
            </div>
          </div>
          {selectedOrder.lines.map((line) => {
            const discounted = line.sku === 'TX330' && line.quantity >= 8;
            const lineTotal = Math.round(line.quantity * line.unitPrice * (discounted ? 0.97 : 1));
            return (
              <div className="ms-order-line" key={line.sku}>
                <span>
                  <strong>
                    {line.sku} · {line.name}
                  </strong>
                  <small>
                    {line.quantity} thùng × {money(line.unitPrice)}
                    {discounted ? ' · đã trừ 3%' : ''}
                  </small>
                </span>
                <b>{money(lineTotal)}</b>
              </div>
            );
          })}
          {selectedOrder.freeGoods > 0 && (
            <div className="ms-gift-line">
              <Ticket size={16} /> Tặng {selectedOrder.freeGoods} thùng NS500 · không tính tiền
            </div>
          )}
          <div className="ms-total-line">
            <span>Thành tiền</span>
            <strong>{money(selectedOrder.total)}</strong>
          </div>
          {selectedOrder.resolution && (
            <div className="ms-resolution-card">
              <TruckIcon />
              {selectedOrder.resolution}
              <small>Đang ở bước NPP chuẩn bị giao bù · Hạn 13/11</small>
            </div>
          )}
        </section>
        <div className="ms-info-card">
          <AlertTriangle size={19} />
          <span>Đã tạo đơn ≠ đã giao đủ ≠ điểm bán đã nhận đủ.</span>
        </div>
      </>
    );
  };

  const renderCalendar = () => (
    <>
      <section className="ms-page-intro">
        <span className="ms-eyebrow">THỨ NĂM · 12/11/2026</span>
        <h1>Lịch & lời hẹn</h1>
        <p>Nhắc đúng người, đúng giờ; không tự đóng việc quá hạn.</p>
      </section>
      {data.appointments.map((item) => (
        <button
          className="ms-calendar-card"
          key={item.id}
          onClick={() => openCustomer(item.outlet)}
        >
          <div className="ms-calendar-time">
            <Clock3 size={17} />
            <strong>{item.time}</strong>
          </div>
          <div className="ms-calendar-content">
            <strong>{item.title}</strong>
            <small>
              {item.outlet} · Phụ trách: {item.owner}
            </small>
            <Badge tone={item.status === 'Đến hạn' ? 'amber' : 'blue'}>{item.status}</Badge>
          </div>
          <ChevronRight size={18} />
        </button>
      ))}
      {data.tickets
        .filter((item) => item.status !== 'Đã đóng')
        .map((item) => (
          <button
            className="ms-calendar-card"
            key={item.id}
            onClick={() => openCustomer(item.outlet)}
          >
            <div className="ms-calendar-time">
              <Ticket size={17} />
              <strong>{item.due.slice(0, 5)}</strong>
            </div>
            <div className="ms-calendar-content">
              <strong>
                {item.id} · {item.title}
              </strong>
              <small>
                {item.outlet} · {item.assignee}
              </small>
              <Badge tone="amber">Hạn xử lý {item.due}</Badge>
            </div>
            <ChevronRight size={18} />
          </button>
        ))}
      {!data.appointments.length && !data.tickets.some((item) => item.status !== 'Đã đóng') && (
        <div className="ms-empty compact">
          Chưa có lời hẹn hoặc việc đến hạn. Lưu lời hẹn từ hồ sơ điểm bán để bắt đầu.
        </div>
      )}
      <section className="ms-section">
        <div className="ms-section-title">
          <div>
            <h2>Ghé trong ngày</h2>
            <p>Chạm để mở hồ sơ 360°</p>
          </div>
        </div>
        {routeStops.map((stop) => (
          <button
            className="ms-task-row"
            key={stop.outlet}
            onClick={() => openCustomer(stop.outlet)}
          >
            <span className="ms-task-time">{stop.time}</span>
            <span>
              <strong>{stop.outlet}</strong>
              <small>{stop.address}</small>
            </span>
            <ChevronRight size={17} />
          </button>
        ))}
      </section>
    </>
  );

  const renderMore = () => (
    <>
      <section className="ms-page-intro">
        <span className="ms-eyebrow">TRẦN HÀ · SUỐI VIỆT</span>
        <h1>Thêm</h1>
        <p>Công cụ tuyến, báo cáo và cài đặt demo.</p>
      </section>
      <section className="ms-network-card">
        {online ? <Cloud size={21} /> : <CloudOff size={21} />}
        <div>
          <strong>{online ? 'Đang có mạng' : 'Đang ngoại tuyến'}</strong>
          <small>
            {online
              ? 'Đơn mới sẽ ghi nhận và chờ NPP xác nhận.'
              : 'Đơn nháp lưu trên thiết bị · tự gửi khi có mạng.'}
          </small>
        </div>
        <Badge tone={online ? 'green' : 'amber'}>{online ? 'ONLINE' : 'OFFLINE'}</Badge>
      </section>
      <button className="ms-menu-row" onClick={() => setScreen('report')}>
        <FileText size={20} />
        <span>
          <strong>Báo cáo ngày</strong>
          <small>Tuyến, điểm có đơn, doanh số, tỷ lệ chuyển đổi</small>
        </span>
        <ChevronRight size={18} />
      </button>
      <button className="ms-menu-row" onClick={requestNotifications}>
        <Bell size={20} />
        <span>
          <strong>Nhắc lịch & thông báo</strong>
          <small>
            {notificationPermission === 'granted'
              ? 'Đã bật trên thiết bị'
              : 'Bật thông báo trên trình duyệt'}
          </small>
        </span>
        <ChevronRight size={18} />
      </button>
      <section className="ms-ai-card">
        <div className="ms-ai-heading">
          <span>
            <Sparkles size={18} />
          </span>
          <Badge tone="neutral">PHASE 2</Badge>
        </div>
        <h2>Trợ lý tại điểm bán</h2>
        <p>Gợi ý chấm trưng bày từ ảnh kệ hoặc tạo dòng đơn nháp từ giọng nói.</p>
        <strong>Gợi ý – cần bạn kiểm tra</strong>
        <small>Không tự gửi đơn, không tự duyệt. Tính năng đang chờ bật cờ.</small>
        <button disabled={!featureFlags.aiSuggestionsEnabled}>
          <Sparkles size={16} /> Dùng gợi ý AI
        </button>
      </section>
      <section className="ms-section">
        <div className="ms-section-title">
          <div>
            <h2>Chuyển vai demo</h2>
            <p>Vai trò hiện tại: {role === 'sales' ? 'Sales Trần Hà' : 'Sup / ASM'}</p>
          </div>
          <Users size={19} />
        </div>
        <div className="ms-role-switch">
          <button className={role === 'sales' ? 'selected' : ''} onClick={() => setRole('sales')}>
            <UserRound size={18} />
            <span>
              Sales
              <br />
              <small>Trần Hà</small>
            </span>
            {role === 'sales' && <Check size={16} />}
          </button>
          <button className={role === 'sup' ? 'selected' : ''} onClick={() => setRole('sup')}>
            <Users size={18} />
            <span>
              Sup / ASM
              <br />
              <small>Trần Mỹ</small>
            </span>
            {role === 'sup' && <Check size={16} />}
          </button>
        </div>
      </section>
      {role === 'sup' && (
        <button className="ms-menu-row" onClick={() => setScreen('team')}>
          <Users size={20} />
          <span>
            <strong>Đội Quận 3</strong>
            <small>Sales, ngoại lệ GPS và chuyển lượt ghé</small>
          </span>
          <ChevronRight size={18} />
        </button>
      )}
      <div className="ms-disclaimer">
        <ShieldCheck size={17} /> Dữ liệu demo lưu trên thiết bị; kết nối gửi đơn thật tới NPP chưa
        được cấu hình.
      </div>
    </>
  );

  const renderReport = () => {
    const visits = data.completedStops.length + (data.activeOutlet ? 1 : 0);
    const dailyOrders = data.orders.filter((item) => item.createdAt.startsWith('12/11'));
    const dailySales = dailyOrders.reduce((sum, item) => sum + item.total, 0);
    const conversion = visits ? Math.round((dailyOrders.length / visits) * 100) : 0;
    const dailyTickets = data.tickets.filter((item) => item.status !== 'Đã đóng').slice(0, 2);
    const hasDailyEntries =
      data.visitHistory.length > 0 || dailyOrders.length > 0 || dailyTickets.length > 0;
    return (
      <>
        <button className="ms-back-link" onClick={() => setScreen('more')}>
          <ArrowLeft size={17} /> Thêm
        </button>
        <section className="ms-page-intro">
          <span className="ms-eyebrow">12/11/2026 · TẠM TÍNH</span>
          <h1>Báo cáo ngày</h1>
          <p>Được tổng hợp từ lượt ghé, đơn và phản ánh đã ghi.</p>
        </section>
        <div className="ms-report-grid">
          <div>
            <small>LƯỢT GHÉ</small>
            <strong>{visits}</strong>
          </div>
          <div>
            <small>ĐIỂM CÓ ĐƠN</small>
            <strong>{new Set(dailyOrders.map((item) => item.outlet)).size}</strong>
          </div>
          <div>
            <small>DOANH SỐ</small>
            <strong>{money(dailySales)}</strong>
          </div>
          <div>
            <small>CHUYỂN ĐỔI</small>
            <strong>{conversion}%</strong>
          </div>
        </div>
        <section className="ms-section">
          <div className="ms-section-title">
            <div>
              <h2>Đã ghi nhận</h2>
              <p>Kiểm tra rồi gửi báo cáo</p>
            </div>
            <CheckCircle2 size={19} />
          </div>
          {data.visitHistory.slice(0, 3).map((item, index) => (
            <div className="ms-task-row" key={`${item.outlet}-${index}`}>
              <span className="ms-task-icon">
                <MapPin size={17} />
              </span>
              <span>
                <strong>{item.outlet}</strong>
                <small>
                  {item.time} · {item.summary}
                </small>
              </span>
            </div>
          ))}
          {dailyOrders.map((item) => (
            <div className="ms-task-row" key={item.id}>
              <span className="ms-task-icon">
                <ShoppingBag size={17} />
              </span>
              <span>
                <strong>
                  {item.id} · {item.outlet}
                </strong>
                <small>
                  {money(item.total)} · {item.status}
                </small>
              </span>
            </div>
          ))}
          {dailyTickets.map((item) => (
            <div className="ms-task-row" key={item.id}>
              <span className="ms-task-icon">
                <Ticket size={17} />
              </span>
              <span>
                <strong>
                  {item.id} · {item.title}
                </strong>
                <small>
                  {item.status} · {item.assignee}
                </small>
              </span>
            </div>
          ))}
          {!hasDailyEntries && (
            <div className="ms-empty compact">
              Chưa có hoạt động để tổng hợp. Hãy check-in, ghi đơn hoặc tạo phản ánh trước khi gửi.
            </div>
          )}
        </section>
        <label className="ms-field">
          Bổ sung ghi chú ngày
          <textarea
            onChange={(event) =>
              updateData((current) => ({
                ...current,
                reportNote: event.target.value,
                reportSubmitted: false,
              }))
            }
            placeholder="Ghi điều cần Sup nắm..."
            value={data.reportNote}
          />
        </label>
        {data.reportSubmitted && (
          <div className="ms-success-note">
            <CheckCircle2 size={17} /> Đã gửi báo cáo ngày lúc {timeNow()}.
          </div>
        )}
      </>
    );
  };

  const renderTeam = () => (
    <>
      <section className="ms-page-intro">
        <span className="ms-eyebrow">SUP / ASM · TP.HCM</span>
        <h1>Đội Quận 3</h1>
        <p>Phạm vi: tuyến Quận 3 · Trần Hà đang đi tuyến.</p>
      </section>
      <section className="ms-team-member">
        <span className="ms-avatar">H</span>
        <div>
          <strong>Trần Hà</strong>
          <small>
            Sales · RS00301 ·{' '}
            {data.activeOutlet ? `Đang ghé ${data.activeOutlet}` : 'Chưa check-in'}
          </small>
        </div>
        <Badge tone={data.activeOutlet ? 'green' : 'neutral'}>
          {data.activeOutlet ? 'Đang tuyến' : 'Sẵn sàng'}
        </Badge>
      </section>
      <section className="ms-section">
        <div className="ms-section-title">
          <div>
            <h2>Ngoại lệ GPS</h2>
            <p>Sales ở ngoài bán kính hoặc chưa lấy được vị trí</p>
          </div>
          <MapPin size={19} />
        </div>
        {data.gpsExceptions.map((item) => (
          <div className="ms-exception-card" key={item.id}>
            <div>
              <strong>
                {item.outlet} · {item.distance === null ? 'Không rõ vị trí' : `${item.distance}m`}
              </strong>
              <small>
                {item.reason} · {item.time}
              </small>
            </div>
            {item.status === 'Chờ Sup xác nhận' ? (
              <button onClick={() => approveGps(item.id)}>Xác nhận</button>
            ) : (
              <Badge tone="green">Đã xác nhận</Badge>
            )}
          </div>
        ))}
        {!data.gpsExceptions.length && (
          <div className="ms-empty compact">Chưa có yêu cầu xác nhận GPS.</div>
        )}
      </section>
      <section className="ms-section">
        <div className="ms-section-title">
          <div>
            <h2>Chuyển lượt ghé</h2>
            <p>Người nhận thấy ngay hồ sơ, đơn và lời hẹn</p>
          </div>
          <ArrowDown size={19} />
        </div>
        <label className="ms-field">
          Điểm bán
          <select onChange={(event) => setHandoffOutlet(event.target.value)} value={handoffOutlet}>
            {routeStops.map((stop) => (
              <option key={stop.outlet}>{stop.outlet}</option>
            ))}
          </select>
        </label>
        <label className="ms-field">
          Người đi thay
          <select
            onChange={(event) => setSupervisorAssignee(event.target.value)}
            value={supervisorAssignee}
          >
            <option>Lê Phúc</option>
            <option>Nguyễn An</option>
          </select>
        </label>
        <div className="ms-handoff-context">
          <ClipboardCheck size={17} /> Bàn giao kèm đơn gần nhất, lời hẹn và đầu mối NPP chị Mai.
        </div>
        <button className="ms-secondary-button ms-full-button" onClick={handoffVisit}>
          <Users size={17} /> CHUYỂN LƯỢT GHÉ & GIỮ LỊCH SỬ
        </button>
      </section>
      <section className="ms-section">
        <div className="ms-section-title">
          <div>
            <h2>Phân công tuyến hôm nay</h2>
            <p>Hiện người phụ trách từng điểm</p>
          </div>
        </div>
        {routeStops.map((stop) => (
          <div className="ms-task-row" key={stop.outlet}>
            <span className="ms-task-icon">
              <Store size={17} />
            </span>
            <span>
              <strong>{stop.outlet}</strong>
              <small>
                {data.assignments[stop.outlet] ?? people.sales.name} · {stop.time}
              </small>
            </span>
          </div>
        ))}
      </section>
    </>
  );

  const renderContent = () => {
    if (screen === 'home') return renderHome();
    if (screen === 'customers') return renderCustomers();
    if (screen === 'customer-detail') return renderCustomerDetail();
    if (screen === 'visit') return renderVisit();
    if (screen === 'order-form') return renderOrderForm();
    if (screen === 'orders') return renderOrders();
    if (screen === 'order-detail') return renderOrderDetail();
    if (screen === 'calendar') return renderCalendar();
    if (screen === 'report') return renderReport();
    if (screen === 'team') return renderTeam();
    return renderMore();
  };

  const renderStickyAction = () => {
    if (screen === 'order-form') {
      return (
        <PrimaryButton disabled={!draftQuantity} onClick={sendOrder}>
          <Send size={18} /> {online ? 'GỬI ĐƠN CHO NPP MINH PHÁT' : 'LƯU ĐƠN NHÁP · CHỜ GỬI'}{' '}
          <span>
            {draftQuantity ? `${draftQuantity} thùng · ${money(draftSubtotal)}` : 'Chọn sản phẩm'}
          </span>
          <ArrowRight size={18} />
        </PrimaryButton>
      );
    }
    if (screen === 'customer-detail') {
      return (
        <PrimaryButton onClick={() => openVisit(customer.name)}>
          <MapPin size={18} />
          {data.activeOutlet === customer.name ? 'MỞ LƯỢT GHÉ ĐANG MỞ' : 'CHUẨN BỊ GHÉ & CHECK-IN'}
          <ArrowRight size={18} />
        </PrimaryButton>
      );
    }
    if (screen === 'home' && activeStore) {
      return (
        <PrimaryButton
          disabled={data.gpsStatus === 'pending'}
          onClick={() => startOrder(activeStore.name)}
        >
          <ShoppingCart size={19} />{' '}
          {data.gpsStatus === 'pending'
            ? 'CHỜ SUP XÁC NHẬN GPS'
            : `TẠO ĐƠN TẠI ${activeStore.name.toUpperCase()}`}{' '}
          <ArrowRight size={18} />
        </PrimaryButton>
      );
    }
    if (screen === 'visit') {
      if (data.activeOutlet === customer.name) {
        return (
          <PrimaryButton onClick={checkOut}>
            <CheckCircle2 size={19} /> CHECK-OUT & LƯU TỔNG KẾT <ArrowRight size={18} />
          </PrimaryButton>
        );
      }
      if (data.activeOutlet) {
        return (
          <PrimaryButton onClick={() => openVisit(data.activeOutlet!)}>
            <MapPin size={18} /> TIẾP TỤC LƯỢT GHÉ {data.activeOutlet.toUpperCase()}{' '}
            <ArrowRight size={18} />
          </PrimaryButton>
        );
      }
      return (
        <PrimaryButton onClick={checkIn}>
          <MapPin size={18} /> CHECK-IN BẰNG GPS <ArrowRight size={18} />
        </PrimaryButton>
      );
    }
    if (screen === 'report') {
      return (
        <PrimaryButton
          onClick={() => {
            updateData((current) => ({ ...current, reportSubmitted: true }));
            notify('Đã gửi báo cáo ngày cho Sup.', () =>
              updateData((current) => ({ ...current, reportSubmitted: false })),
            );
          }}
        >
          <Send size={18} /> GỬI BÁO CÁO NGÀY <ArrowRight size={18} />
        </PrimaryButton>
      );
    }
    return null;
  };

  const backAction = () => {
    if (screen === 'customer-detail') setScreen('customers');
    else if (screen === 'visit') setScreen('customer-detail');
    else if (screen === 'order-form') setScreen('home');
    else if (screen === 'order-detail') setScreen('orders');
    else if (screen === 'report') setScreen('more');
    else setScreen('home');
  };

  const stickyAction = renderStickyAction();

  return (
    <div className="ms-app">
      <header className="ms-topbar">
        <div className="ms-brand">
          <span className="ms-logo">S</span>
          <span>
            <strong>SYNERA</strong>
            <small>SALES · TUYẾN QUẬN 3</small>
          </span>
        </div>
        <div className={`ms-network ${online ? 'online' : 'offline'}`}>
          {online ? <Cloud size={15} /> : <CloudOff size={15} />}
          <span>{online ? 'Có mạng' : 'Ngoại tuyến'}</span>
        </div>
        {screen !== 'home' && (
          <button className="ms-top-back" aria-label="Quay lại" onClick={backAction}>
            <ArrowLeft size={20} />
          </button>
        )}
      </header>
      {storageNotice && (
        <div className="ms-error-banner" role="alert">
          <AlertTriangle size={18} />
          <span>{storageNotice}</span>
          <button onClick={() => void retryStorage()}>Thử lại</button>
        </div>
      )}
      <main className={`ms-content ${stickyAction ? 'has-sticky' : ''}`}>
        <div className="ms-screen" key={screen} aria-busy={loading}>
          {loading ? <LoadingSkeleton /> : renderContent()}
        </div>
      </main>
      {stickyAction && <div className="ms-sticky-action">{stickyAction}</div>}
      <nav className="ms-bottom-nav" aria-label="Điều hướng chính">
        {navItems
          .filter((item) => item.id !== 'team' || canShowTeam)
          .map((item) => {
            const Icon = item.icon;
            return (
              <button
                aria-current={appTab === item.id ? 'page' : undefined}
                className={appTab === item.id ? 'active' : ''}
                key={item.id}
                onClick={() => setTab(item.id)}
              >
                <Icon size={20} />
                <span>{item.label}</span>
              </button>
            );
          })}
      </nav>
      {toast && (
        <div className="ms-toast" role="status" aria-live="polite">
          <CheckCircle2 size={18} />
          <span>{toast}</span>
          {undoAction && (
            <button className="ms-undo-button" onClick={undoLastAction}>
              Hoàn tác
            </button>
          )}
          <button
            aria-label="Đóng thông báo"
            onClick={() => {
              setToast('');
              setUndoAction(null);
            }}
          >
            <X size={16} />
          </button>
        </div>
      )}
    </div>
  );
}

function formatElapsed(totalSeconds: number) {
  const minutes = Math.floor(totalSeconds / 60);
  const hours = Math.floor(minutes / 60);
  return `${String(hours).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}:${String(totalSeconds % 60).padStart(2, '0')}`;
}

function distanceMeters(lat1: number, lon1: number, lat2: number, lon2: number) {
  const rad = (value: number) => (value * Math.PI) / 180;
  const dLat = rad(lat2 - lat1);
  const dLon = rad(lon2 - lon1);
  const a =
    Math.sin(dLat / 2) ** 2 + Math.cos(rad(lat1)) * Math.cos(rad(lat2)) * Math.sin(dLon / 2) ** 2;
  return 6371000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

function readVisitPhoto(
  file: File | undefined,
  updateData: (updater: (current: MobileData) => MobileData) => void,
  notify: (message: string) => void,
) {
  if (!file) return;
  const reader = new FileReader();
  reader.onload = () => {
    const imageData = reader.result;
    if (typeof imageData !== 'string') {
      notify('Không đọc được ảnh kệ. Vui lòng chọn ảnh khác.');
      return;
    }
    updateData((current) => ({
      ...current,
      visitPhotoName: file.name,
      visitPhotoData: imageData,
    }));
  };
  reader.onerror = () => notify('Không đọc được ảnh kệ. Vui lòng chọn ảnh khác.');
  reader.readAsDataURL(file);
}

function LoadingSkeleton() {
  return (
    <div className="ms-loading-skeleton" role="status" aria-label="Đang tải dữ liệu">
      <div className="ms-skeleton" style={{ width: '48%', height: 24 }} />
      <div className="ms-skeleton" style={{ width: '72%' }} />
      <div className="ms-skeleton-card">
        <div className="ms-skeleton" style={{ width: '38%' }} />
        <div className="ms-skeleton" style={{ width: '80%', height: 22 }} />
        <div className="ms-skeleton" style={{ width: '58%' }} />
      </div>
      <div className="ms-skeleton-card">
        <div className="ms-skeleton" style={{ width: '44%' }} />
        <div className="ms-skeleton" style={{ width: '100%', height: 48 }} />
        <div className="ms-skeleton" style={{ width: '100%', height: 48 }} />
      </div>
    </div>
  );
}

function TruckIcon() {
  return <Package size={17} />;
}
