# 学业、试排和成绩复核接口

这些接口前缀为 `/api/v3`。先使用 `/api/v2/auth/csrf` 获取请求令牌，登录后重新获取令牌。服务器从会话取得学生或授课教师身份。正式分数支持 0.00—100.00。

| 业务 | 操作入口 | 可观察结果 |
|---|---|---|
| 分届培养方案 | 教务 `GET/POST /admin/curricula`、`POST /admin/curricula/{id}/assign` | 课程组、学分要求按方案版本保存。已有绑定的学生继续按原版本审查 |
| 学业进度 | 学生 `GET /student/curriculum`；教务 `GET /admin/curricula/{id}/progress` | 按已发布的及格成绩核算同课通过学分；课程组列出缺口 |
| 两套试排 | `GET/PUT /student/plans?termId=...`、`POST /student/plans/compare` | left/right 保存教学班 ID。对比提供时段、学分、资格和替代建议。试排期间席位和正式课表保持原数 |
| 单项成绩复核 | `POST /student/grade-reviews` → `POST /teacher/grade-reviews/{id}/recommend` → `POST /admin/grade-reviews/{id}/decide` → `POST /teacher/grade-reviews/{id}/publish` | 四次操作分别留痕。教务批准后原正式成绩继续可读，教师最终重发才改变正式成绩及下游资格 |
| 查复核记录 | `GET /grade-reviews`、`GET /grade-reviews/{id}` | 学生看本人，教师看授课班，教务按权限查看。版本失配后重新读取 |
| 教室与教师可用时段 | `GET/PUT /admin/planning/resources/{termId}` | 提交 version、rooms、unavailable。学期内未列为禁排的时段可用，资源变化使旧的排课快照失效 |
| 教务钻取 | `GET /admin/analytics/drill`（CAPACITY、WAITLIST、GRADES）；`GET /admin/analytics/settlement`（UNASSIGNED、PREFERENCE、WAIT_TIME） | 传 termId、metric、page、size。结算接口在同一个可重复读事务中得到汇总和当前页明细，返回对应的分子、分母、单位和定义 |

详细请求数据可对照 [API 控制器](src/main/java/edu/ustb/coursev2/AcademicController.java)、[结算查询](src/main/java/edu/ustb/coursev2/SettlementController.java) 与 [完整可执行示例](src/test/java/edu/ustb/coursev2/AcademicFlowTest.java)。志愿分配与排课发布的状态、轮次和摘要规则另见 [规划接口](PLANNING.md)。
