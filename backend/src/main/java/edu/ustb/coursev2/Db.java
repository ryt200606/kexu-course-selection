package edu.ustb.coursev2;

import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class Db {
  public final JdbcTemplate jdbc;
  private final boolean surrogate;

  public Db(JdbcTemplate jdbc, org.springframework.core.env.Environment env) {
    this.jdbc = jdbc;
    this.surrogate = env.getProperty("app.database.type", "opengauss").equals("h2");
  }

  public List<Map<String, Object>> rows(String sql, Object... args) {
    return jdbc.query(
        sql,
        (r, n) -> {
          Map<String, Object> m = new LinkedHashMap<>();
          ResultSetMetaData md = r.getMetaData();
          for (int i = 1; i <= md.getColumnCount(); i++) {
            String raw = md.getColumnLabel(i).toLowerCase(Locale.ROOT);
            StringBuilder key = new StringBuilder();
            boolean upper = false;
            for (char c : raw.toCharArray()) {
              if (c == '_') {
                upper = true;
                continue;
              }
              key.append(upper ? Character.toUpperCase(c) : c);
              upper = false;
            }
            Object v = r.getObject(i);
            if (v instanceof Timestamp t) v = t.toInstant().toString();
            if (v instanceof java.sql.Date d) v = d.toLocalDate().toString();
            m.put(key.toString(), v);
          }
          return m;
        },
        args);
  }

  public Map<String, Object> one(String sql, Object... args) {
    List<Map<String, Object>> rows = rows(sql, args);
    if (rows.isEmpty()) throw Api.missing();
    return rows.get(0);
  }

  public long count(String sql, Object... args) {
    Long n = jdbc.queryForObject(sql, Long.class, args);
    return n == null ? 0 : n;
  }

  public int update(String sql, Object... args) {
    return jdbc.update(sql, args);
  }

  public static String str(Map<String, Object> m, String key) {
    return Objects.toString(m.get(key), "");
  }

  public static int num(Map<String, Object> m, String key) {
    Object n = m.get(key);
    return n instanceof Number x ? x.intValue() : 0;
  }

  public static long lng(Map<String, Object> m, String key) {
    Object n = m.get(key);
    return n instanceof Number x ? x.longValue() : 0;
  }

  public Instant now() {
    return jdbc.queryForObject(
            surrogate ? "SELECT CURRENT_TIMESTAMP" : "SELECT clock_timestamp()", Timestamp.class)
        .toInstant();
  }

  public Api.Page page(String sql, List<Object> args, int page, int size) {
    if (page < 0 || size < 1 || size > 100) throw Api.invalid("page/size范围不正确");
    long count = count("SELECT COUNT(*) FROM (" + sql + ") cs2_page_count", args.toArray());
    List<Object> paged = new ArrayList<>(args);
    paged.add(size);
    paged.add((long) page * size);
    return new Api.Page(rows(sql + " LIMIT ? OFFSET ?", paged.toArray()), page, size, count);
  }

  public static Timestamp timestamp(Object value) {
    try {
      return Timestamp.from(Instant.parse(String.valueOf(value)));
    } catch (Exception ex) {
      throw Api.invalid("时间必须为ISO-8601 UTC格式");
    }
  }

  public static String uid() {
    return UUID.randomUUID().toString();
  }
}
