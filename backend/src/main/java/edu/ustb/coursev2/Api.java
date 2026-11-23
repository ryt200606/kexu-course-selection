package edu.ustb.coursev2;

import java.util.*;

public final class Api {
  private Api() {}

  public record Page(List<Map<String, Object>> items, int page, int size, long total) {}

  public static class Failure extends RuntimeException {
    public final int status;
    public final String code;

    public Failure(int status, String code, String message) {
      super(message);
      this.status = status;
      this.code = code;
    }
  }

  public static Failure conflict(String code, String message) {
    return new Failure(409, code, message);
  }

  public static Failure invalid(String message) {
    return new Failure(400, "INVALID_INPUT", message);
  }

  public static Failure missing() {
    return new Failure(404, "NOT_FOUND", "记录不存在");
  }

  public static Map<String, Object> object(Object... pairs) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
    return result;
  }

  public static String text(Map<String, Object> body, String key, int maximum) {
    Object value = body.get(key);
    if (!(value instanceof String s) || s.isBlank() || s.strip().length() > maximum)
      throw invalid(key + "不能为空或超过长度限制");
    return s.strip();
  }

  public static String id(Map<String, Object> body, String key) {
    String s = text(body, key, 32);
    if (!s.matches("[A-Za-z0-9_-]{1,32}")) throw invalid(key + "格式不正确");
    return s;
  }

  public static int integer(Map<String, Object> b, String k, int min, int max) {
    Object v = b.get(k);
    if (!(v instanceof Number n)
        || n.doubleValue() != n.intValue()
        || n.intValue() < min
        || n.intValue() > max) throw invalid(k + "超出范围");
    return n.intValue();
  }

  public static long version(Map<String, Object> b) {
    Object v = b.get("version");
    if (!(v instanceof Number)) throw invalid("请提供整数记录version");
    try {
      long result = new java.math.BigDecimal(v.toString()).longValueExact();
      if (result < 0) throw invalid("version不能小于0");
      return result;
    } catch (ArithmeticException | NumberFormatException e) {
      throw invalid("version必须是范围内整数");
    }
  }

  public static String optional(Map<String, Object> b, String key, int maximum) {
    if (b.get(key) == null) return "";
    String s = String.valueOf(b.get(key)).strip();
    if (s.length() > maximum) throw invalid(key + "超过长度限制");
    return s;
  }
}
