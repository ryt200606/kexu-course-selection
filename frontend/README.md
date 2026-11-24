# 课序 V3 前端

学生、教师和教务共用 Vue 3 + TypeScript 应用，角色和对象权限由服务端再次校验。教师可以从公开仓库直接打开[浏览器交互样例](https://ryt200606.github.io/kexu-course-selection/)，或按[仓库部署说明](../DEPLOYMENT.md)运行 Nginx、API、openGauss 三层应用。

## 运行与复现

从本目录执行。使用 Node 24 及随工程提交的依赖锁。

```sh
npm ci
npm test
npm run build
npm run dev
```

开发服务将 `/api` 转发至本机 8080 端口。生产构建位于 `dist/`。账号登录、当前教学数据和所有提交都通过部署实例发起。浏览器只提交当前选择，人数、学分、排课资格和正式成绩由后端返回。

## 角色入口

|角色|入口|可核对的结果|
|---|---|---|
|学生|学业规划、选课中心、成绩与学分|课程模块进度、两套保存的试排、冲突及可行替代班、轮次志愿顺序、选课、候补和正式成绩|
|教师|授课与成绩、成绩复核|草稿与正式分数、复核意见、教务批准后的发布、跨及格线修正|
|教务|培养方案、排课与分配、指标明细、成绩复核|方案版本和学生绑定进度、教室与时间资源、批次约束与对照、快照回读、成绩审核、按学期的分子与分母|

试排保存保留一对草案；正式提交前服务器重新核对，试排不会占座。成绩复核先由教师核查，再由教务审核、教师重新发布，学生在最终发布前可查到上次正式分数。

## 公开预览和浏览器验证

```sh
npm run build:preview
npx playwright install chromium
npm run test:preview
```

预览构建输出到 `dist-preview/`，使用相对资源路径和 hash 路由，可托管在 GitHub Pages 项目子路径。角色样例数据保存在当前标签页，可用“重置样例”恢复。预览中的实测证据页面只加载打包时已存在的 `performance/results/` 原始 JSON；缺测项会显示对应空状态。预览不会请求部署 API。

`test:preview` 启动临时静态服务器，检查 1440px 桌面和 390px 移动视口。设置 `COURSE_BROWSER_EVIDENCE` 可保存分角色截图和 JSON 收据；可选 `COURSE_BROWSER_EXECUTABLE` 指定本地 Chromium 可执行文件。

完整部署的浏览器用例通过真实登录和 API 创建带唯一前缀的演示学期、课程和师生账号，核验两套试排、正式选课、成绩发布、更正及学分回读。应在专用验收数据库执行；样本资料保留以便排查和核验。设置以下环境变量后运行 `npm run test:live`。

|变量|用途|
|---|---|
|`COURSE_PLANNING_ACCEPTANCE`|设为 `1` 同时执行排课、名单发布和课表回读，要求部署中已启用真实优化器|
|`COURSE_ISOLATED_FIXTURE`|设为 `1` 确认验收数据库允许创建演示资料|
|`COURSE_LIVE_URL`|Nginx 的 HTTP(S) 源站地址|
|`COURSE_LIVE_CREDENTIAL_FILE`|本地私有 UTF-8 JSON 文件路径，需含 `bootstrap_admin_password`。文件、口令与令牌不进入日志|
|`COURSE_BROWSER_EVIDENCE`|独立的截图与结果目录|

源文件中 `src/preview/` 仅用于演示构建。正式应用使用相同的主要业务页面及其 Vue 组件，并通过同源会话、CSRF 与服务端角色绑定执行操作。
