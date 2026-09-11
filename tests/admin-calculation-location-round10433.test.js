const assert=require('assert');
const fs=require('fs');

const brandsHtml=fs.readFileSync('web-admin/brands.html','utf8');
const brandCalc=fs.readFileSync('web-admin/brand-calculation-round10433.js','utf8');
const reviewHtml=fs.readFileSync('web-admin/review.html','utf8');
const reviewAdminOnly=fs.readFileSync('web-admin/review-admin-only-round10433.css','utf8');
const model=fs.readFileSync('web-admin/review-customer-trend-model-round10432.js','utf8');

assert.ok(brandsHtml.includes('ร้านและแบรนด์'));
assert.ok(brandsHtml.includes('brand-calculation-round10433.js?v=104330'));
assert.ok(brandsHtml.includes('review-customer-trend-model-round10432.js?v=104321'));
assert.ok(brandCalc.includes("btn.textContent='กติกาคำนวณ'"));
assert.ok(brandCalc.includes("/api/admin/brand-receipt-rules"));
assert.ok(brandCalc.includes('reviewCalculation:r.value'));
assert.ok(brandCalc.includes('rv32Start'));
assert.ok(brandCalc.includes('rv32End'));
assert.ok(brandCalc.includes('rv32Percent'));
assert.ok(brandCalc.includes('rv32Same'));
assert.ok(brandCalc.includes('ยอดลูกค้าเท่ากันในร้านเดียวกันสามารถเกิดขึ้นได้'));

assert.ok(reviewHtml.includes('review-admin-only-round10433.css?v=104330'));
assert.ok(reviewHtml.includes('review-customer-trend-model-round10432.js?v=104321'));
assert.ok(reviewAdminOnly.includes('#rv32RuleBtn'));
assert.ok(reviewAdminOnly.includes('display:none!important'));
assert.ok(model.includes("sameValueHours:1"),'same-POS equal counter default must remain guarded');

console.log('Round104.33 admin calculation settings location guards passed');
