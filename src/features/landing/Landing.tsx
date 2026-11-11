import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  ArrowRight,
  ArrowUpRight,
  Building2,
  Store,
  Route,
  PackageCheck,
  ShoppingBag,
  Layers,
  ChartNoAxesCombined,
  ChevronDown,
  Menu,
  X,
  Check,
  ShieldCheck,
  WifiOff,
} from 'lucide-react';
import { gsap } from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';
import { Brand } from '../../shared/Brand';
gsap.registerPlugin(ScrollTrigger);

const solutions = [
  {
    icon: PackageCheck,
    title: 'Quản lý phân phối',
    text: 'Kết nối đặt hàng, hạn mức, kho và giao nhận trên cùng một luồng.',
  },
  {
    icon: Store,
    title: 'Quan hệ khách hàng',
    text: 'Hiểu từng cửa hàng qua hồ sơ 360°, lịch sử mua và lời hẹn.',
  },
  {
    icon: Route,
    title: 'Đội ngũ thị trường',
    text: 'Đi tuyến, check-in và lên đơn ngay tại điểm bán, kể cả khi offline.',
  },
];
export default function Landing() {
  const root = useRef<HTMLDivElement>(null);
  const video = useRef<HTMLVideoElement>(null);
  const [scrolled, setScrolled] = useState(window.scrollY > 24);
  const [menu, setMenu] = useState(false);
  const [reduced, setReduced] = useState(
    () => matchMedia('(prefers-reduced-motion:reduce)').matches,
  );
  useEffect(() => {
    const media = matchMedia('(prefers-reduced-motion:reduce)');
    const motion = () => setReduced(media.matches);
    const scroll = () => setScrolled(window.scrollY > 24);
    media.addEventListener('change', motion);
    window.addEventListener('scroll', scroll, { passive: true });
    return () => {
      media.removeEventListener('change', motion);
      window.removeEventListener('scroll', scroll);
    };
  }, []);
  useEffect(() => {
    if (reduced) {
      video.current?.pause();
      return;
    }
    void video.current?.play().catch(() => {});
    const context = gsap.context(() => {
      gsap.utils.toArray<HTMLElement>('.landing-reveal').forEach((el) =>
        gsap.from(el, {
          y: 22,
          opacity: 0,
          duration: 0.55,
          ease: 'power2.out',
          scrollTrigger: { trigger: el, start: 'top 92%', once: true },
        }),
      );
    }, root);
    return () => context.revert();
  }, [reduced]);
  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setMenu(false);
    };
    document.addEventListener('keydown', key);
    return () => document.removeEventListener('keydown', key);
  }, []);
  return (
    <div className="landing-page" ref={root}>
      <a className="skip-link" href="#landing-content">
        Bỏ qua điều hướng
      </a>
      <header className={`landing-header ${scrolled ? 'scrolled' : ''}`}>
        <div className="landing-header-inner">
          <Link className="landing-brand" to="/landing" aria-label="SYNERA · Trang chủ">
            <Brand />
          </Link>
          <nav aria-label="Điều hướng website">
            <details className="solutions-menu">
              <summary>
                Giải pháp <ChevronDown size={15} />
              </summary>
              <div className="solutions-mega">
                {solutions.map((s) => (
                  <a key={s.title} href="#giai-phap">
                    <s.icon size={24} />
                    <strong>{s.title}</strong>
                    <span>{s.text}</span>
                  </a>
                ))}
              </div>
            </details>
            <a href="#quy-trinh">Cách hoạt động</a>
            <a href="#vai-tro">Dành cho bạn</a>
            <a href="#goi-su-dung">Gói sử dụng</a>
          </nav>
          <div className="landing-header-actions">
            <Link to="/login" className="landing-login">
              Đăng nhập
            </Link>
            <Link to="/register" className="landing-button compact">
              Tạo tài khoản <ArrowUpRight size={16} />
            </Link>
            <button
              className="landing-menu-button"
              aria-label={menu ? 'Đóng menu website' : 'Mở menu website'}
              aria-expanded={menu}
              aria-controls="landing-mobile-menu"
              onClick={() => setMenu(!menu)}
            >
              {menu ? <X /> : <Menu />}
            </button>
          </div>
        </div>
        {menu && (
          <nav
            id="landing-mobile-menu"
            className="landing-mobile-menu"
            aria-label="Điều hướng website mobile"
          >
            {[
              ['#giai-phap', 'Giải pháp'],
              ['#quy-trinh', 'Cách hoạt động'],
              ['#vai-tro', 'Dành cho bạn'],
              ['#goi-su-dung', 'Gói sử dụng'],
            ].map(([href, label]) => (
              <a key={href} href={href} onClick={() => setMenu(false)}>
                {label}
                <ArrowRight size={16} />
              </a>
            ))}
            <Link to="/login">
              Đăng nhập <ArrowRight size={16} />
            </Link>
          </nav>
        )}
      </header>
      <main id="landing-content">
        <section className="landing-hero">
          <video
            ref={video}
            muted
            loop
            playsInline
            autoPlay={!reduced}
            preload="metadata"
            poster="/brand/synera-mark.png"
            aria-hidden="true"
          >
            <source src="/assets/landing/background-lp.mp4" type="video/mp4" />
          </video>
          <div className="hero-overlay" />
          <div className="landing-container hero-content">
            <span className="landing-eyebrow">
              <span />
              KẾT NỐI PHÂN PHỐI · KẾT NỐI TĂNG TRƯỞNG
            </span>
            <h1>
              Một nền tảng.
              <br />
              Kết nối mọi
              <br />
              <em>điểm chạm.</em>
            </h1>
            <p>
              Từ nhà sản xuất đến từng điểm bán.
              <br />
              SYNERA hợp nhất DMS và CRM, giúp đội ngũ phối hợp rõ ràng và vận hành liền mạch.
            </p>
            <div className="hero-actions">
              <Link to="/register" className="landing-button">
                Tạo tài khoản <ArrowRight size={18} />
              </Link>
              <a href="#quy-trinh" className="landing-button outline">
                Xem cách hoạt động
              </a>
            </div>
            <div className="hero-trust">
              <span>
                <Layers size={16} />
                DMS + CRM hợp nhất
              </span>
              <span>
                <WifiOff size={16} />
                Sales hỗ trợ offline
              </span>
            </div>
          </div>
          <a href="#giai-phap" className="hero-scroll">
            Khám phá SYNERA <ChevronDown size={16} />
          </a>
        </section>
        <section id="giai-phap" className="landing-section">
          <div className="landing-container">
            <div className="landing-section-heading landing-reveal">
              <span className="landing-eyebrow">GIẢI PHÁP CỦA SYNERA</span>
              <h2>
                Phối hợp dễ dàng.
                <br />
                Vận hành cùng một nhịp.
              </h2>
              <p>Mỗi bộ phận một công việc, cùng nhìn về một nguồn thông tin.</p>
            </div>
            <div className="solution-cards">
              {solutions.map((s, i) => (
                <article key={s.title} className={`solution-card landing-reveal tone-${i}`}>
                  <span className="solution-icon">
                    <s.icon size={29} />
                  </span>
                  <span className="solution-number">0{i + 1}</span>
                  <h3>{s.title}</h3>
                  <p>{s.text}</p>
                  <a href="#vai-tro">
                    Khám phá giải pháp <ArrowRight size={17} />
                  </a>
                  <div className="solution-mini-flow">
                    {(i === 0
                      ? ['Đặt hàng', 'Duyệt đơn', 'Giao nhận']
                      : i === 1
                        ? ['Hồ sơ', 'Lời hẹn', 'Chăm sóc']
                        : ['Đi tuyến', 'Check-in', 'Lên đơn']
                    ).map((l, j) => (
                      <span key={l}>
                        {j > 0 && <ArrowRight size={12} />}
                        <b>{l}</b>
                      </span>
                    ))}
                  </div>
                </article>
              ))}
            </div>
          </div>
        </section>
        <section id="quy-trinh" className="landing-section process-section">
          <div className="landing-container">
            <div className="landing-section-heading landing-reveal">
              <span className="landing-eyebrow">TỪ YÊU CẦU ĐẾN KẾT QUẢ</span>
              <h2>
                Một luồng xuyên suốt.
                <br />
                Mọi bên cùng nắm bắt.
              </h2>
              <p>Theo dõi vòng đời đơn hàng trên cùng một nền tảng.</p>
            </div>
            <div className="process-grid">
              {[
                {
                  title: 'NPP đặt hàng',
                  text: 'Chọn sản phẩm, xem tồn và hạn mức trước khi gửi.',
                  icon: ShoppingBag,
                },
                {
                  title: 'NSX phê duyệt',
                  text: 'Đối chiếu công nợ, kiểm tra và xử lý ngoại lệ.',
                  icon: ShieldCheck,
                },
                {
                  title: 'Giao & nhận hàng',
                  text: 'Theo dõi tiến trình, ghi nhận thực nhận và chênh lệch.',
                  icon: PackageCheck,
                },
                {
                  title: 'Đối soát rõ ràng',
                  text: 'Tách số tạm tính và số đã chốt để cùng đối chiếu.',
                  icon: ChartNoAxesCombined,
                },
              ].map((step, i) => (
                <article key={step.title} className="landing-reveal">
                  <div className="process-step-icon">
                    <step.icon size={27} />
                    <span>0{i + 1}</span>
                  </div>
                  <h3>{step.title}</h3>
                  <p>{step.text}</p>
                </article>
              ))}
            </div>
            <div className="landing-center">
              <Link to="/register" className="landing-button">
                Tạo tài khoản <ArrowRight size={17} />
              </Link>
              <small>Giao diện xác thực hiện chưa kết nối dịch vụ.</small>
            </div>
          </div>
        </section>
        <section id="vai-tro" className="landing-section">
          <div className="landing-container">
            <div className="landing-section-heading landing-reveal">
              <span className="landing-eyebrow">MỘT HỆ SINH THÁI · BA GÓC NHÌN</span>
              <h2>
                Đúng công cụ.
                <br />
                Đúng người sử dụng.
              </h2>
              <p>Không gian làm việc phù hợp với vai trò và phạm vi được giao.</p>
            </div>
            <div className="role-cards">
              {[
                {
                  icon: Building2,
                  role: 'nsx',
                  label: 'Nhà sản xuất',
                  desc: 'Điều hành mạng lưới từ một nơi.',
                  list: [
                    'Dashboard & duyệt đơn',
                    'Hạn mức, công nợ & đối soát',
                    'Chính sách & chỉ tiêu',
                  ],
                },
                {
                  icon: Store,
                  role: 'npp',
                  label: 'Nhà phân phối',
                  desc: 'Chủ động kinh doanh mỗi ngày.',
                  list: [
                    'Đặt hàng & theo dõi giao nhận',
                    'Kho, cửa hàng & CRM',
                    'Tuyến & đội ngũ bán hàng',
                  ],
                },
                {
                  icon: Route,
                  role: 'sales',
                  label: 'Nhân viên Sales',
                  desc: 'Linh hoạt trên từng tuyến bán hàng.',
                  list: [
                    'Lịch hôm nay & hồ sơ cửa hàng',
                    'GPS check-in & lên đơn nhanh',
                    'Đơn nháp offline & báo cáo',
                  ],
                },
              ].map((r) => (
                <article className="landing-reveal" key={r.role}>
                  <r.icon size={30} />
                  <h3>{r.label}</h3>
                  <p>{r.desc}</p>
                  <ul>
                    {r.list.map((t) => (
                      <li key={t}>
                        <Check size={16} />
                        {t}
                      </li>
                    ))}
                  </ul>
                  <Link to="/register">
                    Tạo tài khoản <ArrowUpRight size={17} />
                  </Link>
                </article>
              ))}
            </div>
          </div>
        </section>
        <section id="goi-su-dung" className="landing-section plans-section">
          <div className="landing-container">
            <div className="landing-section-heading landing-reveal">
              <span className="landing-eyebrow">LỘ TRÌNH ĐỒNG HÀNH</span>
              <h2>
                Bắt đầu phù hợp.
                <br />
                Mở rộng khi sẵn sàng.
              </h2>
              <p>Cấu hình theo quy mô và nhu cầu. Chưa công bố bảng giá thương mại.</p>
            </div>
            <div className="plan-cards">
              {[
                {
                  name: 'Thí điểm',
                  text: 'Khám phá luồng cốt lõi cùng dữ liệu mẫu.',
                  tag: 'BẮT ĐẦU',
                  items: ['Đặt hàng & duyệt đơn', 'Công nợ & giao nhận', 'Ba vai trò trải nghiệm'],
                },
                {
                  name: 'Mở rộng',
                  text: 'Kết nối vận hành và chăm sóc điểm bán.',
                  tag: 'DMS + CRM',
                  items: [
                    'Kho & quản lý cửa hàng',
                    'Tuyến, lời hẹn & Sales',
                    'Báo cáo và chỉ tiêu',
                  ],
                },
                {
                  name: 'Tích hợp',
                  text: 'Lên kế hoạch kết nối hệ thống doanh nghiệp.',
                  tag: 'THEO NHU CẦU',
                  items: [
                    'API & phân quyền phía server',
                    'ERP / kế toán / đồng bộ',
                    'AI trưng bày & thông báo thật',
                  ],
                },
              ].map((p, i) => (
                <article key={p.name} className={`landing-reveal ${i === 1 ? 'featured' : ''}`}>
                  <span className="landing-eyebrow">{p.tag}</span>
                  <h3>{p.name}</h3>
                  <p>{p.text}</p>
                  <ul>
                    {p.items.map((x) => (
                      <li key={x}>
                        <Check size={15} />
                        {x}
                      </li>
                    ))}
                  </ul>
                  <Link className="landing-button outline" to={i === 2 ? '/register' : '/login'}>
                    {i === 2 ? 'Tạo tài khoản' : 'Đăng nhập'}
                    <ArrowRight size={16} />
                  </Link>
                </article>
              ))}
            </div>
          </div>
        </section>
        <section className="landing-cta">
          <div className="landing-container landing-reveal">
            <span className="landing-eyebrow">CÙNG NHAU ĐI XA HƠN</span>
            <h2>
              Kết nối hôm nay.
              <br />
              Sẵn sàng cho bước tiếp theo.
            </h2>
            <Link to="/register" className="landing-button">
              Tạo tài khoản SYNERA <ArrowRight size={18} />
            </Link>
          </div>
        </section>
      </main>
      <footer className="landing-footer">
        <div className="landing-container">
          <Link to="/landing" aria-label="SYNERA · Trang chủ">
            <Brand />
          </Link>
          <span>Nền tảng kết nối quản lý phân phối & quan hệ khách hàng.</span>
          <small>© 2026 SYNERA · Phiên bản trải nghiệm</small>
        </div>
      </footer>
    </div>
  );
}
