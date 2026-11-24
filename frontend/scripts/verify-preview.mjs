import { createServer } from 'node:http'
import { readFile, mkdir, writeFile } from 'node:fs/promises'
import { extname, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import assert from 'node:assert/strict'
import { chromium } from 'playwright'

const base = '/kexu-course-selection/'
const root = fileURLToPath(new URL('../dist-preview/', import.meta.url))
const evidence = process.env.COURSE_BROWSER_EVIDENCE
const mime = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript',
  '.css': 'text/css',
  '.svg': 'image/svg+xml',
  '.woff2': 'font/woff2',
}
const server = createServer(async (req, res) => {
  try {
    const pathname = new URL(req.url, 'http://localhost').pathname
    if (!pathname.startsWith(base)) {
      res.writeHead(404).end()
      return
    }
    const relative = decodeURIComponent(pathname.slice(base.length)) || 'index.html'
    const file = resolve(root, relative)
    if (!file.startsWith(resolve(root) + sep)) {
      res.writeHead(403).end()
      return
    }
    const bytes = await readFile(file)
    res.writeHead(200, { 'Content-Type': mime[extname(file)] || 'application/octet-stream' }).end(bytes)
  } catch {
    res.writeHead(404).end()
  }
})
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
let browser
const receipts = []
try {
  browser = await chromium.launch({
    headless: true,
    ...(process.env.COURSE_BROWSER_EXECUTABLE
      ? { executablePath: process.env.COURSE_BROWSER_EXECUTABLE }
      : {}),
  })
  const url = 'http://127.0.0.1:' + server.address().port + base
  if (evidence) await mkdir(evidence, { recursive: true })
  for (const [name, width, height] of [
    ['desktop', 1440, 1000],
    ['mobile', 390, 844],
  ]) {
    const context = await browser.newContext({ viewport: { width, height }, reducedMotion: 'reduce' })
    const page = await context.newPage(),
      errors = [],
      apiRequests = [],
      checks = []
    page.setDefaultTimeout(8000)
    page.on('pageerror', (e) => errors.push(e.message))
    page.on('request', (r) => {
      if (new URL(r.url()).pathname.startsWith('/api/')) apiRequests.push(r.url())
    })
    const heading = async (text) => page.getByRole('heading', { name: text, exact: true }).first().waitFor()
    const button = (name) => page.getByRole('button', { name, exact: true })
    const nav = page.getByRole('navigation', { name: '该角色的功能' })
    const roleNav = page.getByRole('navigation', { name: '体验角色' })
    const roles = (role) => roleNav.getByRole('link', { name: role, exact: true }).click()
    const menu = (name) => nav.getByRole('link', { name, exact: true }).click()
    const shot = async (key) => {
      if (evidence)
        await page.screenshot({ path: resolve(evidence, name + '-' + key + '.png'), fullPage: true })
    }
    await page.goto(url)
    await heading('学业规划')
    await heading('计算机科学与技术')
    assert(await page.getByText('公开交互样例', { exact: true }).isVisible())
    checks.push('synthetic scope visible')
    await shot('student')
    await button('方案对比').click()
    await button('比较两个方案').click()
    await page.getByText('存在资格或课程冲突 · 6 学分').waitFor()
    await page.getByText('方案可行 · 6 学分').waitFor()
    await page.getByText('同时段冲突：', {exact:false}).first().waitFor()
    checks.push('compared conflicting and feasible plans')
    await button('保存两套方案').click()
    await page.getByText('两套试排方案已保存。').waitFor()
    await button('选课志愿').click()
    await button('下移 DB-A').click()
    await button('保存志愿顺序').click()
    await page.getByText('志愿顺序已保存。').waitFor()
    checks.push('persisted ordered preferences')
    await menu('成绩单')
    await page.getByText('59.99', { exact: true }).waitFor()
    checks.push('59.99 displayed precisely')
    await roles('教师')
    await button('查看记录 →').first().click()
    await page.getByLabel('建议成绩', { exact: true }).fill('60.00')
    await page.getByLabel('具体说明').fill('已核对实验评分表，成绩应更正为六十分。')
    await button('提交教师核查意见').click()
    await page.getByText('待教务审核', { exact: true }).last().waitFor()
    checks.push('teacher recommendation')
    await shot('teacher')
    await roles('教务')
    await heading('教学指标明细')
    await button('学业预警').click()
    await page.getByText('暂无已发布样本', { exact: true }).waitFor()
    checks.push('zero denominator metric')
    await shot('admin')
    await menu('成绩审核')
    await button('查看记录 →').first().click()
    await page.getByLabel('具体说明').fill('已审核原始记录，准予重新发布。')
    await button('记录教务审核决定').click()
    await page.getByText('待教师发布', { exact: true }).last().waitFor()
    await roles('学生')
    await menu('成绩单')
    await page.getByText('59.99', { exact: true }).waitFor()
    checks.push('approval preserves original grade')
    await roles('教师')
    await button('查看记录 →').first().click()
    await button('发布更正成绩').click()
    await roles('学生')
    await menu('成绩单')
    await page.getByText('60.00', { exact: true }).waitFor()
    checks.push('published correction visible')
    await page.reload()
    await page.getByText('60.00', { exact: true }).waitFor()
    checks.push('reload retains tab-local edits')
    await roles('教务')
    await menu('排课结果')
    await button('下一步').click()
    await button('下一步').click()
    await page.getByText('周三 · 教 201', { exact: true }).waitFor()
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), true)
    checks.push('layout within viewport')
    const other = await context.newPage()
    await other.goto(url + '#/student/grades')
    await other.getByText('59.99', { exact: true }).waitFor()
    await other.close()
    await page.getByRole('link',{name:'实测证据',exact:true}).click()
    await heading('实测证据')
    const index=await (await context.request.get(url+'measurements/index.json')).json()
    if(index.includes('sql-matrix')){
      await page.locator('tbody td').filter({hasText:/^课程目录$/}).waitFor()
      await page.getByText('展开逐次测量').click()
      assert(await page.getByText('原索引方案',{exact:false}).isVisible())
      checks.push('benchmark source read from published SQL matrix')
    }
    await shot('measured-records')
    checks.push('separate tabs isolate sessions')
    assert.deepEqual(errors, [])
    assert.deepEqual(apiRequests, [])
    checks.push('no page errors or live API calls')
    receipts.push({ name, width, height, checks })
    await context.close()
  }
  const result = { scope: 'STATIC_SYNTHETIC_BROWSER_ONLY', browser: browser.version(), checks: receipts }
  console.log(JSON.stringify(result, null, 2))
  if (evidence) await writeFile(resolve(evidence, 'preview-browser.json'), JSON.stringify(result, null, 2))
} finally {
  await browser?.close()
  await new Promise((resolve) => server.close(resolve))
}
