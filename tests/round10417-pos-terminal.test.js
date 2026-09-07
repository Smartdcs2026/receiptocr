const assert = require('assert');
const fs = require('fs');

const core = fs.readFileSync('web-admin/ocr-simple.js', 'utf8');
const index = fs.readFileSync('web-admin/index.html', 'utf8');
const spa = fs.readFileSync('web-admin/admin-spa.js', 'utf8');
const adminHtml = fs.readFileSync('web-admin/admin.html', 'utf8');
const model = fs.readFileSync('android-app/app/src/main/java/com/receiptocr/app/config/ReceiptRuleModels.kt', 'utf8');
const repo = fs.readFileSync('android-app/app/src/main/java/com/receiptocr/app/data/remote/OcrTemplateRepository.kt', 'utf8');
const resolver = fs.readFileSync('android-app/app/src/main/java/com/receiptocr/app/ocr/PosIdentityResolver.kt', 'utf8');
const pipeline = fs.readFileSync('android-app/app/src/main/java/com/receiptocr/app/ocr/RealOcrPipeline.kt', 'utf8');
const gradle = fs.readFileSync('android-app/app/build.gradle.kts', 'utf8');

assert(core.includes('\\d+|LAST'), 'Admin OCR core parser must accept CODE=LAST');
assert(core.includes('useLastWorkPos:true'), 'Admin OCR core must persist terminal POS mapping');
assert(core.includes('target:"LAST"'), 'Admin OCR core must preserve LAST target semantics');
assert(core.includes('configuredTestPosValues'), 'Admin OCR core must read the current store POS list');
assert(core.includes('Math.max(...values)'), 'Admin OCR core must resolve LAST to the highest current store POS');
assert(core.includes('function configuredPosMappingItems()'), 'Admin tester must read mapping state directly');
assert(core.includes('brandReceiptRule?.posIdentityRule?.mappings'), 'Admin tester must fall back to the saved mapping');
assert(core.includes('parseBrandPosMappings($("brandPosMappings")?.value||"")'), 'Admin tester must prefer the visible mapping textarea');
assert(core.includes('enabled:$("posIdentityMode").value==="PREFIX_MAPPING"||posMappings.length>0'), 'Mappings must activate POS identity mode even if the dropdown is stale');
assert(core.includes('return `${receiptPos}=LAST`'), 'Admin OCR core must render saved LAST mappings back to the editor');
assert(!core.includes('if(allowed.length&&!allowed.includes(prefix))return null'), 'Explicit Admin mapping must not be rejected by a stale prefix list');
assert(core.includes('normalizePosIdentityKey(item.receiptPos)?.match(/^[A-Z]+/)'), 'Admin must derive allowed prefixes from explicit mappings');
assert(!resolver.includes('if (allowed.isNotEmpty() && prefix !in allowed) return null'), 'Android explicit mapping must win over a stale prefix list');
assert(index.includes('ocr-simple.js?v=104175'), 'OCR core must use cache generation 104175');
assert(spa.includes('VERSION="104175"'), 'Admin SPA cache version must be 104175');
assert(!spa.includes('round10417TerminalPos'), 'Admin SPA must no longer depend on the legacy terminal helper');
assert(adminHtml.includes('admin-spa.js?v=104175'), 'Admin shell must force the 104175 SPA asset');
assert(adminHtml.includes('admin-auth.js?v=104175'), 'Admin shell auth asset must share the new cache generation');

assert(model.includes('useLastWorkPos: Boolean = false'), 'Android model must support terminal mapping');
assert(model.includes('runtimeLastWorkPos: Int = 0'), 'Android rule must carry runtime store terminal POS');
assert(repo.includes('item.optString("target").equals("LAST"'), 'Android parser must accept target LAST');
assert(resolver.includes('mapping.useLastWorkPos'), 'Resolver must branch for terminal mapping');
assert(resolver.includes('rule.runtimeLastWorkPos'), 'Resolver must use store runtime terminal POS');
assert(pipeline.includes('runtimeLastWorkPos = expectedPosSet.maxOrNull() ?: 0'), 'Pipeline must bind LAST to actual store plan');
assert(gradle.includes('versionCode = 109'), 'Android versionCode remains Round104.17');
assert(gradle.includes('versionName = "0.104.17"'), 'Android versionName remains 0.104.17');

console.log('Round104.17.5 direct test mapping checks passed');
