import { check } from 'k6';
import { event } from './ingest.js';

export const options = { vus: 1, iterations: 1, thresholds: { checks: ['rate==1'] } };

export default function () {
  const data = { run: 'test', effectiveAt: '2026-01-01T00:00:00.000Z' };
  const events = Array.from({ length: 100 }, (_, n) => event(n, data));
  check(events, {
    '50 new employees per block': rows => rows.filter(r => r.employeeId.startsWith('NEW-')).length === 50,
    '46 distinct updates per block': rows => new Set(rows.slice(50, 96).map(r => r.employeeId)).size === 46,
    'duplicate 96 exactly replays 94': rows => JSON.stringify(rows[96]) === JSON.stringify(rows[94]),
    'duplicate 97 exactly replays 95': rows => JSON.stringify(rows[97]) === JSON.stringify(rows[95]),
    'out-of-order 98 targets 94 with a lower version': rows => rows[98].employeeId === rows[94].employeeId && rows[98].sourceSeq < rows[94].sourceSeq,
    'out-of-order 99 targets 95 with a lower version': rows => rows[99].employeeId === rows[95].employeeId && rows[99].sourceSeq < rows[95].sourceSeq,
    'update IDs stay inside the seeded 10k': () => {
      const id = event(99999951, data).employeeId;
      return id.startsWith('API-') && Number(id.slice(4)) >= 0 && Number(id.slice(4)) < 10000;
    },
  });
}
