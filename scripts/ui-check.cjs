// 通过真实浏览器操作本地应用，不拦截或替换网络响应。
const { chromium } = require(process.env.AFL_PLAYWRIGHT_PATH || 'playwright');
const fs = require('node:fs');
const assert = require('node:assert/strict');
const path = require('node:path');
const base = process.env.AFL_URL || 'http://127.0.0.1:19090';
const uiWaitTimeout = Number(process.env.AFL_UI_WAIT_TIMEOUT_MS || 120000);
const output = path.resolve('runtime/evidence');
async function main() {
  fs.mkdirSync(output, { recursive: true });
  const browser = await chromium.launch({ headless: true, ...(process.env.AFL_BROWSER_EXECUTABLE ? { executablePath: process.env.AFL_BROWSER_EXECUTABLE } : {}) });
  const evidence = { url: base, startedAt: new Date().toISOString(), checks: [] };
  try {
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, colorScheme: 'light', acceptDownloads: true });
    const page = await context.newPage();
    page.setDefaultTimeout(uiWaitTimeout);
    page.setDefaultNavigationTimeout(uiWaitTimeout);
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    const concurrentRequest = page.request.post(base + '/api/drills', { data: {
      requirement: 'browser concurrency fixture: actual local delay', manualScenario: {
        scenarioType: 'DELAY', failureCount: 0, failureStatus: 503, responseDelayMs: 2800,
        maxAttempts: 1, requestTimeoutMs: 5000, expectedOutcome: 'SUCCESS'
      }
    } });
    // 先等待真实服务进入忙碌状态，再打开另一个页面。
    for (let poll = 0; poll < 100; poll++) {
      if ((await (await page.request.get(base + '/api/status')).json()).busy) break;
    }
    await page.goto(base);
    await page.waitForFunction(() => document.getElementById('run').disabled);
    const concurrentResult = await concurrentRequest;
    assert.equal(concurrentResult.status(), 200);
    await page.waitForFunction(() => !document.getElementById('run').disabled, { timeout: 10000 });
    evidence.checks.push('另一页面能够观察忙碌状态，并在任务结束后自动恢复操作');
    await page.locator('#chat-status').filter({ hasText: /配置/ }).waitFor();
    await page.getByText('手动演练', { exact: true }).click();
    await page.locator('#run').click();
    await page.locator('#result-status').filter({ hasText: /^PASS$/ }).waitFor();
    assert.equal(await page.evaluate(() => document.activeElement.id), 'result-title');
    assert.deepEqual(await page.locator('#rounds tbody tr td:nth-child(2)').allTextContents(), ['503', '200']);
    evidence.checks.push('桌面恢复场景：真实观察到 503、200，程序 PASS');
    const downloadPromise = page.waitForEvent('download');
    await page.locator('#download').click();
    const download = await downloadPromise;
    const reportPath = path.join(output, 'browser-export.md');
    await download.saveAs(reportPath);
    const exported = fs.readFileSync(reportPath, 'utf8');
    assert.ok(exported.includes(await page.locator('#task-id').innerText()));
    assert.ok(exported.includes('503') && exported.includes('200') && exported.includes('MANUAL'));
    evidence.checks.push('浏览器下载报告与页面任务编号和 HTTP 记录一致');
    await page.screenshot({ path: path.join(output, 'desktop-pass.png'), fullPage: true });
    await page.locator('#failureCount').fill('2');
    await page.locator('#run').click();
    await page.locator('#result-status').filter({ hasText: /^FAIL$/ }).waitFor();
    assert.deepEqual(await page.locator('#rounds tbody tr td:nth-child(2)').allTextContents(), ['503', '503']);
    evidence.checks.push('桌面正常失败：两次故障且最多两次请求，程序 FAIL，没有第三次请求');
    await page.screenshot({ path: path.join(output, 'desktop-fail.png'), fullPage: true });
    // 仅在模型未配置时验证真实的缺配置路径，避免意外消费模型额度。
    const status = await (await page.request.get(base + '/api/status')).json();
    if (!status.ai.configured) {
      await page.getByText('AI 演练', { exact: true }).click();
      await page.locator('#run').click();
      await page.locator('#result-status').filter({ hasText: /^MODEL_ERROR$/ }).waitFor();
      await page.screenshot({ path: path.join(output, 'desktop-model-error.png'), fullPage: true });
      evidence.checks.push('缺少模型配置时显示 MODEL_ERROR，没有伪造执行结果');
      await page.locator('#rebuild').click();
      await page.locator('#global-message').filter({ hasText: /失败/ }).waitFor();
      evidence.checks.push('Embedding 未配置时明确提示知识库重建失败');
    }
    const mobile = await browser.newContext({ viewport: { width: 390, height: 844 }, colorScheme: 'light' });
    const narrow = await mobile.newPage();
    narrow.setDefaultTimeout(uiWaitTimeout);
    narrow.setDefaultNavigationTimeout(uiWaitTimeout);
    await narrow.goto(base);
    await narrow.getByText('手动演练', { exact: true }).click();
    await narrow.locator('#example').selectOption('delay');
    await narrow.locator('#run').click();
    await narrow.locator('#result-status').filter({ hasText: /^PASS$/ }).waitFor();
    assert.deepEqual(await narrow.locator('#rounds tbody tr td:nth-child(2)').allTextContents(), ['超时/无响应', '超时/无响应']);
    for (const [label, target] of [['desktop', page], ['mobile', narrow]]) {
      assert.equal(await target.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, label + ' horizontal overflow');
      const overlaps = await target.evaluate(() => {
        const a = document.querySelector('.composer').getBoundingClientRect();
        const b = document.querySelector('.results').getBoundingClientRect();
        return Math.min(a.right, b.right) - Math.max(a.left, b.left) > 1 && Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top) > 1;
      });
      assert.equal(overlaps, false, label + ' overlapping workspace sections');
    }
    await narrow.screenshot({ path: path.join(output, 'mobile-timeout.png'), fullPage: true });
    await narrow.emulateMedia({ colorScheme: 'dark' });
    await narrow.screenshot({ path: path.join(output, 'mobile-dark.png'), fullPage: true });
    evidence.checks.push('390px 窄屏：真实超时、无横向溢出，已保存暗色主题截图');
    assert.deepEqual(errors, []);
    evidence.checks.push('浏览器未出现 JavaScript 错误');
    evidence.result = 'PASS';
    fs.writeFileSync(path.join(output, 'browser-check.json'), JSON.stringify(evidence, null, 2));
    console.log(JSON.stringify(evidence, null, 2));
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
