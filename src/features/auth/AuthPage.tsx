import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { ArrowRight, ShieldCheck } from 'lucide-react';
import { Brand } from '../../shared/Brand';
import { loginAccount, registerAccount } from './authApi';
import { passwordConfirmationError } from './validation';

type AuthPageProps = {
  mode: 'login' | 'register';
};

export default function AuthPage({ mode }: AuthPageProps) {
  const isRegister = mode === 'register';
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError('');
    setMessage('');
    const form = new FormData(event.currentTarget);
    const email = String(form.get('email') ?? '');
    const password = String(form.get('password') ?? '');

    if (isRegister) {
      const validationError = passwordConfirmationError(password, form.get('confirmPassword'));
      if (validationError) {
        setError(validationError);
        return;
      }
    }

    setIsSubmitting(true);
    try {
      if (isRegister) {
        await registerAccount(String(form.get('name') ?? ''), email, password);
        setMessage('Tạo tài khoản thành công. Bạn đã được đăng nhập.');
      } else {
        await loginAccount(email, password);
        setMessage('Đăng nhập thành công.');
      }
    } catch (requestError) {
      setError(
        requestError instanceof Error
          ? requestError.message
          : 'Không thể kết nối dịch vụ. Vui lòng thử lại.',
      );
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <div className="auth-page">
      <section className="auth-story">
        <Link className="brand" to="/" aria-label="SYNERA · Trang chủ">
          <Brand />
        </Link>
        <div className="auth-message">
          <span className="eyebrow">CONNECTED DISTRIBUTION</span>
          <h1>
            Kết nối hệ sinh thái.
            <br />
            Mở lối tăng trưởng.
          </h1>
          <p>Một không gian chung cho nhà sản xuất, nhà phân phối và đội ngũ bán hàng.</p>
          <div className="auth-network">
            <span>NHÀ SẢN XUẤT</span>
            <i />
            <span>NHÀ PHÂN PHỐI</span>
            <i />
            <span>ĐIỂM BÁN</span>
          </div>
        </div>
        <span className="auth-footer">DMS + CRM · Đồng hành trên từng điểm chạm.</span>
      </section>
      <section className="auth-form-area">
        <div className="auth-form">
          <span className="soft-icon" aria-hidden="true">
            <ShieldCheck size={24} />
          </span>
          <h2>{isRegister ? 'Tạo tài khoản' : 'Chào mừng trở lại'}</h2>
          <p>
            {isRegister ? 'Đăng ký tài khoản SYNERA.' : 'Đăng nhập vào tài khoản SYNERA của bạn.'}
          </p>
          <div className="auth-notice" role="note">
            Thông tin tài khoản được xử lý qua API bảo mật và lưu trên máy chủ.
          </div>
          <form className="stack" onSubmit={submit}>
            {isRegister && (
              <label>
                Họ và tên
                <input name="name" autoComplete="name" required />
              </label>
            )}
            <label>
              Email
              <input name="email" type="email" autoComplete="email" required />
            </label>
            <label>
              Mật khẩu
              <input
                name="password"
                type="password"
                autoComplete={isRegister ? 'new-password' : 'current-password'}
                minLength={12}
                maxLength={200}
                required
              />
            </label>
            {isRegister && (
              <label>
                Xác nhận mật khẩu
                <input
                  name="confirmPassword"
                  type="password"
                  autoComplete="new-password"
                  minLength={12}
                  maxLength={200}
                  required
                />
              </label>
            )}
            {error && (
              <p className="form-error" role="alert">
                {error}
              </p>
            )}
            {message && (
              <p className="auth-status" role="status">
                {message}
              </p>
            )}
            <button className="btn primary" type="submit" disabled={isSubmitting}>
              {isSubmitting ? 'Đang xử lý...' : isRegister ? 'Tạo tài khoản' : 'Đăng nhập'}
              <ArrowRight size={16} />
            </button>
          </form>
          <p className="auth-switch">
            {isRegister ? 'Đã có tài khoản?' : 'Chưa có tài khoản?'}{' '}
            <Link to={isRegister ? '/login' : '/register'}>
              {isRegister ? 'Đăng nhập' : 'Đăng ký'}
            </Link>
          </p>
          <Link className="auth-home" to="/">
            Quay lại trang chủ
          </Link>
        </div>
      </section>
    </div>
  );
}
