const assert = require('assert');
const fs = require('fs');

const html = fs.readFileSync('web-admin/review.html', 'utf8');
const css = fs.readFileSync('web-admin/review-fast-round10428.css', 'utf8');
const js = fs.readFileSync('web-admin/review-fast-round10428.js', 'utf8');
const review = fs.readFileSync('web-admin/review.js', 'utf8');
const gradle = fs.readFileSync('android-app/app/build.gradle.kts', 'utf8');

assert(html.includes('review-fast-round10428.css?v=104280'), 'fast-review CSS must be loaded');
assert(html.includes('review-fast-round10428.js?v=104280'), 'fast-review JS must be loaded');
assert(html.includes('data-review-round="104280"'), 'review page cache generation must be 104280');
assert(html.indexOf('review-workspace-v2.js?v=104161') < html.indexOf('review-fast-round10428.js?v=104280'), 'fast-review enhancement must load after workspace v2');

assert(js.includes("root.classList.add('reviewFast10428')"), 'fast-review root class must be enabled');
assert(js.includes("filterToggle.textContent='ตัวกรอง'"), 'advanced filters need a single compact toggle');
assert(js.includes("detail.classList.toggle('rv28ImageToolsOpen')"), 'image tools must be expandable on demand');
assert(js.includes("markReviewPriority"), 'POS issue rows must be prioritized');

assert(css.includes('grid-template-columns:repeat(3,1fr)!important'), 'queue must show three primary status buttons');
assert(css.includes('.rv2StatusRow [data-queue-status=""]{display:none!important}'), 'all-status button must not clutter the primary row');
assert(css.includes('not(.rv28FiltersOpen) .rv2FilterGrid'), 'advanced filters must be collapsed by default');
assert(css.includes('grid-template-columns:minmax(0,3fr) minmax(400px,2fr)!important'), 'detail split must prioritize evidence while keeping review data visible');
assert(css.includes('.reviewAlert.good{display:none!important}'), 'routine ready banner must not consume vertical space');
assert(css.includes('.reviewActions{position:sticky!important;bottom:0!important'), 'decision actions must stay visible at the bottom');
assert(css.includes('.evidenceToolbar [data-tool="left"]'), 'secondary image controls must be hidden until requested');
assert(css.includes('.reviewEvidenceManager{display:none!important}'), 'evidence management must stay out of the normal review path');

assert(review.includes('id="returnSubmission"'), 'return action must remain available');
assert(review.includes('id="approveSubmission"'), 'approve action must remain available');
assert(review.includes('reviewRecordTable'), 'POS review table must remain intact');
assert(review.includes('evidenceToolbar'), 'evidence viewer must remain intact');

assert(gradle.includes('versionCode = 119'), 'Round104.28 web-only change must preserve APK versionCode 119');
assert(gradle.includes('versionName = "0.104.27"'), 'Round104.28 web-only change must preserve APK versionName 0.104.27');

console.log('Round104.28 fast review mode checks passed');
