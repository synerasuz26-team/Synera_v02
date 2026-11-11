export function passwordConfirmationError(
  password: FormDataEntryValue | null,
  confirmation: FormDataEntryValue | null,
) {
  return password === confirmation ? '' : 'Mật khẩu xác nhận không khớp.';
}
