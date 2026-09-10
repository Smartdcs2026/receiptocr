const assert = require('assert');
const fs = require('fs');
const rules = require('../web-admin/receipt-date-rules.js');

const rule = rules.normalizePosIdentityRule({
  enabled: true,
  allowedPrefixes: ['N','B'],
  lastWorkPosPrefixes: ['B'],
  mappings: [
    {receiptPos:'N01',workPos:1},
    {receiptPos:'N02',workPos:2},
    {receiptPos:'N03',workPos:3}
  ],
  fallbackUnknownToLastWorkPos: true
});
const pos = [1,2,3,4];
assert.strictEqual(rules.resolvePosIdentity('N01', rule, pos), 1);
assert.strictEqual(rules.resolvePosIdentity('N03', rule, pos), 3);
assert.strictEqual(rules.resolvePosIdentity('B01', rule, pos), 4);
assert.strictEqual(rules.resolvePosIdentity('8', rule, pos), 4);
assert.strictEqual(rules.resolvePosIdentity('X01', rule, pos), 4);
assert.strictEqual(rules.resolvePosIdentity('N04', rule, pos), null);
assert.strictEqual(rules.resolvePosIdentity('8', rule, [1,2,3,4,8]), 8);

const oldRule = {...rule, fallbackUnknownToLastWorkPos:false};
assert.strictEqual(rules.resolvePosIdentity('8', oldRule, pos), 8);
assert.strictEqual(rules.resolvePosIdentity('X01', oldRule, pos), null);

const index = fs.readFileSync('web-admin/index.html','utf8');
const simple = fs.readFileSync('web-admin/ocr-simple.js','utf8');
const model = fs.readFileSync('android-app/app/src/main/java/com/receiptocr/app/config/ReceiptRuleModels.kt','utf8');
const parser = fs.readFileSync('android-app/app/src/main/java/com/receiptocr/app/data/remote/OcrTemplateRepository.kt','utf8');
const resolver = fs.readFileSync('android-app/app/src/main/java/com/receiptocr/app/ocr/PosIdentityResolver.kt','utf8');
const ui = fs.readFileSync('android-app/app/src/main/java/com/receiptocr/app/ui/ReceiptOCRApp.kt','utf8');
assert(index.includes('id="fallbackUnknownToLastWorkPos"'));
assert(simple.includes('fallbackUnknownToLastWorkPos'));
assert(model.includes('fallbackUnknownToLastWorkPos: Boolean = false'));
assert(parser.includes('root.optBoolean("fallbackUnknownToLastWorkPos", false)'));
assert(resolver.includes('prefix !in allowedPrefixes'));
assert(ui.includes('POS("POS")'));
assert(ui.includes('RECEIPTS("รูปบิล")'));
assert(ui.includes('NOTES("หมายเหตุ")'));
assert(!ui.includes('STORE_PHOTOS("ภาพร้าน")'), 'Store photos must not return as a fourth work tab');
assert(ui.includes('SubmissionEditPolicy.isLocked'), 'Submitted work must keep the edit lock policy');
assert(!ui.includes('ดูข้อมูลได้ แต่แก้ไขหรือลบไม่ได้'), 'Verbose submitted-work explanation must remain removed');
assert(ui.includes('DatePickerDialog('));
assert(ui.includes('TimePickerDialog('));
console.log('Round104.20 CJ terminal fallback behavior checks passed');