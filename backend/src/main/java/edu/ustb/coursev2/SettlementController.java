package edu.ustb.coursev2;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import org.springframework.transaction.annotation.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v3/admin/analytics")
public class SettlementController {
  private final Db db;
  private final DatabaseConfig.Identity identity;
  SettlementController(Db db, DatabaseConfig.Identity identity) { this.db=db; this.identity=identity; }

  @GetMapping("/settlement")
  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public Map<String,Object> settlement(@RequestParam String termId,@RequestParam String metric,
      @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
    Actor.require("ADMIN");
    db.one("SELECT id FROM cs2_term WHERE id=?",termId);
    String detail,definition,unit;
    switch(metric){
      case "UNASSIGNED" -> {
        detail="SELECT f.round_id AS item_id,r.name AS label,SUM(CASE WHEN awarded.student_id IS NULL THEN 1 ELSE 0 END) AS numerator,"
            +" COUNT(*) AS denominator FROM cs3_preference_form f JOIN cs2_round r ON r.id=f.round_id"
            +" JOIN cs3_planning_run p ON p.round_id=f.round_id AND p.kind='ALLOCATION' AND p.state='PUBLISHED'"
            +" LEFT JOIN (SELECT DISTINCT run_id,student_id FROM cs3_allocation_award) awarded"
            +" ON awarded.run_id=p.id AND awarded.student_id=f.student_id WHERE r.term_id=?"
            +" AND EXISTS(SELECT 1 FROM cs3_preference_choice c WHERE c.round_id=f.round_id AND c.student_id=f.student_id)"
            +" GROUP BY f.round_id,r.name";
        definition="已发布轮次中，提交过志愿但未获任何教学班的学生人次；分母为各轮提交过志愿的学生人次。"; unit="人次";
      }
      case "PREFERENCE" -> {
        detail="SELECT c.choice_rank AS item_id,CAST(c.choice_rank AS VARCHAR(10)) AS label,COUNT(a.run_id) AS numerator,"
            +" COUNT(*) AS denominator FROM cs3_preference_choice c JOIN cs2_round r ON r.id=c.round_id"
            +" JOIN cs3_planning_run p ON p.round_id=c.round_id AND p.kind='ALLOCATION' AND p.state='PUBLISHED'"
            +" LEFT JOIN cs3_allocation_award a ON a.run_id=p.id AND a.student_id=c.student_id AND a.section_id=c.section_id"
            +" WHERE r.term_id=? GROUP BY c.choice_rank";
        definition="发布时命中的志愿条目数除以相应顺位提交的志愿条目数；总值按条目加权。"; unit="%";
      }
      case "WAIT_TIME" -> {
        String seconds=identity.surrogate()?"DATEDIFF('SECOND',w.created_at,w.updated_at)"
            :"EXTRACT(EPOCH FROM (w.updated_at-w.created_at))";
        detail="SELECT w.section_id AS item_id,c.title AS label,SUM("+seconds+") AS numerator,COUNT(*) AS denominator"
            +" FROM cs2_waitlist w JOIN cs2_section s ON s.id=w.section_id JOIN cs2_offering o ON o.id=s.offering_id"
            +" JOIN cs2_course c ON c.id=o.course_id WHERE w.state='ENROLLED' AND o.term_id=? GROUP BY w.section_id,c.title";
        definition="在本学期成功转正的候补记录中，从排队至转正的总秒数除以转正人次。"; unit="秒";
      }
      default -> throw Api.invalid("无此结算指标");
    }
    var totals=db.one("SELECT COALESCE(SUM(numerator),0) AS numerator,COALESCE(SUM(denominator),0) AS denominator FROM ("+detail+") q",termId);
    var n=new BigDecimal(totals.get("numerator").toString());
    var d=new BigDecimal(totals.get("denominator").toString());
    BigDecimal value=metric.equals("UNASSIGNED")?n:d.signum()==0?null:
        n.multiply(metric.equals("PREFERENCE")?BigDecimal.valueOf(100):BigDecimal.ONE).divide(d,2,RoundingMode.HALF_UP);
    var rows=db.page(detail+" ORDER BY item_id",List.of(termId),page,size);
    return Api.object("metric",metric,"unit",unit,"definition",definition,"numerator",n,"denominator",d,"value",value,
        "items",rows.items(),"total",rows.total(),"size",rows.size(),"page",rows.page());
  }
}

