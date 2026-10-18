// Smoke test for the Supabase corpus mirror (server.js Task 39 addition).
//
// Run with env vars (never commit real values):
//   SUPABASE_URL=https://<ref>.supabase.co \
//   SUPABASE_SERVICE_KEY=<service_role_key> \
//   SUPABASE_ANON_KEY=<anon_key> \
//   node test-supabase-mirror.mjs
//
// 1. verifies normalizeQuery/corpusRowFor produce well-formed rows
// 2. POSTs two pages of fake results with Prefer: resolution=merge-duplicates
//    (exactly what mirrorSearchResults does) — the second POST re-serves one
//    row to prove idempotent upsert on content_key
// 3. reads them back with the ANON key (the phone's read path, RLS-checked)
// 4. cleans up with the service key
import assert from 'node:assert/strict';
import { normalizeQuery, corpusRowFor, isBlockedMarketplace } from './server.js';

const SUPABASE_URL = (process.env.SUPABASE_URL || '').replace(/\/+$/, '');
const SVC = process.env.SUPABASE_SERVICE_KEY || '';
const ANON = process.env.SUPABASE_ANON_KEY || '';
const REST = `${SUPABASE_URL}/rest/v1`;
assert.ok(SUPABASE_URL && SVC && ANON, 'SUPABASE_URL / SUPABASE_SERVICE_KEY / SUPABASE_ANON_KEY must be set');

// 1. pure helpers
assert.equal(normalizeQuery('  Mountain   Wallpaper 4K '), 'mountain wallpaper 4k');
const row = corpusRowFor('mountain wallpaper 4k', {
  original_url: 'https://example.com/mtn.jpg',
  caption: 'A test mountain',
  source: 'Unsplash',
  original_width: '1920px',
  original_height: 1080,
});
assert.equal(row.query_norm, 'mountain wallpaper 4k');
assert.equal(row.width, 1920); // "1920px" coerces
assert.equal(row.height, 1080); // number passes through
assert.equal(row.source_host, 'Unsplash');
assert.match(row.content_key, /^[0-9a-f]{32}$/);
assert.ok(row.scraped_at.endsWith('Z'));
assert.equal(corpusRowFor('q', { original_url: 'javascript:alert(1)' }), null);
// Marketplace blocklist: these hosts/sources must never be mirrored.
assert.equal(isBlockedMarketplace('https://m.media-amazon.com/images/I/x.jpg', null), true);
assert.equal(isBlockedMarketplace('https://i.ebayimg.com/images/g/x.jpg', 'ebay'), true);
assert.equal(isBlockedMarketplace('https://i.etsystatic.com/il/x.jpg', null), true);
assert.equal(isBlockedMarketplace('https://example.com/a.jpg', 'Amazon'), true); // source label catches z-ai-style rows
assert.equal(corpusRowFor('q', { original_url: 'https://m.media-amazon.com/images/I/x.jpg', source: 'amazon.com' }), null);
assert.equal(isBlockedMarketplace('https://images.pexels.com/photos/x.jpg', 'Pexels'), false);
assert.equal(isBlockedMarketplace('https://upload.wikimedia.org/wikipedia/x.jpg', null), false);
assert.equal(isBlockedMarketplace('https://i.pinimg.com/564x/x.jpg', 'Pinterest'), false);
console.log('marketplace blocklist OK (amazon/etsy/ebay/walmart/... skipped, wikimedia/pexels/pinimg pass)');
console.log('helpers OK:', JSON.stringify(row).slice(0, 120), '...');

// 2. upsert two overlapping pages (mirrors what the bridge does per search)
const mk = (url, caption) => corpusRowFor('__mirror_smoke__', { original_url: url, caption, source: null, original_width: 0, original_height: 0 });
const page1 = [mk('https://example.com/a.jpg', 'a'), mk('https://example.com/b.jpg', 'b')];
const page2 = [mk('https://example.com/b.jpg', 'b-again'), mk('https://example.com/c.jpg', 'c')];
for (const rows of [page1, page2]) {
  const resp = await fetch(`${REST}/search_corpus?on_conflict=content_key`, {
    method: 'POST',
    headers: {
      apikey: SVC,
      authorization: `Bearer ${SVC}`,
      'content-type': 'application/json',
      prefer: 'resolution=merge-duplicates,return=minimal',
    },
    body: JSON.stringify(rows),
    signal: AbortSignal.timeout(15_000),
  });
  // 201 = rows inserted; 200 = rows merged (updated) — both are success.
  assert.ok(
    resp.status === 200 || resp.status === 201,
    `upsert expected 200/201, got ${resp.status}: ${await resp.text()}`,
  );
}
console.log('upserts OK (second POST upserted instead of duplicating)');

// 3. anon read path — exactly what CorpusSearchEngine will do
const read = await fetch(
  `${REST}/search_corpus?select=query_norm,image_url,title&query_norm=eq.__mirror_smoke__&order=scraped_at.desc`,
  { headers: { apikey: ANON, authorization: `Bearer ${ANON}` }, signal: AbortSignal.timeout(15_000) },
);
assert.equal(read.status, 200);
const seen = await read.json();
assert.equal(seen.length, 3, `expected exactly 3 rows after merge-duplicates, got ${seen.length}`);
assert.deepEqual(seen.map((r) => r.image_url).sort(), [
  'https://example.com/a.jpg',
  'https://example.com/b.jpg',
  'https://example.com/c.jpg',
]);
console.log('anon read OK:', JSON.stringify(seen));

// 4. cleanup
const del = await fetch(`${REST}/search_corpus?query_norm=eq.__mirror_smoke__`, {
  method: 'DELETE',
  headers: { apikey: SVC, authorization: `Bearer ${SVC}`, prefer: 'return=minimal' },
  signal: AbortSignal.timeout(15_000),
});
assert.equal(del.status, 204, `delete expected 204, got ${del.status}`);
console.log('cleanup OK');
console.log('\nALL SUPABASE MIRROR SMOKE TESTS PASSED');
