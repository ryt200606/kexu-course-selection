package edu.ustb.coursev2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class Idempotency {
  // Compatibility A treats empty VARCHAR as NULL; claims need a nonempty marker.
  private static final String PENDING_RESPONSE = "__COURSE_V2_PENDING__";
  private final Db db;
  private final ObjectMapper json;

  public Idempotency(Db db, ObjectMapper json) {
    this.db = db;
    this.json = json;
  }

  public Map<String, Object> run(
      String operation,
      String key,
      Map<String, Object> payload,
      Supplier<Map<String, Object>> business) {
    if (key == null || !key.matches("[A-Za-z0-9._:-]{8,100}"))
      throw Api.invalid("请提供8—100位Idempotency-Key请求键");
    Actor a = Actor.current();
    String hash;
    try {
      hash =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(json.writeValueAsBytes(new TreeMap<>(payload))));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    List<Map<String, Object>> existing =
        db.rows(
            "SELECT request_hash,response_json FROM cs2_idempotency WHERE user_id=? AND operation=?"
                + " AND request_key=?",
            a.id(),
            operation,
            key);
    if (!existing.isEmpty()) {
      if (!Db.str(existing.get(0), "requestHash").equals(hash))
        throw Api.conflict("IDEMPOTENCY_CONFLICT", "同一请求键不能用于不同参数");
      return response(Db.str(existing.get(0), "responseJson"));
    }
    // A conflicting INSERT aborts a PostgreSQL-family transaction. Recover to the JDBC
    // savepoint before reading the winner; catching the exception alone is not sufficient.
    org.springframework.transaction.TransactionStatus tx =
        org.springframework.transaction.interceptor.TransactionAspectSupport
            .currentTransactionStatus();
    Object savepoint = tx.createSavepoint();
    try {
      db.update(
          "INSERT INTO"
              + " cs2_idempotency(user_id,operation,request_key,request_hash,response_json,created_at)"
              + " VALUES(?,?,?,?,?,CURRENT_TIMESTAMP)",
          a.id(),
          operation,
          key,
          hash,
          PENDING_RESPONSE);
      tx.releaseSavepoint(savepoint);
    } catch (org.springframework.dao.DuplicateKeyException duplicate) {
      tx.rollbackToSavepoint(savepoint);
      tx.releaseSavepoint(savepoint);
      Map<String, Object> winner =
          db.one(
              "SELECT request_hash,response_json FROM cs2_idempotency WHERE user_id=? AND"
                  + " operation=? AND request_key=?",
              a.id(),
              operation,
              key);
      if (!Db.str(winner, "requestHash").equals(hash))
        throw Api.conflict("IDEMPOTENCY_CONFLICT", "同一请求键不能用于不同参数");
      return response(Db.str(winner, "responseJson"));
    }
    Map<String, Object> result = business.get();
    try {
      String text = json.writeValueAsString(result);
      if (text.length() > 7900) throw new IllegalStateException("Idempotent response too large");
      db.update(
          "UPDATE cs2_idempotency SET response_json=? WHERE user_id=? AND operation=? AND"
              + " request_key=?",
          text,
          a.id(),
          operation,
          key);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
    return result;
  }

  private Map<String, Object> response(String stored) {
    if (stored.isEmpty() || PENDING_RESPONSE.equals(stored))
      throw Api.conflict("REQUEST_IN_PROGRESS", "相同请求正在执行，请稍后重试");
    try {
      return json.readValue(stored, new TypeReference<Map<String, Object>>() {});
    } catch (Exception error) {
      throw new IllegalStateException("Stored response invalid", error);
    }
  }
}
