import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'

const site = 'https://ryt200606.github.io/kexu-course-selection/'
const distribution = fileURLToPath(new URL('../dist-preview/', import.meta.url))
const originals = fileURLToPath(new URL('../../performance/results/', import.meta.url))
const evidence = process.env.COURSE_BROWSER_EVIDENCE
const digest = (bytes) => createHash('sha256').update(bytes).digest('hex')
const browser = await chromium.launch({
  headless: true,
  ...(process.env.COURSE_BROWSER_EXECUTABLE ? { executablePath: process.env.COURSE_BROWSER_EXECUTABLE } : {}),
})

try {
  const context = await browser.newContext({
    viewport: { width: 1440, height: 1000 },
    reducedMotion: 'reduce',
  })
  const page = await context.newPage()
  const failures = [],
    liveRequests = [],
    files = []
  page.on('pageerror', (e) => failures.push(e.message))
  page.on('request', (request) => {
    if (new URL(request.url()).pathname.split('/').includes('api')) liveRequests.push(request.url())
  })
  page.setDefaultTimeout(20000)
  if (evidence) await mkdir(evidence, { recursive: true })
  assert.deepEqual(await context.cookies(), [])
  const response = await page.goto(site, { waitUntil: 'domcontentloaded', timeout: 45000 })
  assert.equal(response?.status(), 200)
  assert.equal(new URL(page.url()).origin, new URL(site).origin)
  await page.getByText('公开交互样例', { exact: true }).waitFor()
  await page.getByRole('button', { name: '方案对比', exact: true }).click()
  await page.getByRole('button', { name: '比较两个方案', exact: true }).click()
  await page.getByText('存在资格或课程冲突 · 6 学分').waitFor()
  await page.getByText('方案可行 · 6 学分').waitFor()
  if (evidence) await page.screenshot({ path: resolve(evidence, 'public-student-plans.png'), fullPage: true })

  const roles = page.getByRole('navigation', { name: '体验角色' })
  await roles.getByRole('link', { name: '教师', exact: true }).click()
  await page.getByRole('button', { name: '查看记录 →', exact: true }).first().click()
  await page.getByLabel('建议成绩', { exact: true }).fill('60.00')
  await page.getByLabel('具体说明').fill('查阅样例评分表后，建议复核为六十分。')
  await page.getByRole('button', { name: '提交教师核查意见', exact: true }).click()
  await page.getByText('待教务审核', { exact: true }).last().waitFor()
  await roles.getByRole('link', { name: '教务', exact: true }).click()
  await page.getByRole('heading', { name: '教学指标明细', exact: true }).waitFor()
  await page.getByRole('link', { name: '实测证据', exact: true }).click()
  await page
    .locator('tbody td')
    .filter({ hasText: /^课程目录$/ })
    .waitFor()
  if (evidence) await page.screenshot({ path: resolve(evidence, 'public-sql-evidence.png'), fullPage: true })

  async function verify(relative, localName = relative, originalName) {
    const remote = await context.request.get(new URL(relative, site).href, { timeout: 30000 })
    assert.equal(remote.status(), 200, relative)
    const bytes = await remote.body()
    const sha256 = digest(bytes)
    assert.equal(
      sha256,
      digest(await readFile(resolve(distribution, localName))),
      'published bytes vs local build: ' + relative,
    )
    if (originalName)
      assert.equal(
        sha256,
        digest(await readFile(resolve(originals, originalName))),
        'published bytes vs source: ' + relative,
      )
    files.push({ path: relative, status: remote.status(), bytes: bytes.length, sha256 })
    return bytes
  }
  await verify('index.html')
  const scriptURL = new URL(
    await page.locator('script[type="module"][src]').first().getAttribute('src'),
    site,
  )
  assert.equal(scriptURL.origin, new URL(site).origin)
  assert(scriptURL.pathname.startsWith(new URL(site).pathname + 'assets/'))
  await verify(scriptURL.pathname.slice(new URL(site).pathname.length))
  const manifest = JSON.parse((await verify('measurements/index.json')).toString('utf8'))
  assert(Array.isArray(manifest) && manifest.includes('sql-matrix'))
  assert.equal(new Set(manifest).size, manifest.length)
  for (const id of manifest) {
    assert.match(id, /^[a-z0-9-]+$/)
    await verify('measurements/' + id + '.json', 'measurements/' + id + '.json', id + '.json')
  }
  assert.deepEqual(failures, [])
  assert.deepEqual(liveRequests, [])
  const receipt = {
    scope: 'ANONYMOUS_PUBLIC_GITHUB_PAGES_SYNTHETIC_INTERACTION_AND_HASHES',
    site,
    checkedAt: new Date().toISOString(),
    browser: browser.version(),
    checks: [
      'anonymous, three-role navigation',
      'compared conflicting and feasible plans',
      'teacher recommendation persisted in tab',
      'rendered measured SQL evidence',
      'public HTML and entry JS match local distribution',
      'all published original measurement files match',
    ],
    files,
  }
  console.log(JSON.stringify(receipt, null, 2))
  if (evidence) await writeFile(resolve(evidence, 'public-pages.json'), JSON.stringify(receipt, null, 2))
} finally {
  await browser.close()
}
