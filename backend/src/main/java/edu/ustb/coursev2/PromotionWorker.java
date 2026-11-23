package edu.ustb.coursev2;

import java.util.*;
import org.slf4j.*;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PromotionWorker {
  private static final Logger LOG = LoggerFactory.getLogger(PromotionWorker.class);
  private final Db db;
  private final EnrollmentService service;
  private final Environment env;
  private volatile boolean ready;

  public PromotionWorker(Db db, EnrollmentService service, Environment env) {
    this.db = db;
    this.service = service;
    this.env = env;
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
      for (Map<String, Object> s :
          db.rows(
              "SELECT s.id FROM cs2_section s WHERE s.promotion_pending=1 OR EXISTS(SELECT 1 FROM"
                  + " cs2_waitlist w JOIN cs2_round r ON r.id=w.round_id WHERE w.section_id=s.id"
                  + " AND w.state='WAITING' AND (r.state<>'OPEN' OR r.ends_at<=CURRENT_TIMESTAMP))"
                  + " ORDER BY s.id LIMIT 4")) {
        try {
          service.promote(Db.str(s, "id"));
        } catch (Exception e) {
          LOG.warn(
              "Bounded promotion postponed section={} code={}",
              s.get("id"),
              e.getClass().getSimpleName());
        }
      }
    } catch (Exception e) {
      LOG.warn("Promotion worker unavailable: {}", e.getClass().getSimpleName());
    }
  }
}
