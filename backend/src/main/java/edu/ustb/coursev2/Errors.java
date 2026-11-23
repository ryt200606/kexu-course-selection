package edu.ustb.coursev2;

import java.util.Map;
import org.slf4j.*;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class Errors {
  private static final Logger LOG = LoggerFactory.getLogger(Errors.class);

  public static Map<String, Object> body(String code, String message) {
    return Api.object(
        "code",
        code,
        "message",
        message,
        "fieldErrors",
        Map.of(),
        "requestId",
        RequestIds.current());
  }

  @ExceptionHandler(Api.Failure.class)
  ResponseEntity<?> domain(Api.Failure e) {
    return ResponseEntity.status(e.status).body(body(e.code, e.getMessage()));
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    IllegalArgumentException.class
  })
  ResponseEntity<?> input(Exception e) {
    return ResponseEntity.badRequest().body(body("INVALID_INPUT", "请求格式不正确"));
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<?> denied(Exception e) {
    return ResponseEntity.status(403).body(body("FORBIDDEN", "当前身份无权执行此操作"));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<?> integrity(Exception e) {
    return ResponseEntity.status(409).body(body("CONSTRAINT_VIOLATION", "数据重复、被引用或违反数据库约束"));
  }

  @ExceptionHandler({
    CannotAcquireLockException.class,
    QueryTimeoutException.class,
    PessimisticLockingFailureException.class
  })
  ResponseEntity<?> busy(Exception e) {
    return ResponseEntity.status(503).body(body("BUSY_RETRY", "事务繁忙，请稍后使用同一请求键重试"));
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<?> database(Exception e) {
    LOG.error("Database failure requestId={}", RequestIds.current(), e);
    return ResponseEntity.status(503).body(body("DATABASE_UNAVAILABLE", "数据库操作暂不可用"));
  }
}
