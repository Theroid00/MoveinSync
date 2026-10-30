import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const latency = new Trend('ingest_ms', true);
const outcomes = new Counter('ingest_outcomes');
const base = __ENV.BASE_URL || 'http://localhost:8080';
export const options = {
  scenarios: { ingest: {
    executor: 'constant-arrival-rate', rate: Number(__ENV.RATE || 100), timeUnit: '1s',
    duration: '60s', preAllocatedVUs: 200, maxVUs: 1000,
  } },
  summaryTrendStats: ['min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)', 'p(99.9)'],
  setupTimeout: '180s',
};

function hr(id) {
  return { name: `Employee ${id}`, email: `${id}@bench.example`, phone: '+919000000999',
    managerId: null, officeCode: 'BLR-A', shiftCode: 'MORNING' };
}

export function setup() {
  const auth = http.post(`${base}/api/v1/auth/token`, JSON.stringify({ username: 'hr@acme', password: 'demo123' }),
    { headers: { 'Content-Type': 'application/json' } });
  if (auth.status !== 200) throw new Error(`token request failed: ${auth.status}`);
  const token = auth.json('accessToken');
  const run = __ENV.RUN_ID || String(Date.now());
  const effectiveAt = new Date().toISOString();
  for (let start = 0; start < 10000; start += 1000) {
    const lines = [];
    for (let i = start; i < start + 1000; i++) {
      const id = `API-${i}`;
      lines.push(JSON.stringify({ eventId: `seed-${run}-${i}`, sourceSeq: 900000000 + i,
        eventType: 'UPSERT', employeeId: id, effectiveAt, hr: hr(id) }));
    }
    const r = http.post(`${base}/api/v1/hr/events/batch`, lines.join('\n'),
      { headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/x-ndjson' }, timeout: '60s' });
    if (r.status !== 200 || r.json('summary.APPLIED') !== 1000) throw new Error(`baseline batch failed: ${r.status}`);
  }
  return { token, run, effectiveAt };
}

export function event(n, data) {
  const mode = n % 100;
  // Duplicate modes replay the exact payload of iterations 94/95 in the same block.
  if (mode === 96 || mode === 97) return event(n - 2, data);
  const update = mode >= 50;
  const target = mode >= 98 ? n - 4 : n;
  const targetMode = target % 100;
  const id = update ? `API-${(Math.floor(target / 100) * 46 + targetMode - 50) % 10000}` : `NEW-${data.run}-${n}`;
  return { eventId: `${data.run}-${n}`, sourceSeq: 1000000000 + n - (mode >= 98 ? 1000 : 0),
    eventType: 'UPSERT', employeeId: id, effectiveAt: data.effectiveAt,
    hr: { ...hr(id), shiftCode: update ? 'NIGHT' : 'MORNING' } };
}

export default function (data) {
  const n = exec.scenario.iterationInTest;
  const r = http.post(`${base}/api/v1/hr/events`, JSON.stringify(event(n, data)),
    { headers: { Authorization: `Bearer ${data.token}`, 'Content-Type': 'application/json' },
      tags: { name: 'ingest' }, timeout: '15s' });
  latency.add(r.timings.duration, { status: String(r.status) });
  check(r, { 'ingest HTTP 200': response => response.status === 200 });
  if (r.status === 200) outcomes.add(1, { outcome: r.json('outcome') });
}
