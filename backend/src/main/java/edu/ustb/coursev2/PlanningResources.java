package edu.ustb.coursev2;

import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v3/admin/planning/resources")
public class PlanningResources {
  private final Db db;
  private final Journal journal;
  PlanningResources(Db db, Journal journal) { this.db=db; this.journal=journal; }

  static Map<String,Object> read(Db db, String term) {
    var version = db.rows("SELECT version FROM cs3_resource_version WHERE term_id=?",term);
    var rooms = db.rows("SELECT room AS id,capacity FROM cs3_room WHERE term_id=? ORDER BY room",term);
    var blocks = db.rows("SELECT teacher_id,weekday,start_week,end_week,start_period,end_period FROM cs3_teacher_block "
        + "WHERE term_id=? ORDER BY teacher_id,weekday,start_week,end_week,start_period,end_period",term);
    return Api.object("version",version.isEmpty()?0L:Db.lng(version.get(0),"version"),"rooms",rooms,"unavailable",blocks);
  }

  @GetMapping("/{term}")
  @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  public Map<String,Object> get(@PathVariable String term) {
    Actor.require("ADMIN");
    db.one("SELECT id FROM cs2_term WHERE id=?",term);
    return read(db,term);
  }

  @PutMapping("/{term}")
  @Transactional
  public Map<String,Object> replace(@PathVariable String term,@RequestBody Map<String,Object> body) {
    Actor.require("ADMIN");
    var termRow=db.one("SELECT state FROM cs2_term WHERE id=? FOR UPDATE",term);
    if ("ARCHIVED".equals(termRow.get("state"))) throw Api.conflict("TERM_ARCHIVED","已归档学期不可修改排课资源");
    long expected=Api.version(body);
    var version=db.rows("SELECT version FROM cs3_resource_version WHERE term_id=?",term);
    long actual=version.isEmpty()?0L:Db.lng(version.get(0),"version");
    if(expected!=actual) throw Api.conflict("VERSION_CONFLICT","排课资源已更新，请重新载入");
    var rooms=PlanningService.records(body.get("rooms"),160,"rooms");
    var blocks=PlanningService.records(body.get("unavailable"),500,"unavailable");
    if(rooms.isEmpty()) throw Api.invalid("至少维护一间教室");
    if(actual==0) db.update("INSERT INTO cs3_resource_version(term_id,version) VALUES(?,1)",term);
    else db.update("UPDATE cs3_resource_version SET version=version+1 WHERE term_id=?",term);
    db.update("DELETE FROM cs3_room WHERE term_id=?",term);
    db.update("DELETE FROM cs3_teacher_block WHERE term_id=?",term);
    Set<String> names=new HashSet<>();
    for(var room:rooms){
      String name=Api.text(room,"id",80);
      if(!names.add(name)) throw Api.invalid("教室名称重复：" + name);
      db.update("INSERT INTO cs3_room(term_id,room,capacity) VALUES(?,?,?)",term,name,Api.integer(room,"capacity",1,10000));
    }
    for(var block:blocks){
      int firstWeek=Api.integer(block,"startWeek",1,30), firstPeriod=Api.integer(block,"startPeriod",1,14);
      db.update("INSERT INTO cs3_teacher_block(term_id,teacher_id,weekday,start_week,end_week,start_period,end_period) VALUES(?,?,?,?,?,?,?)",
          term, Api.id(block,"teacherId"), Api.integer(block,"weekday",1,7), firstWeek,
          Api.integer(block,"endWeek",firstWeek,30), firstPeriod, Api.integer(block,"endPeriod",firstPeriod,14));
    }
    journal.audit("PLANNING_RESOURCES",term,"教室 "+rooms.size()+"，教师禁排 "+blocks.size()+"，版本 "+(actual+1),term);
    return read(db,term);
  }
}

