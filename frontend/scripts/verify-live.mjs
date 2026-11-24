// Run on an isolated course instance. Uses the normal API to seed a unique term,
// then drives student, teacher and registrar in separate real browser sessions.
import assert from 'node:assert/strict'
import { randomBytes } from 'node:crypto'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium } from 'playwright'

const base = process.env.COURSE_LIVE_URL,
  secretsFile = process.env.COURSE_LIVE_CREDENTIAL_FILE,
  output = process.env.COURSE_BROWSER_EVIDENCE
if (!base || !secretsFile || !output || process.env.COURSE_ISOLATED_FIXTURE !== '1')
  throw Error('Set isolated fixture confirmation, live URL, credential file and evidence directory.')
const target = new URL(base)
if (!['https:', 'http:'].includes(target.protocol) || target.username || target.password)
  throw Error('Use an HTTP(S) origin without credentials.')
const secrets = JSON.parse((await readFile(secretsFile, 'utf8')).replace(/^\uFEFF/, ''))
const adminPass = secrets.bootstrap_admin_password
if (typeof adminPass !== 'string' || !adminPass) throw Error('Missing bootstrap admin credential.')
const id = 'WB' + randomBytes(4).toString('hex').toUpperCase(),
  term = id + 'TERM',
  course = id + 'C',
  sec = id + 'X',
  alt = id + 'Y',
  teacher = id + 'T',
  student = id + 'S',
  password = 'Test#' + randomBytes(18).toString('base64url')
const evidence = {
  scope: 'REAL_BROWSER_HTTP_OPENGAUSS',
  fixture: id,
  checks: [],
  responseCounts: {},
  screens: [],
}
await mkdir(output, { recursive: true })
const browser = await chromium.launch({
  headless: true,
  ...(process.env.COURSE_BROWSER_EXECUTABLE ? { executablePath: process.env.COURSE_BROWSER_EXECUTABLE } : {}),
})
evidence.browser = browser.version()
const errors = [],
  opened = []
async function login(username, secret, size) {
  const context = await browser.newContext({
    baseURL: target.origin,
    viewport: size,
    reducedMotion: 'reduce',
  })
  const page = await context.newPage()
  opened.push(page)
  page.setDefaultTimeout(15000)
  page.on('pageerror', (e) => errors.push(e.message))
  page.on('response', (r) => {
    const u = new URL(r.url())
    if (u.pathname.startsWith('/api/')) {
      const k = String(r.status())
      evidence.responseCounts[k] = (evidence.responseCounts[k] || 0) + 1
    }
  })
  await page.goto('/login')
  await page.locator('[name=username]').fill(username)
  await page.locator('[name=password]').fill(secret)
  await page.getByRole('button', { name: '进入我的工作台' }).click()
  await page.waitForURL((u) => u.pathname === '/')
  const health = await context.request.get('/api/v2/health'),
    meta = await health.json()
  if (!/gauss/i.test(JSON.stringify(meta)) || meta.surrogate === true)
    throw Error('Expected a real openGauss deployment')
  evidence.checks.push(
    'authenticated user via real browser ' +
      (username === 'admin' ? 'registrar' : username.endsWith('T') ? 'teacher' : 'student'),
  )
  return { context, page }
}
async function request(ctx, version, path, method = 'GET', body) {
  const headers = {}
  if (method !== 'GET') {
    const token = await ctx.request.get('/api/v2/auth/csrf')
    headers['X-CSRF-TOKEN'] = (await token.json()).token
  }
  const res = await ctx.request.fetch('/api/v' + version + path, {
    method,
    headers,
    ...(body === undefined ? {} : { data: body }),
  })
  const result = await res.text()
  if (!res.ok()) {
    let detail = ''
    try {
      const e = JSON.parse(result)
      detail = e.code + ' ' + e.message
    } catch {}
    throw Error(method + ' ' + path + ' ' + res.status() + ' ' + detail)
  }
  return result ? JSON.parse(result) : null
}
async function go(page, path, heading) {
  await page.goto(path)
  await page.getByRole('heading', { name: heading, exact: true }).first().waitFor()
  const termSelect = page.getByLabel('选择学期', { exact: true })
  if (await termSelect.count()) {
    await termSelect.locator('option[value="' + term + '"]').waitFor({ state: 'attached' })
    await termSelect.selectOption(term)
  }
}
async function snap(page, file) {
  await page.screenshot({ path: resolve(output, file + '.png'), fullPage: true })
  evidence.screens.push(file + '.png')
}
const btn = (page, name) => page.getByRole('button', { name, exact: true })
async function pick(page, label, id) {
  const sel = page.getByRole('combobox', { name: label, exact: true })
  await page.getByRole('searchbox', { name: '搜索' + label, exact: true }).fill(id)
  await sel.locator('option[value="' + id + '"]').waitFor({ state: 'attached' })
  await sel.selectOption(id)
}
try {
  const admin = await login('admin', adminPass, { width: 1440, height: 1000 }),
    a = admin.context
  const post = (suffix, body) => request(a, 2, '/admin/' + suffix, 'POST', body)
  const now = Date.now()
  await post('terms', { id: term, name: '浏览器完整流程 ' + id, state: 'ACTIVE' })
  const opens = now + 12000,
    roundSpec = {
      id: id + 'R',
      termId: term,
      name: '浏览器选课轮',
      state: 'DRAFT',
      startsAt: new Date(opens).toISOString(),
      endsAt: new Date(now + 86400000).toISOString(),
      withdrawalEndsAt: new Date(now + 172800000).toISOString(),
      creditLimit: 18,
    }
  const roundDraft = await post('rounds', roundSpec)
  await request(a, 3, '/admin/planning/rounds/' + id + 'R/commit', 'POST')
  const directSpec = {
    ...roundSpec,
    id: id + 'FREE',
    name: '普通选课轮',
    state: 'OPEN',
    startsAt: new Date(now - 3600000).toISOString(),
  }
  const directRound = await post('rounds', directSpec)
  evidence.checks.push('seed committed before preference round opened')
  await post('teachers', { id: teacher, name: '流程验收任课教师', department: '虚构教学系' })
  await post('students', { id: student, name: '流程验收学生', department: '虚构教学系', state: 'ACTIVE' })
  for (const [role, username, field] of [
    ['TEACHER', teacher, 'teacherId'],
    ['STUDENT', student, 'studentId'],
  ])
    await post('users', {
      username,
      displayName: role === 'TEACHER' ? '流程验收教师' : '流程验收学生',
      role,
      [field]: username,
      password,
    })
  await post('courses', {
    id: course,
    title: '事务与审计实践',
    department: '虚构教学系',
    description: '独立浏览器验收课程',
  })
  await post('offerings', {
    id: id + 'O',
    courseId: course,
    termId: term,
    credits: 3,
    state: 'PUBLISHED',
    prerequisites: [],
  })
  for (const [n, day] of [
    [sec, 1],
    [alt, 3],
  ])
    await post('sections', {
      id: n,
      teacherId: teacher,
      offeringId: id + 'O',
      capacity: 10,
      state: 'PUBLISHED',
      meetings: [{ room: id + '201', weekday: day, startPeriod: 1, endPeriod: 2, startWeek: 1, endWeek: 16 }],
    })
  const preferenceSection = id + 'P',
    planningSection = id + 'D'
  await post('courses', {
    id: id + 'C2',
    title: '课程规划方法',
    department: '虚构教学系',
    description: '独立志愿与排课验收',
  })
  await post('offerings', {
    id: id + 'O2',
    courseId: id + 'C2',
    termId: term,
    credits: 2,
    state: 'PUBLISHED',
    prerequisites: [],
  })
  await post('sections', {
    id: preferenceSection,
    teacherId: teacher,
    offeringId: id + 'O2',
    capacity: 5,
    state: 'PUBLISHED',
    meetings: [{ room: id + '203', weekday: 5, startPeriod: 3, endPeriod: 4, startWeek: 1, endWeek: 16 }],
  })
  await post('sections', {
    id: planningSection,
    teacherId: teacher,
    offeringId: id + 'O2',
    capacity: 5,
    state: 'DRAFT',
    meetings: [],
  })
  const unscheduledCourse = id + 'C3'
  await post('courses', {
    id: unscheduledCourse,
    title: '下一学期专题',
    department: '虚构教学系',
    description: '本学期不设班的验收样例',
  })
  const curr = await request(a, 3, '/admin/curricula', 'POST', {
    cohort: id,
    name: '浏览器验证培养方案',
    modules: [{ name: '事务核心模块', requiredCredits: 3, courseIds: [course, unscheduledCourse] }],
  })
  await request(a, 3, '/admin/curricula/' + curr.id + '/assign', 'POST', { studentIds: [student] })
  if (Date.now() < opens) await new Promise((resolve) => setTimeout(resolve, opens - Date.now() + 100))
  const st = await login(student, password, { width: 390, height: 844 }),
    te = await login(teacher, password, { width: 1440, height: 1000 })
  const s = st.page,
    t = te.page,
    reg = admin.page
  await go(s, '/student/planning', '学业规划')
  await s.getByRole('heading', { name: '浏览器验证培养方案' }).waitFor()
  await s.getByRole('link', { name: '事务与审计实践：查看本学期教学班', exact: true }).click()
  await s.locator('.course-card').first().waitFor()
  assert.equal(await s.locator('.course-card').count(), 2)
  assert.deepEqual(await s.locator('.course-card h3').allTextContents(), ['事务与审计实践', '事务与审计实践'])
  await s.locator('.course-card button').first().click()
  await s.getByRole('heading', { name: '你的选课资格', exact: true }).waitFor()
  await s.getByRole('dialog').getByRole('button', { name: '关闭', exact: true }).click()
  await s.goBack()
  await s.getByRole('link', { name: '下一学期专题：查看本学期教学班', exact: true }).click()
  await s.getByRole('heading', { name: '本学期尚未公布该课程的教学班', exact: true }).waitFor()
  await s.goBack()
  evidence.checks.push(
    'curriculum to exact term/course offerings and live eligibility; unoffered course is empty',
  )
  await btn(s, '方案对比').click()
  await pick(s, '方案A教学班', sec)
  await btn(s, '加入方案').nth(0).click()
  await pick(s, '方案B教学班', alt)
  await btn(s, '加入方案').nth(1).click()
  await btn(s, '比较两个方案').click()
  await s.getByText('方案可行 · 3 学分').first().waitFor()
  await btn(s, '保存两套方案').click()
  await s.getByText('两套试排方案已保存。').waitFor()
  await snap(s, 'student-real-plans')
  evidence.checks.push('real saved drafts and checked eligibility')
  await s.goto('/student/catalog?section=' + sec)
  await s.getByRole('heading', { name: '选课中心', exact: true }).waitFor()
  // Direct links open the requested section; the backend still checks session and eligibility.
  const firstDialog = s.getByRole('dialog')
  await firstDialog.waitFor()
  await firstDialog
    .getByRole('button', { name: /确认选课|选入课表|选择此课|立即选课/ })
    .first()
    .click()
  await firstDialog.waitFor({ state: 'hidden' })
  evidence.checks.push('student enrolled via section deep link')
  await t.goto('/teacher/sections/' + sec)
  const gradeInput = t.locator('input[type=number]').first()
  await gradeInput.fill('59.99')
  await btn(t, '保存草稿').click()
  await t.getByText('成绩草稿已保存。正式发布前，学生看不到本次修改。').waitFor()
  await t
    .getByRole('button', { name: /发布成绩|正式发布/ })
    .first()
    .click()
  await btn(t, '确认正式发布').click()
  await go(s, '/student/grades', '成绩与学分')
  await s.getByText('59.99', { exact: true }).waitFor()
  evidence.checks.push('teacher saved and published 59.99')
  await go(s, '/student/reviews', '成绩复核')
  await btn(s, '申请复核').click()
  const form = s.getByRole('dialog')
  await form.getByLabel('已发布成绩').locator('option:not([value=""])').first().waitFor({ state: 'attached' })
  await form.getByLabel('已发布成绩').selectOption({ index: 1 })
  await form.getByLabel('具体说明').fill('请核查综合实验评分与系统录入的一致性。')
  await form.getByRole('button', { name: '申请成绩复核' }).click()
  await go(t, '/teacher/reviews', '成绩复核')
  await btn(t, '查看记录 →').first().click()
  await t.getByLabel('建议成绩', { exact: true }).fill('60.00')
  await t.getByLabel('具体说明').fill('已核对实验评分记录，建议更正为六十分。')
  await btn(t, '提交教师核查意见').click()
  await t.getByText('待教务审核', { exact: true }).last().waitFor()
  await t
    .locator('tbody td')
    .filter({ hasText: /^60.00$/ })
    .waitFor()
  await snap(t, 'teacher-real-review')
  await go(reg, '/admin/reviews', '成绩复核')
  await btn(reg, '查看记录 →').first().click()
  await reg.getByLabel('具体说明').fill('已审核原始评分与任课教师意见，批准更正。')
  await btn(reg, '记录教务审核决定').click()
  await reg.getByText('待教师发布', { exact: true }).last().waitFor()
  await snap(reg, 'admin-real-review')
  await go(s, '/student/grades', '成绩与学分')
  await s.getByText('59.99', { exact: true }).waitFor()
  evidence.checks.push('approval preserves original public grade')
  await go(t, '/teacher/reviews', '成绩复核')
  await btn(t, '查看记录 →').first().click()
  await btn(t, '发布更正成绩').click()
  await t.getByText('已更正发布', { exact: true }).last().waitFor()
  await go(s, '/student/grades', '成绩与学分')
  await s.getByText('60.00', { exact: true }).waitFor()
  await go(s, '/student/planning', '学业规划')
  await s.getByText('已通过 · 计入 3 学分', { exact: true }).waitFor()
  await snap(s, 'student-real-earned-credits')
  evidence.checks.push('published correction grants real curriculum credits')
  await request(a, 2, '/admin/rounds/' + directSpec.id, 'PUT', {
    ...directSpec,
    state: 'CLOSED',
    version: directRound.version,
  })
  await request(a, 2, '/admin/rounds/' + roundSpec.id, 'PUT', {
    ...roundSpec,
    state: 'OPEN',
    version: roundDraft.version,
  })
  await btn(s, '选课志愿').click()
  await s
    .locator('select')
    .filter({ has: s.locator('option[value="' + roundSpec.id + '"]') })
    .selectOption(roundSpec.id)
  await pick(s, '志愿教学班', preferenceSection)
  await btn(s, '添加志愿').click()
  await pick(s, '志愿教学班', sec)
  await btn(s, '添加志愿').click()
  await btn(s, '保存志愿顺序').click()
  await s.getByText('志愿顺序已保存。').waitFor()
  await go(reg, '/admin/indicators', '教学指标明细')
  await btn(reg, '学业预警').click()
  await reg.getByText(sec, { exact: true }).waitFor()
  await snap(reg, 'admin-real-analytics')
  if (process.env.COURSE_PLANNING_ACCEPTANCE === '1') {
    await go(reg, '/admin/planning', '排课与分配')
    await reg.getByLabel('教室名称', { exact: true }).fill(id + 'LAB')
    await reg.getByLabel('容量', { exact: true }).fill('12')
    await btn(reg, '保存学期资源').click()
    await reg.getByText(/学期资源已保存/).waitFor()
    await pick(reg, '待排教学班', planningSection)
    await reg
      .getByRole('combobox')
      .filter({ has: reg.getByRole('option', { name: '周四', exact: true }) })
      .first()
      .selectOption('4')
    await reg
      .locator('select')
      .filter({ has: reg.locator('option[value="' + id + 'LAB"],option:has-text("' + id + 'LAB")') })
      .selectOption(id + 'LAB')
    await btn(reg, '生成排课预览').click()
    await btn(reg, '发布此批次').waitFor()
    await snap(reg, 'admin-real-solver-preview')
    await btn(reg, '发布此批次').click()
    await btn(reg, '确认发布').click()
    await reg.getByText('批次已发布，结果已重新读取。').waitFor()
    const scheduled = await request(a, 2, '/admin/sections/' + planningSection)
    assert.equal(scheduled.state, 'PUBLISHED')
    assert.equal(scheduled.meetings[0].room, id + 'LAB')
    evidence.checks.push('real browser scheduled and published versioned room/teacher assignment')
    const active = await request(a, 2, '/admin/rounds/' + roundSpec.id)
    await request(a, 2, '/admin/rounds/' + roundSpec.id, 'PUT', {
      ...roundSpec,
      state: 'CLOSED',
      version: active.version,
    })
    await go(reg, '/admin/planning', '排课与分配')
    await btn(reg, '志愿分配').click()
    await reg
      .locator('select')
      .filter({ has: reg.locator('option[value="' + roundSpec.id + '"]') })
      .selectOption(roundSpec.id)
    await btn(reg, '生成分配预览').click()
    await btn(reg, '发布此批次').waitFor()
    await snap(reg, 'admin-real-allocation-preview')
    await btn(reg, '发布此批次').click()
    await btn(reg, '确认发布').click()
    await reg.getByText('批次已发布，结果已重新读取。').waitFor()
    await go(s, '/student/planning', '学业规划')
    await btn(s, '选课志愿').click()
    await s
      .locator('select')
      .filter({ has: s.locator('option[value="' + roundSpec.id + '"]') })
      .selectOption(roundSpec.id)
    await s.getByText('获分配 ' + preferenceSection + ' · 第 1 志愿').waitFor()
    const preferenceResult = await request(st.context, 3, '/student/preferences/' + roundSpec.id)
    assert.equal(preferenceResult.phase, 'PUBLISHED')
    const skipped = preferenceResult.unassigned.find((x) => x.sectionId === sec)
    assert(
      skipped?.explanation && skipped.reasons.length,
      'published student receives actual rejection reasons',
    )
    await s.getByText(skipped.explanation, { exact: true }).waitFor()
    evidence.allocation = {
      assigned: preferenceResult.assignments.length,
      unassigned: preferenceResult.unassigned.length,
      reasonCodes: skipped.reasons,
    }
    await snap(s, 'student-real-allocation')
    evidence.checks.push('real allocation committed before opening and published after closure')
  }
  const checks = await request(a, 2, '/admin/consistency?termId=' + term)
  assert.equal(checks.status, 'PASS')
  assert.deepEqual(checks.mismatches, [])
  evidence.checks.push('database consistency readback')
  assert.deepEqual(errors, [])
  evidence.status = 'PASS'
} catch (e) {
  evidence.status = 'FAIL'
  evidence.failure = e instanceof Error ? e.message : String(e)
  for (const [n, page] of opened.entries()) {
    if (page.url().includes('/login')) continue
    try {
      await page.screenshot({ path: resolve(output, 'failed-' + id + '-' + n + '.png') })
      const diagnostics = await page.locator('[role=alert],.error-inline,.plan-verdict').allTextContents()
      evidence['page' + n] = {
        path: new URL(page.url()).pathname,
        alerts: diagnostics.slice(0, 5),
        inputs: await page
          .locator('select')
          .evaluateAll((es) =>
            es.map((el) => [el.getAttribute('aria-label'), el.selectedOptions[0]?.label, el.options.length]),
          ),
      }
    } catch {
      /* diagnostics cannot mask the original cause */
    }
  }
  throw e
} finally {
  await writeFile(resolve(output, 'live-browser.json'), JSON.stringify(evidence, null, 2))
  console.log(JSON.stringify(evidence))
  await browser.close()
}
