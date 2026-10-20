'use strict';
const $ = (id) => document.getElementById(id);
const examples = {
  recovery: { requirement: '模拟查询订单接口：第一次返回503，第二次返回正常订单。客户端最多请求两次，检查是否恢复成功。', scenarioType: 'RECOVER_AFTER_FAILURES', failureCount: 1, maxAttempts: 2, responseDelayMs: 0, requestTimeoutMs: 1200, expectedOutcome: 'RECOVERED' },
  normal: { requirement: '正常返回订单，客户端最多请求一次，超时1000ms，期望首次成功。', scenarioType: 'NORMAL', failureCount: 0, maxAttempts: 1, responseDelayMs: 0, requestTimeoutMs: 1000, expectedOutcome: 'SUCCESS' },
  error: { requirement: '查询订单接口持续返回503，客户端最多请求三次，超时1000ms，检查错误耗尽。', scenarioType: 'CONTINUOUS_ERROR', failureCount: 0, maxAttempts: 3, responseDelayMs: 0, requestTimeoutMs: 1000, expectedOutcome: 'EXHAUSTED' },
  delay: { requirement: '查询订单接口延迟900ms返回200，客户端超时150ms，最多请求两次，检查全部超时。', scenarioType: 'DELAY', failureCount: 0, maxAttempts: 2, responseDelayMs: 900, requestTimeoutMs: 150, expectedOutcome: 'TIMEOUT' }
};
let busy = false;
let remoteBusy = false;
let statusTimer;
function node(tag, text, className) {
  const element = document.createElement(tag);
  if (text !== undefined) element.textContent = text;
  if (className) element.className = className;
  return element;
}
function notice(text, error = false) {
  $('global-message').textContent = text;
  $('global-message').className = error ? 'notice error' : 'notice';
  $('global-message').hidden = !text;
}
function mode() { return document.querySelector('input[name=mode]:checked').value; }
function syncMode() {
  const readOnly = mode() === 'READ_ONLY_REAL';
  $('drill-fields').hidden = readOnly;
  $('readonly-fields').hidden = !readOnly;
  $('requirement').required = !readOnly;
  $('requirement').disabled = readOnly || busy || remoteBusy;
  $('example').disabled = readOnly || busy || remoteBusy;
  $('manual-fields').hidden = mode() !== 'MANUAL';
  $('mode-note').textContent = mode() === 'MANUAL' ? '手动配置由 Java 执行；AI 只提供独立解释与审核' : 'AI 配置联通状态以当次调用记录为准';
  $('run-label').textContent = readOnly ? '执行只读测试' : busy ? '执行中' : '执行演练';
  $('readonly-device-id').disabled = !readOnly || busy || remoteBusy;
  $('readonly-count').disabled = !readOnly || busy || remoteBusy;
  $('save-readonly-target').disabled = !readOnly || busy || remoteBusy;
  syncFields();
}
function syncFields() {
  const manual = mode() === 'MANUAL';
  $('failureCount').disabled = !manual || busy || remoteBusy || $('scenarioType').value !== 'RECOVER_AFTER_FAILURES';
  $('responseDelayMs').disabled = !manual || busy || remoteBusy || $('scenarioType').value !== 'DELAY';
}
function setBusy(value, label = '演练进行中') {
  busy = value;
  document.querySelectorAll('#drill-form input,#drill-form select,#drill-form textarea,#drill-form button').forEach((element) => {
    element.disabled = value || remoteBusy;
  });
  $('rebuild').disabled = value || remoteBusy;
  $('pending').hidden = !value;
  $('pending-text').textContent = label;
  $('run-label').textContent = value ? '执行中' : '执行演练';
  document.querySelector('.results').setAttribute('aria-busy', String(value));
  syncFields();
}
async function request(path, options) {
  let response;
  try { response = await fetch(path, options); }
  catch { throw new Error('无法连接本地服务，请检查服务是否仍在运行。'); }
  const data = await response.json();
  if (!response.ok) {
    const errors = { BUSY: '另一个演练或知识库重建正在进行，请稍后再试。', INVALID_REQUEST: '配置无效，请检查必填字段和参数范围。', MODEL_ERROR: `模型调用失败：${data.message || '请检查本地配置与服务连接。'}`, EXECUTION_ERROR: '本地操作失败，请检查知识目录和运行日志。' };
    throw new Error(errors[data.status] || `请求失败 (${response.status})`);
  }
  return data;
}
async function refresh() {
  clearTimeout(statusTimer);
  try {
    const status = await request('/api/status');
    $('chat-status').textContent = status.ai.chatConfigured ? status.ai.chatModel + ' / 已配置' : '未配置';
    $('embedding-status').textContent = status.ai.embeddingConfigured ? status.ai.embeddingModel + ' / 已配置' : '未配置';
    $('knowledge-status').textContent = status.knowledge.ready ? `${status.knowledge.chunks} 片段 / ${status.knowledge.dimensions} 维` : ({ NOT_BUILT: '未建立', STALE: '内容已变更', KNOWLEDGE_ERROR: '文件不可读' }[status.knowledge.state] || status.knowledge.state);
    const target = await request('/api/read-only/status');
    $('readonly-target-status').textContent = target.configured
      ? `已配置：${target.deviceId} / ${target.baseUrl}${target.endpoint} / ${target.authMode === 'SOURCE_WHITELIST_NO_TOKEN' ? '源码白名单，无需 token' : target.authMode}`
      : `未配置：${target.error || '请填写设备 ID 并保存'}`;
    if (target.configured && !$('readonly-device-id').value) $('readonly-device-id').value = target.deviceId;
    const wasRemoteBusy = remoteBusy;
    remoteBusy = status.busy;
    setBusy(busy);
    if (status.busy && !busy) notice('另一个任务正在运行。');
    if (!status.busy && wasRemoteBusy && $('global-message').textContent === '另一个任务正在运行。') notice('');
    if (status.busy && !busy) statusTimer = setTimeout(refresh, 1200);
  } catch (error) { notice(error.message, true); }
}
function details(title, content, asCode = false) {
  const item = node('details');
  item.append(node('summary', title), node(asCode ? 'pre' : 'p', content));
  return item;
}
function renderAiAssistance(container, assistance) {
  const result = assistance || { status: 'NOT_REQUESTED', explanation: '', findings: [], error: '' };
  container.replaceChildren();
  const label = { COMPLETED: '已完成', FAILED: '失败，Java 结果保留', NOT_REQUESTED: '未调用' }[result.status] || result.status;
  container.append(node('p', `辅助状态：${label}`, 'review-state' + (result.status === 'COMPLETED' ? '' : ' bad')));
  if (result.explanation) container.append(node('p', result.explanation));
  (result.findings || []).forEach((finding) => container.append(node('p', finding)));
  if (result.error) container.append(node('p', result.error, 'caption'));
}
function render(report, moveFocus = true) {
  $('empty').hidden = true;
  $('report').hidden = false;
  $('readonly-report').hidden = true;
  $('result-status').textContent = report.status;
  $('result-status').className = 'badge' + (['FAIL', 'MODEL_ERROR', 'EXECUTION_ERROR'].includes(report.status) ? ' bad' : report.status === 'PASS' ? '' : ' warn');
  $('report-mode').textContent = report.mode === 'AI' ? 'AI 演练' : '手动演练 / AI 辅助独立';
  $('report-stage').textContent = `阶段：${report.stage}`;
  $('report-error').textContent = report.error;
  $('report-error').hidden = !report.error;
  $('task-id').textContent = report.id;
  $('task-time').textContent = new Date(report.createdAt).toLocaleString('zh-CN', { hour12: false });
  $('task-requirement').textContent = report.requirement;
  $('download').href = `/api/drills/${encodeURIComponent(report.id)}/report`;
  $('download').hidden = false;
  $('sources').replaceChildren();
  if (!report.knowledge.length) $('sources').append(node('p', report.mode === 'MANUAL' ? '手动模式未检索知识库。' : '本次未取得检索结果。', 'caption'));
  report.knowledge.forEach((chunk) => {
    const score = chunk.score == null ? '' : ` / 相似度 ${chunk.score.toFixed(3)}`;
    $('sources').append(details(`${chunk.source} / ${chunk.chunkId}${score}`, chunk.content));
  });
  $('rounds').replaceChildren();
  report.rounds.forEach((round) => {
    const section = node('section', undefined, 'round');
    const heading = node('div', undefined, 'round-header');
    heading.append(node('h3', `第 ${round.number} 轮`));
    if (round.execution) heading.append(node('span', round.execution.status, 'badge' + (round.execution.status === 'PASS' ? '' : ' bad')));
    section.append(heading);
    if (round.revisionReason) section.append(details('修订原因', round.revisionReason));
    if (round.error) section.append(node('p', round.error, 'notice error'));
    if (round.plan) {
      section.append(node('p', round.plan.explanation));
      const config = details('生成配置', JSON.stringify(round.plan, null, 2), true);
      config.open = true;
      section.append(config);
    }
    if (round.execution) {
      const execution = round.execution;
      section.append(node('h4', `实际请求 / ${execution.attempts.length} 次 / ${execution.elapsedMs}ms`));
      const table = node('table');
      table.setAttribute('aria-label', `第${round.number}轮真实请求记录`);
      const head = node('thead'), row = node('tr');
      ['尝试', 'HTTP', '耗时', '客户端观察'].forEach((label) => row.append(node('th', label)));
      head.append(row);
      const body = node('tbody');
      execution.attempts.forEach((attempt) => {
        const tr = node('tr');
        [attempt.number, attempt.status ?? '超时/无响应', `${attempt.elapsedMs}ms`, attempt.result].forEach((value) => tr.append(node('td', String(value))));
        body.append(tr);
      });
      table.append(head, body);
      section.append(table, details('原始请求记录', JSON.stringify(execution.attempts, null, 2), true));
      section.append(node('h4', `程序断言 / ${execution.observedOutcome}`));
      execution.assertions.forEach((assertion) => {
        const line = node('div', undefined, 'assertion');
        line.append(node('span', `${assertion.passed ? 'PASS' : 'FAIL'} ${assertion.name}`, assertion.passed ? 'pass' : 'fail'), node('span', assertion.detail));
        section.append(line);
      });
    }
    if (report.mode === 'AI') section.append(node('h4', '独立审核'));
    if (report.mode === 'AI' && round.review) {
      section.append(node('p', round.review.status, 'review-state' + (round.review.status === 'APPROVED' ? '' : ' bad')));
      section.append(node('p', round.review.explanation));
      round.review.issues.forEach((issue) => section.append(node('p', issue)));
      section.append(details('审核引用', JSON.stringify({ knowledgeChunkIds: round.review.knowledgeChunkIds, evidenceIds: round.review.evidenceIds }, null, 2), true));
    } else if (report.mode === 'AI') section.append(node('p', '本轮未完成审核。', 'caption'));
    $('rounds').append(section);
  });
  if (report.mode === 'MANUAL' && report.rounds.length) {
    const section = node('section', undefined, 'evidence-section');
    section.append(node('h3', 'AI 辅助解释'));
    const output = node('div');
    renderAiAssistance(output, report.aiAssistance);
    section.append(output);
    $('rounds').append(section);
  }
  $('model-calls').replaceChildren();
  if (!report.modelCalls.length) $('model-calls').append(node('p', '本次没有模型调用记录。', 'caption'));
  report.modelCalls.forEach((call) => $('model-calls').append(node('div', `${call.role} / ${call.model || '未配置'} / ${call.status} / ${call.elapsedMs}ms\n输入 ${call.promptTokens ?? '未知'} tokens，输出 ${call.completionTokens ?? '未知'} tokens，向量维度 ${call.dimensions ?? '-'}${call.error ? '\n' + call.error : ''}`, 'call')));
  try { localStorage.setItem('afl-last-report', report.id); } catch { /* 本地存储不可用时仍可演练。 */ }
  if (moveFocus) {
    $('completion').textContent = `演练结束，结果 ${report.status}，${report.mode === 'AI' ? 'AI 演练' : '手动演练'}。`;
    $('result-title').focus({ preventScroll: true });
    if (matchMedia('(max-width: 760px)').matches) $('result-title').scrollIntoView({ block: 'start' });
  }
}
function renderReadOnly(report, moveFocus = true) {
  $('empty').hidden = true;
  $('report').hidden = true;
  $('readonly-report').hidden = false;
  $('readonly-status').textContent = report.status;
  $('readonly-status').className = 'badge' + (report.status === 'PASS' ? '' : report.status === 'FAIL' ? ' bad' : ' warn');
  $('readonly-error').textContent = report.error || '';
  $('readonly-error').hidden = !report.error;
  $('readonly-task-id').textContent = report.id;
  $('readonly-target').textContent = `GET ${report.endpoint}`;
  $('readonly-request-count').textContent = `${report.attempts.length} / ${report.requestedRequests}`;
  $('download').href = `/api/read-only/${encodeURIComponent(report.id)}/report`;
  $('download').hidden = false;
  $('readonly-attempts').replaceChildren();
  $('readonly-assertions').replaceChildren();
  renderAiAssistance($('readonly-ai-assistance'), report.aiAssistance);
  report.attempts.forEach((attempt) => {
    const line = node('div', undefined, 'assertion readonly-attempt');
    line.append(node('span', `GET ${attempt.number} / HTTP ${attempt.httpStatus ?? '无响应'} / ${attempt.elapsedMs}ms / ${attempt.result}`, attempt.result === 'SUCCESS' ? 'pass' : 'fail'));
    line.append(node('span', `业务码 ${attempt.businessCode ?? '-'} / 记录 ${attempt.recordsReturned}`));
    $('readonly-attempts').append(line);
    for (const [label, passed] of [
      ['响应结构有效', attempt.responseShapeValid],
      ['设备 ID 匹配', attempt.deviceIdMatches],
      ['采样时间字段存在', attempt.sampleTimePresent],
      ['至少一个传感器指标存在', attempt.hasMetric]
    ]) {
      const assertion = node('div', undefined, 'assertion');
      assertion.append(node('span', `${passed ? 'PASS' : 'FAIL'} ${label}`, passed ? 'pass' : 'fail'));
      assertion.append(node('span', attempt.result === 'AUTH_REQUIRED' ? '授权未通过' : attempt.result));
      $('readonly-assertions').append(assertion);
    }
  });
  $('readonly-side-effect').textContent = report.sideEffect;
  try { localStorage.setItem('afl-last-read-only-report', report.id); } catch { /* 本地存储不可用时仍可查看当前报告。 */ }
  if (moveFocus) {
    $('completion').textContent = `只读测试结束，结果 ${report.status}。`;
    $('result-title').focus({ preventScroll: true });
    if (matchMedia('(max-width: 760px)').matches) $('result-title').scrollIntoView({ block: 'start' });
  }
}
$('requirement').addEventListener('input', () => { $('char-count').textContent = `${$('requirement').value.length} / 2000`; });
$('example').addEventListener('change', () => {
  const example = examples[$('example').value];
  $('requirement').value = example.requirement;
  Object.keys(example).filter((key) => key !== 'requirement').forEach((key) => { $(key).value = example[key]; });
  $('failureStatus').value = '503';
  $('requirement').dispatchEvent(new Event('input'));
  syncFields();
});
document.querySelectorAll('input[name=mode]').forEach((radio) => radio.addEventListener('change', syncMode));
$('scenarioType').addEventListener('change', () => {
  const type = $('scenarioType').value;
  $('failureCount').value = type === 'RECOVER_AFTER_FAILURES' ? '1' : '0';
  $('responseDelayMs').value = type === 'DELAY' ? '900' : '0';
  $('expectedOutcome').value = { NORMAL: 'SUCCESS', CONTINUOUS_ERROR: 'EXHAUSTED', DELAY: 'TIMEOUT', RECOVER_AFTER_FAILURES: 'RECOVERED' }[type];
  syncFields();
});
$('drill-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  if (busy || remoteBusy) return;
  if (mode() === 'READ_ONLY_REAL') {
    notice('');
    setBusy(true, '真实只读查询进行中');
    try {
      const report = await request('/api/read-only/run', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ requestCount: Number($('readonly-count').value) })
      });
      renderReadOnly(report);
    } catch (error) { notice(error.message, true); }
    finally { setBusy(false); await refresh(); }
    return;
  }
  let manualScenario = null;
  if (mode() === 'MANUAL') {
    manualScenario = { scenarioType: $('scenarioType').value, expectedOutcome: $('expectedOutcome').value };
    ['failureCount', 'failureStatus', 'responseDelayMs', 'maxAttempts', 'requestTimeoutMs'].forEach((key) => { manualScenario[key] = Number($(key).value); });
    if (manualScenario.scenarioType !== 'RECOVER_AFTER_FAILURES') manualScenario.failureCount = 0;
    if (manualScenario.scenarioType !== 'DELAY') manualScenario.responseDelayMs = 0;
  }
  notice('');
  setBusy(true);
  try {
    const report = await request('/api/drills', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ requirement: $('requirement').value, manualScenario }) });
    render(report);
  } catch (error) { notice(error.message, true); }
  finally { setBusy(false); await refresh(); }
});
$('save-readonly-target').addEventListener('click', async () => {
  if (busy || remoteBusy) return;
  notice('');
  try {
    const target = await request('/api/read-only/config', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ deviceId: $('readonly-device-id').value.trim() })
    });
    $('readonly-target-status').textContent = target.configured
      ? `已配置：${target.deviceId} / ${target.baseUrl}${target.endpoint} / 源码白名单，无需 token`
      : `未配置：${target.error}`;
    notice('本地只读目标已保存；未修改外部服务配置。');
  } catch (error) { notice(error.message, true); }
});
$('rebuild').addEventListener('click', async () => {
  if (busy || remoteBusy) return;
  notice('');
  setBusy(true, '知识库重建中');
  try { const result = await request('/api/knowledge/rebuild', { method: 'POST' }); notice(`知识库已重建：${result.chunks} 片段，${result.dimensions} 维。`); }
  catch (error) { notice(error.message, true); }
  finally { setBusy(false); await refresh(); }
});
lucide.createIcons();
$('requirement').dispatchEvent(new Event('input'));
syncMode();
refresh();
try {
  const id = localStorage.getItem('afl-last-report');
  if (id) request(`/api/drills/${encodeURIComponent(id)}`).then((report) => render(report, false)).catch(() => {});
} catch { /* 没有 localStorage 时也不影响工具运行。 */ }
try {
  const id = localStorage.getItem('afl-last-read-only-report');
  if (id) request(`/api/read-only/${encodeURIComponent(id)}`).then((report) => {
    const readOnlyRadio = document.querySelector('input[name=mode][value=READ_ONLY_REAL]');
    if (readOnlyRadio) {
      readOnlyRadio.checked = true;
      syncMode();
    }
    renderReadOnly(report, false);
  }).catch(() => {});
} catch { /* 没有 localStorage 时也不影响工具运行。 */ }
