const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('src/main/resources/static/js/reservation-new.js', 'utf8');
const day = '2099-06-09';
const time = (value) => new Date(`${day}T${value}:00`).getTime();

function page(role = 'STUDENT') {
  const elements = new Map();
  const element = (id) => {
    if (!elements.has(id)) {
      const classes = new Set(['d-none']);
      elements.set(id, { value: '', disabled: false, textContent: '', listeners: {},
        classList: { add: (v) => classes.add(v), remove: (v) => classes.delete(v), contains: (v) => classes.has(v) },
        addEventListener(name, fn) { this.listeners[name] = fn; }
      });
    }
    return elements.get(id);
  };
  const requests = [];
  const chart = { option: null, click: null, loading: false,
    clear() { this.option = null; }, showLoading() { this.loading = true; }, hideLoading() { this.loading = false; },
    resize() {}, setOption(value) { this.option = value; },
    convertFromPixel(_, point) { return [point[0], 0.5]; },
    getZr() { return { on: (_, fn) => { this.click = fn; } }; }
  };
  const context = vm.createContext({ console, Date, Promise, currentUserRole: role, window: {},
    document: { getElementById: element, querySelector: (selector) => element(selector) },
    echarts: { init: () => chart },
    axios: { get: (url) => new Promise((resolve, reject) => requests.push({ url, resolve, reject })) }
  });
  vm.runInContext(source, context);
  element('datePicker').value = day;
  return { context, element, chart, requests,
    click: (value) => chart.click({ offsetX: time(value), offsetY: 50 }) };
}
const flush = async () => { await Promise.resolve(); await Promise.resolve(); await Promise.resolve(); };
async function load(p, data = []) {
  const done = p.context.loadSchedule();
  p.requests.at(-1).resolve({ data: { success: true, data } });
  await done;
}
const booking = (role, start = '10:00', end = '11:00', status = 1) => ({
  role, start_time: `${day}T${start}:00`, end_time: `${day}T${end}:00`, reservation_status: status
});

test('ADMIN and pending reservations have explicit labels and distinct colours', async () => {
  const p = page();
  await load(p, [booking('ADMIN'), booking('STUDENT', '12:00', '13:00', 0)]);
  const values = p.chart.option.series[0].data.map((v) => v.value);
  assert.equal(values[1][3], '#6f42c1');
  assert.equal(values[1][4], '管理员·已预约');
  assert.equal(values[2][4], '学生·待审批占用');
  assert.notEqual(values[1][3], values[2][3]);
});

test('end may meet an occupied start boundary without overlap', async () => {
  const p = page();
  await load(p, [booking('TEACHER')]);
  p.click('09:00'); p.click('10:00');
  assert.equal(p.element('submitBtn').disabled, false);
  assert.equal(p.element('applyEndTime').value, `${day}T10:00:00`);
});

test('start may meet an occupied end boundary', async () => {
  const p = page(); await load(p, [booking('STUDENT')]);
  p.click('11:00'); p.click('12:00');
  assert.equal(p.element('submitBtn').disabled, false);
});

test('range crossing ADMIN reservation is blocked for students', async () => {
  const p = page(); await load(p, [booking('ADMIN')]);
  p.click('09:00'); p.click('12:00');
  assert.equal(p.element('submitBtn').disabled, true);
  assert.match(p.element('selectionMessage').textContent, /其他预约冲突/);
});

test('teacher override warning includes ADMIN and pending occupancy', async () => {
  const p = page('TEACHER'); await load(p, [booking('ADMIN', '10:00', '11:00', 0)]);
  p.click('09:00'); p.click('12:00');
  assert.equal(p.element('submitBtn').disabled, false);
  assert.equal(p.element('submitBtn').textContent, '确认覆盖并提交');
});

test('pending start cannot be selected by a student and explains why', async () => {
  const p = page(); await load(p, [booking('STUDENT', '10:00', '11:00', 0)]);
  p.click('10:15');
  assert.equal(p.context.clickCount, 0);
  assert.match(p.element('selectionMessage').textContent, /待审批占用/);
});

test('22:00 cannot expand into 22:15', async () => {
  const p = page(); await load(p);
  p.click('22:00'); p.click('22:00');
  assert.equal(p.element('applyEndTime').value, '');
  assert.equal(p.element('submitBtn').disabled, true);
  p.click('21:45'); p.click('22:00');
  assert.equal(p.element('applyEndTime').value, `${day}T22:00:00`);
  assert.equal(p.element('submitBtn').disabled, false);
});

test('date reload clears hidden fields and blocks old chart clicks', async () => {
  const p = page(); await load(p);
  p.click('09:00'); p.click('09:30');
  p.context.loadSchedule();
  p.click('10:00'); p.click('10:30');
  assert.equal(p.element('applyStartTime').value, '');
  assert.equal(p.element('applyEndTime').value, '');
  assert.equal(p.element('submitBtn').disabled, true);
  assert.equal(p.chart.option, null);
});

test('stale responses never overwrite the latest date schedule', async () => {
  const p = page();
  const first = p.context.loadSchedule(); const request = p.requests.at(-1);
  await load(p, [booking('ADMIN')]);
  request.resolve({ data: { success: true, data: [booking('TEACHER')] } });
  await first;
  assert.equal(p.context.scheduleData[0].role, 'ADMIN');
});

test('failed load stays unavailable and local retry recovers', async () => {
  const p = page();
  const failed = p.context.loadSchedule();
  p.requests.at(-1).reject(new Error('offline')); await failed;
  assert.equal(p.context.scheduleReady, false);
  assert.equal(p.element('scheduleError').classList.contains('d-none'), false);
  p.click('09:00'); assert.equal(p.context.clickCount, 0);
  const retry = p.element('retrySchedule').listeners.click();
  p.requests.at(-1).resolve({ data: { success: true, data: [] } }); await retry;
  assert.equal(p.context.scheduleReady, true);
  assert.equal(p.element('scheduleError').classList.contains('d-none'), true);
});

test('invalid API envelope stays unavailable', async () => {
  const p = page(); const done = p.context.loadSchedule();
  p.requests.at(-1).resolve({ data: '<html>login</html>' }); await done;
  assert.equal(p.context.scheduleReady, false);
  assert.equal(p.element('submitBtn').disabled, true);
});

test('beginning a replacement selection removes previously submitted values', async () => {
  const p = page(); await load(p);
  p.click('09:00'); p.click('09:30'); p.click('12:00');
  assert.equal(p.element('applyStartTime').value, '');
  assert.equal(p.element('applyEndTime').value, '');
  assert.equal(p.element('submitBtn').disabled, true);
});

test('submission cannot bypass unavailable schedule state', async () => {
  const p = page(); let prevented = false;
  p.element('form[action="/reservation/apply"]').listeners.submit({ preventDefault() { prevented = true; } });
  assert.equal(prevented, true);
  await flush();
});
