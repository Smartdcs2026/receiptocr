const assert = require('assert');
const fs = require('fs');

const admin = fs.readFileSync('web-admin/ocr-terminal-pos-round10417.js', 'utf8');
const spa = fs.readFileSync('web-admin/admin-spa.js', 'utf8');
const model = fs.readFileSync('android-app/app/src/main/java/com/receiptocr/app/config/ReceiptRuleModels.kt', 'utf8');
const repo = fs.readFileSync('android-app/app/src/main/java/com/receiptocr/app/data/remote/OcrTemplateRepository.kt', 'utf8');
const resolver = fs.readFileSync('android-app/app/src/main/java/com/receiptocr/app/ocr/PosIdentityResolver.kt', 'utf8');
const pipeline = fs.readFileSync('android-app/app/src/main/java/com/receiptocr/app/ocr/RealOcrPipeline.kt', 'utf8');
const gradle = fs.readFileSync('android-app/app/build.gradle.kts', 'utf8');

assert(admin.includes('LAST$/i'), 'Admin must recognize CODE=LAST');
assert(admin.includes('useLastWorkPos:true'), 'Admin must persist terminal POS mapping');
assert(admin.includes('Math.max(...values)'), 'Admin test must resolve LAST using highest store POS');
assert(admin.includes('B01=LAST'), 'Admin help must show B01=LAST example');
assert(spa.includes('ocr-terminal-pos-round10417.js'), 'Admin SPA must load terminal POS helper');
assert(spa.includes('VERSION="104170"'), 'Admin cache version must be 104170');

assert(model.includes('useLastWorkPos: Boolean = false'), 'Android model must support terminal mapping');
assert(model.includes('runtimeLastWorkPos: Int = 0'), 'Android rule must carry runtime store terminal POS');
assert(repo.includes('item.optString("target").equals("LAST"'), 'Android parser must accept target LAST');
assert(resolver.includes('mapping.useLastWorkPos'), 'Resolver must branch for terminal mapping');
assert(resolver.includes('rule.runtimeLastWorkPos'), 'Resolver must use store runtime terminal POS');
assert(pipeline.includes('runtimeLastWorkPos = expectedPosSet.maxOrNull() ?: 0'), 'Pipeline must bind LAST to actual store plan');
assert(gradle.includes('versionCode = 109'), 'Android versionCode must be bumped');
assert(gradle.includes('versionName = "0.104.17"'), 'Android versionName must be 0.104.17');

console.log('Round104.17 terminal POS checks passed');
