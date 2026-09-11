const fs=require('fs'),path=require('path'),assert=require('assert');
const R=require('../web-admin/review-customer-rank-round10434.js');
function ok(v,m){assert.ok(v,m)}
let h=R.parseStoreHours('06.00-23.00');assert.equal(h.kind,'RANGE');assert.equal(h.minutesPerDay,1020);
assert.equal(R.openMinutesBetween(Date.UTC(2026,8,10,21,0),Date.UTC(2026,8,11,9,0),h),300,'21:00 -> 09:00 must contain 5 selling hours for 06-23 store');
assert.equal(R.openMinutesBetween(Date.UTC(2026,8,10,21,0),Date.UTC(2026,8,11,9,0),R.parseStoreHours('24 ชั่วโมง')),720,'24h store uses full elapsed time');
assert.equal(R.parseStoreHours('24H').kind,'24H');assert.equal(R.parseStoreHours('00.00-00.00').kind,'24H');
const overnight=R.parseStoreHours('18.00-02.00');assert.equal(overnight.kind,'RANGE');ok(overnight.overnight,'overnight store must be recognized');assert.equal(R.isOpenAt(Date.UTC(2026,8,10,1,0),overnight),true);assert.equal(R.isOpenAt(Date.UTC(2026,8,10,12,0),overnight),false);
const thresholds={'A+':1500,A:1200,'B+':900,B:600,'C+':400,C:0};assert.equal(R.grade(1260,thresholds),'A');assert.equal(R.grade(350,thresholds),'C');
let rank=R.calculate([{pos:1,delta:700},{pos:2,delta:560}],{enabled:true,summaryPoint:'LAST_PLANNED',measure:'TOTAL_INCREASE',thresholds},h,{readyForSummary:true,qualityIssues:[]});assert.equal(rank.label,'A');
rank=R.calculate([{pos:1,delta:700,openMinutes:300}],{enabled:true,measure:'PER_OPEN_HOUR',timeBasis:'OPEN_HOURS',thresholds},R.parseStoreHours(''),{readyForSummary:true,qualityIssues:[]});assert.equal(rank.label,'ติดตามข้อมูล');
rank=R.calculate([{pos:1,delta:700}],{enabled:true,measure:'TOTAL_INCREASE',thresholds},h,{readyForSummary:true,qualityIssues:['บิลผิดร้าน']});assert.equal(rank.label,'ติดตามข้อมูล');
const admin=fs.readFileSync(path.join(__dirname,'../web-admin/brand-calculation-round10434.js'),'utf8');
for(const token of ['ตัวอย่างผลจากค่าที่เลือก','เปิดการจัดระดับ A+ ถึง C','ลูกค้าเฉลี่ยต่อชั่วโมงเปิดขาย/เครื่อง','MONTH_END','ติดตามข้อมูล'])ok(admin.includes(token),`missing Admin feature: ${token}`);
const brands=fs.readFileSync(path.join(__dirname,'../web-admin/brands.html'),'utf8');ok(brands.includes('brand-calculation-round10434.js'),'brands page must use Round104.34 dialog');ok(!brands.includes('brand-calculation-round10433.js'),'old calculation dialog must not be loaded together');
const review=fs.readFileSync(path.join(__dirname,'../web-admin/review.html'),'utf8');ok(review.includes('review-customer-rank-ui-round10434.js'),'review must load rank UI');
console.log('Round104.34 calculation/rank/store-hours tests passed');
