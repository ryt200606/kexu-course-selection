package edu.ustb.coursev2;

import java.util.*;
import org.slf4j.*;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class PromotionWorker {
  private static final Logger LOG = LoggerFactory.getLogger(PromotionWorker.class);
  private final Db db;
  private final EnrollmentService service;
  private final Environment env;
  private final TransactionTemplate tx;
  private volatile boolean ready;

  public PromotionWorker(Db db, EnrollmentService service, Environment env, PlatformTransactionManager manager) {
    this.db = db;
    this.service = service;
    this.env = env;
    this.tx = new TransactionTemplate(manager);
  }

  @org.springframework.context.event.EventListener(
      org.springframework.boot.context.event.ApplicationReadyEvent.class)
  public void ready() {
    ready = true;
  }

  @Scheduled(fixedDelayString = "${app.promotions.delay-ms:2000}")
  public void resume() {
    if (!ready
        || !env.getProperty("app.mode", "serve").equals("serve")
        || !env.getProperty("app.promotions.enabled", Boolean.class, true)) return;
    try {
      for (String section : nextBatch()) {
        try {
          service.promote(section);
        } catch (Exception e) {
          LOG.warn(
              "Bounded promotion postponed section={} code={}",
              section,
              e.getClass().getSimpleName());
        }
      }
    } catch (Exception e) {
      LOG.warn("Promotion worker unavailable: {}", e.getClass().getSimpleName());
    }
  }

  List<String> nextBatch() {
    return tx.execute(status -> {
      var cursor = db.one("SELECT last_section FROM cs3_promotion_cursor WHERE id=1 FOR UPDATE");
      var due = db.rows("SELECT s.id FROM cs2_section s WHERE s.promotion_pending=1 OR EXISTS"
          + " (SELECT 1 FROM cs2_waitlist w JOIN cs2_round r ON r.id=w.round_id WHERE w.section_id=s.id"
          + " AND w.state='WAITING' AND (r.state<>'OPEN' OR r.ends_at<=CURRENT_TIMESTAMP))"
          + " ORDER BY CASE WHEN s.id>? THEN 0 ELSE 1 END,s.id LIMIT 4", Db.str(cursor, "lastSection"))
          .stream().map(row -> Db.str(row, "id")).toList();
      if (!due.isEmpty())
        db.update("UPDATE cs3_promotion_cursor SET last_section=? WHERE id=1", due.get(due.size()-1));
      return due;
    });
  }
}
