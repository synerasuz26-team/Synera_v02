package com.synera.api.importing;

import java.text.Normalizer;
import java.util.Locale;

public final class ImportNormalization {
    private ImportNormalization() { }
    public static String text(String value) {
        return value == null ? "" : value.replace('\u00a0', ' ').strip().replaceAll("\\s+", " ");
    }
    public static String folded(String value) {
        return Normalizer.normalize(text(value), Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replace('đ', 'd').replace('Đ', 'D').toUpperCase(Locale.ROOT);
    }
    public static String code(String value) { return folded(value).replaceAll("\\s+", ""); }
    public static String phone(String value) {
        String result = text(value).replaceAll("[\\s().-]", "");
        if (result.startsWith("0084")) result = "+84" + result.substring(4);
        if (result.startsWith("0")) result = "+84" + result.substring(1);
        if (result.startsWith("84")) result = "+" + result;
        if (!result.isEmpty() && !result.matches("\\+[1-9][0-9]{8,14}"))
            throw new IllegalArgumentException("Số điện thoại không hợp lệ. Hãy dùng định dạng văn bản để giữ số 0 đầu.");
        return result;
    }
    public static long integer(String value, String label, long minimum) {
        try {
            if (!text(value).matches("[0-9]+")) throw new NumberFormatException();
            long result = Long.parseLong(text(value));
            if (result < minimum) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + " phải là số nguyên từ " + minimum + " trở lên, không có dấu phân cách.");
        }
    }
    public static boolean similarOutlet(String firstName, String firstAddress, String secondName, String secondAddress) {
        if (!folded(firstName).equals(folded(secondName))) return false;
        String left = folded(firstAddress).replaceAll("[^A-Z0-9]", "");
        String right = folded(secondAddress).replaceAll("[^A-Z0-9]", "");
        if (left.isEmpty() || right.isEmpty()) return false;
        int[] previous = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            int[] next = new int[right.length() + 1]; next[0] = i;
            for (int j = 1; j <= right.length(); j++) next[j] = Math.min(Math.min(previous[j] + 1, next[j - 1] + 1),
                    previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1));
            previous = next;
        }
        return previous[right.length()] <= Math.max(2, Math.max(left.length(), right.length()) / 7);
    }
}
