package edu.ustb.coursev2;

import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class Journal {
  private final Db db;

  public Journal(Db db) {
    this.db = db;
  }

  public void audit(String action, String entity, String message, String term) {
    Actor actor = null;
    try {
      actor = Actor.current();
    } catch (Api.Failure ignored) {
    }
    db.update(
        "INSERT INTO"
            + " cs2_audit(id,actor_id,actor_name,action,entity_id,message,request_id,term_id,created_at)"
            + " VALUES(?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
        Db.uid(),
        actor == null ? null : actor.id(),
        actor == null ? "候补服务" : actor.displayName(),
        action,
        entity,
        message,
        RequestIds.current(),
        term);
  }

  public void notifyStudent(String student, String title, String message) {
    for (Map<String, Object> u : db.rows("SELECT id FROM cs2_user WHERE student_id=?", student))
      db.update(
          "INSERT INTO cs2_notification(id,user_id,title,message,created_at)"
              + " VALUES(?,?,?,?,CURRENT_TIMESTAMP)",
          Db.uid(),
          u.get("id"),
          title,
          message);
  }

  public void notifyUser(String user, String title, String message) {
    db.update(
        "INSERT INTO cs2_notification(id,user_id,title,message,created_at)"
            + " VALUES(?,?,?,?,CURRENT_TIMESTAMP)",
        Db.uid(),
        user,
        title,
        message);
  }
}
