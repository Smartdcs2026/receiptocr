const assert = require('assert');
const fs = require('fs');
const rules = require('../web-admin/receipt-date-rules.js');

const core = fs.readFileSync('web-admin/ocr-simple.js', 'utf8');
const index = fs.readFileSync('web-admin/index.html', 'utf8');
const spa = fs.readFileSync('web-admin/admin-spa.js', 'utf8');
const adminHtml = fs.readFileSync('web-admin/admin.html', 'utf8');
const model = fs.readFileSync('android-app/app/src/main/java/com/receiptocr/app/config/ReceiptRuleModels.kt', 'utf8');
const repo = fs.readFileSync('android-app/app/src/main/java/com/receiptocr/app/data/remote/OcrTemplateRepository.kt', 'utf8');
const resolver = fs.readFileSync('android-app/app/src/main/java/com/receiptocr/app/ocr/PosIdentityResolver.kt', 'utf8');
const pipeline = fs.readFileSync('android-app/app/src/main/java/com/receiptocr/app/ocr/RealOcrPipeline.kt', 'utf8');
const gradle = fs.readFileSync('android-app/app/build.gradle.kts', 'utf8');

const prefixRule = rules.normalizePosIdentityRule({enabled:true,lastWorkPosPrefixes:['B'],mappings:[{receiptPos:'N01',workPos:1}]});
assert.strictEqual(rules.resolvePosIdentity('B01', prefixRule, [1,2,3]), 3, 'B01 must use POS 3 in a 3-POS store');
assert.strictEqual(rules.resolvePosIdentity('B02', prefixRule, [1,2,3,4,5]), 5, 'B02 must use POS 5 in a 5-POS store');
assert.strictEqual(rules.resolvePosIdentity('B99', prefixRule, [1,2,3,4,5]), 5, 'B99 suffix must not become the POS number');
assert.strictEqual(rules.resolvePosIdentity('N01', prefixRule, [1,2,3]), 1, 'N01 exact mapping must remain supported');
assert.strictEqual(rules.resolvePosIdentity('B01', prefixRule, []), null, 'Terminal prefix must not guess without store POS data');

const legacy = rules.normalizePosIdentityRule({enabled:true,mappings:[{receiptPos:'B01',workPos:0,useLastWorkPos:true}]});
assert(legacy.lastWorkPosPrefixes.includes('B'), 'Legacy B01=LAST must migrate to B prefix rule');
assert.strictEqual(rules.resolvePosIdentity('B88', legacy, [1,2,3,4]), 4, 'Legacy LAST migration must apply to every B code');

assert(index.includes('id="brandLastPosPrefixes"'), 'Admin must expose a terminal-prefix field');
assert(index.includes('B01, B02, B99'), 'Admin must explain B-prefix behavior in simple language');
assert(core.includes('lastWorkPosPrefixes'), 'Admin must persist terminal prefixes');
assert(core.includes('ReceiptDateRules.resolvePosIdentity'), 'Admin tester must use the shared resolver');
assert(model.includes('lastWorkPosPrefixes: List<String> = emptyList()'), 'Android model must carry terminal prefixes');
assert(repo.includes('lastWorkPosPrefixes'), 'Android cloud parser must read terminal prefixes');
assert(repo.includes('legacyLastPrefixes'), 'Android parser must migrate legacy exact LAST mappings');
assert(resolver.includes('prefix in terminalPrefixes'), 'Android resolver must apply prefix-wide terminal mapping');
assert(pipeline.includes('runtimeLastWorkPos = expectedPosSet.maxOrNull() ?: 0'), 'Pipeline must bind terminal prefixes to the actual store plan');
assert(index.includes('ocr-simple.js?v=104176'), 'OCR core must use cache generation 104176');
assert(spa.includes('VERSION="104176"'), 'Admin SPA cache version must be 104176');
assert(adminHtml.includes('admin-spa.js?v=104176'), 'Admin shell must force the 104176 SPA asset');
assert(gradle.includes('versionCode = 110'), 'Android versionCode must be Round104.18');
assert(gradle.includes('versionName = "0.104.18"'), 'Android versionName must be 0.104.18');

console.log('Round104.18 terminal POS behavior checks passed');
