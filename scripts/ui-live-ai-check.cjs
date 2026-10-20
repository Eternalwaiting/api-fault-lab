// 显式开启才消费真实模型额度；不拦截网络或替换响应。
const { chromium } = require(process.env.AFL_PLAYWRIGHT_PATH || 'playwright');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const base = process.env.AFL_URL || 'http://127.0.0.1:19090';
const output = path.resolve('runtime/evidence');

async function main() {
  if (process.env.AFL_VERIFY_LIVE_AI !== '1') throw new Error('请显式设置 AFL_VERIFY_LIVE_AI=1，再运行真实 AI 浏览器验收。');
  fs.mkdirSync(output, { recursive: true });
  const browser = await chromium.launch({
    headless: true,
    ...(process.env.AFL_BROWSER_EXECUTABLE ? { executablePath: process.env.AFL_BROWSER_EXECUTABLE } : {})
  });
  const errors = [];
  try {
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, acceptDownloads: true });
    const page = await context.newPage();
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(base);
    await page.waitForFunction(() => !document.getElementById('run').disabled);
    assert.equal(await page.locator('input[name=mode]:checked').inputValue(), 'AI');
    await page.locator('#example').selectOption('recovery');
    assert.doesNotMatch(await page.locator('#requirement').inputValue(), /超时/);
    const responsePromise = page.waitForResponse(response =>
      response.url() === base + '/api/drills' && response.request().method() === 'POST', { timeout: 180000 });
    await page.locator('#run').click();
    const response = await responsePromise;
    assert.equal(response.status(), 200);
    const report = await response.json();
    assert.equal(report.mode, 'AI');
    assert.equal(report.status, 'PASS');
    const round = report.rounds.at(-1);
    assert.equal(round.review.status, 'APPROVED');
    assert.deepEqual(round.execution.attempts.map(attempt => attempt.status), [503, 200]);
    const rule = report.knowledge.find(chunk => chunk.source === 'timeout-rules.md' && /requestTimeoutMs 固定为 (\d+)/.test(chunk.content));
    assert.ok(rule, '报告必须包含来自实际文件的默认规则');
    const expectedTimeout = Number(rule.content.match(/requestTimeoutMs 固定为 (\d+)/)[1]);
    assert.equal(round.plan.scenario.requestTimeoutMs, expectedTimeout);
    assert.ok(round.plan.knowledgeChunkIds.includes(rule.chunkId));
    assert.ok(report.knowledge.length <= 8);
    for (const role of ['EMBEDDING', 'PLANNER', 'REVIEWER'])
      assert.ok(report.modelCalls.some(call => call.role === role && call.status === 'SUCCESS'), role);
    await page.locator('#result-status').filter({ hasText: /^PASS$/ }).waitFor();
    assert.equal(await page.locator('#task-id').innerText(), report.id);
    assert.ok((await page.locator('#sources').innerText()).includes('timeout-rules.md'));
    assert.ok((await page.locator('#rounds').innerText()).includes('APPROVED'));
    assert.equal(await page.evaluate(() => document.activeElement.id), 'result-title');
    const downloadPromise = page.waitForEvent('download');
    await page.locator('#download').click();
    const download = await downloadPromise;
    const exportPath = path.join(output, '52-real-ai-export.md');
    await download.saveAs(exportPath);
    const exported = fs.readFileSync(exportPath, 'utf8');
    for (const marker of [report.id, rule.chunkId, 'timeout-rules.md', 'APPROVED', '503', '200'])
      assert.ok(exported.includes(marker), marker);
    await page.screenshot({ path: path.join(output, '52-real-ai-desktop.png'), fullPage: true });
    const mobile = await browser.newContext({ viewport: { width: 390, height: 844 } });
    // 恢复同一真实报告，只发 GET；窄屏检查不重复调用模型。
    await mobile.addInitScript(id => localStorage.setItem('afl-last-report', id), report.id);
    const narrow = await mobile.newPage();
    narrow.on('pageerror', error => errors.push(error.message));
    await narrow.goto(base);
    await narrow.locator('#result-status').filter({ hasText: /^PASS$/ }).waitFor();
    assert.equal(await narrow.locator('#task-id').innerText(), report.id);
    assert.ok((await narrow.locator('#sources').innerText()).includes(rule.chunkId));
    for (const target of [page, narrow]) {
      assert.equal(await target.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
      assert.deepEqual(await target.locator('#rounds tbody tr td:nth-child(2)').allTextContents(), ['503', '200']);
    }
    await narrow.screenshot({ path: path.join(output, '52-real-ai-mobile.png'), fullPage: true });
    assert.deepEqual(errors, []);
    const evidence = {
      checkedAt: new Date().toISOString(), status: 'PASS', taskId: report.id,
      mode: report.mode, actualHttpCodes: [503, 200], review: round.review.status,
      expectedTimeout, actualTimeout: round.plan.scenario.requestTimeoutMs,
      ruleChunkId: rule.chunkId, ruleScore: rule.score, contextChunks: report.knowledge.length,
      exportMatches: true, desktopAndMobileSameReport: true, horizontalOverflow: false,
      jsErrors: errors, networkMocked: false
    };
    fs.writeFileSync(path.join(output, '52-real-ai-browser.json'), JSON.stringify(evidence, null, 2));
    console.log(JSON.stringify(evidence, null, 2));
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
