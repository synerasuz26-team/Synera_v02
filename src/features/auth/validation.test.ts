import { describe, expect, it } from 'vitest';
import { passwordConfirmationError } from './validation';

describe('password confirmation', () => {
  it('accepts a matching password', () => {
    expect(passwordConfirmationError('a-long-password', 'a-long-password')).toBe('');
  });

  it('rejects a mismatch or missing confirmation', () => {
    expect(passwordConfirmationError('a-long-password', 'different-password')).toBe(
      'Mật khẩu xác nhận không khớp.',
    );
    expect(passwordConfirmationError('a-long-password', null)).toBe(
      'Mật khẩu xác nhận không khớp.',
    );
  });
});
