from pathlib import Path

GEN_OLD = "104175"
GEN_NEW = "104176"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"{label}: target not found")
    return text.replace(old, new, 1)


# 1) Shared Admin rule core: prefix-wide terminal POS + backward migration from CODE=LAST.
rules = Path("web-admin/receipt-date-rules.js")
rules.write_text(r'''(function(root,factory){
  const api=factory();
  if(typeof module==="object"&&module.exports)module.exports=api;
  root.ReceiptDateRules=api;
})(typeof globalThis!=="undefined"?globalThis:this,function(){
  "use strict";
  const pad=n=>String(n).padStart(2,"0");
  const parseIso=v=>{const m=String(v||"").match(/^(\d{4})-(\d{2})-(\d{2})$/);if(!m)return null;const d=new Date(Date.UTC(+m[1],+m[2]-1,+m[3]));return d.getUTCFullYear()===+m[1]&&d.getUTCMonth()===+m[2]-1&&d.getUTCDate()===+m[3]?d:null};
  const parseThai=v=>{const m=String(v||"").match(/^(\d{2})\/(\d{2})\/(\d{4})$/);return m?parseIso(`${m[3]}-${m[2]}-${m[1]}`):null};
  const iso=d=>`${d.getUTCFullYear()}-${pad(d.getUTCMonth()+1)}-${pad(d.getUTCDate())}`;
  const add=(d,n)=>{const x=new Date(d);x.setUTCDate(x.getUTCDate()+n);return x};
  const days=(a,b)=>Math.round((b-a)/86400000);
  const normalizePosIdentity=value=>{
    const text=String(value||"").toUpperCase().replace(/\s+/g,"").replace(/^POS[:#=\-]?/,"");
    const m=text.match(/^([A-Z]{1,4})?(\d{1,3})$/);
    if(!m)return null;
    const n=Number(m[2]);
    return Number.isInteger(n)&&n>0?`${m[1]||""}${n}`:null;
  };
  const posPrefix=value=>{
    const key=normalizePosIdentity(value);
    return key?(key.match(/^[A-Z]+/)||[""])[0]:"";
  };
  const uniquePrefixes=values=>[...new Set((values||[]).map(x=>String(x||"").trim().toUpperCase()).filter(x=>/^[A-Z]{1,4}$/.test(x)))];
  function normalizePosIdentityRule(raw={}){
    const mappings=(Array.isArray(raw.mappings)?raw.mappings:[]).map(item=>{
      const receiptPos=String(item?.receiptPos||"").trim().toUpperCase();
      const workPos=Math.max(0,Number(item?.workPos)||0);
      const useLastWorkPos=item?.useLastWorkPos===true||String(item?.target||"").toUpperCase()==="LAST";
      return receiptPos&&(workPos>0||useLastWorkPos)?{...item,receiptPos,workPos,useLastWorkPos}:null;
    }).filter(Boolean);
    // Backward compatibility: old B01=LAST becomes a B-prefix terminal rule.
    const legacyLastPrefixes=mappings.filter(x=>x.useLastWorkPos).map(x=>posPrefix(x.receiptPos)).filter(Boolean);
    const lastWorkPosPrefixes=uniquePrefixes([...(raw.lastWorkPosPrefixes||[]),...legacyLastPrefixes]);
    const mappingPrefixes=mappings.map(x=>posPrefix(x.receiptPos)).filter(Boolean);
    const allowedPrefixes=uniquePrefixes([...(raw.allowedPrefixes||[]),...lastWorkPosPrefixes,...mappingPrefixes]);
    return {
      ...raw,
      enabled:raw.enabled===true||mappings.length>0||lastWorkPosPrefixes.length>0,
      allowedPrefixes,
      lastWorkPosPrefixes,
      mappings,
      allowUnmappedUserChoice:raw.allowUnmappedUserChoice!==false,
      runtimeLastWorkPos:Math.max(0,Number(raw.runtimeLastWorkPos)||0)
    };
  }
  function resolvePosIdentity(value,rawRule={},availableWorkPos=[]){
    const key=normalizePosIdentity(value);
    if(!key)return null;
    const numeric=Number((key.match(/(\d+)$/)||[])[1]||0)||null;
    const rule=normalizePosIdentityRule(rawRule);
    const prefix=posPrefix(key);
    const active=rule.enabled||rule.mappings.length>0||rule.lastWorkPosPrefixes.length>0;
    if(!prefix)return numeric;
    if(!active)return numeric;

    // Brand rule has priority: every code beginning with this prefix uses the
    // store's actual last POS; the digits after the letter are not the POS number.
    if(rule.lastWorkPosPrefixes.includes(prefix)){
      const last=(availableWorkPos||[]).map(Number).filter(x=>Number.isInteger(x)&&x>0).sort((a,b)=>b-a)[0]
        ||(rule.runtimeLastWorkPos>0?rule.runtimeLastWorkPos:null);
      return last||null;
    }

    const mapping=rule.mappings.find(x=>normalizePosIdentity(x.receiptPos)===key);
    if(!mapping)return null;
    if(mapping.useLastWorkPos){
      const last=(availableWorkPos||[]).map(Number).filter(x=>Number.isInteger(x)&&x>0).sort((a,b)=>b-a)[0]
        ||(rule.runtimeLastWorkPos>0?rule.runtimeLastWorkPos:null);
      return last||null;
    }
    return mapping.workPos>0?mapping.workPos:null;
  }
  function defaultRule(brandId=""){return{brandId,customerCounterMode:"CONTINUOUS",preventDuplicateImage:true,preventDuplicateReceiptData:true,posIdentityRule:{enabled:false,allowedPrefixes:[],lastWorkPosPrefixes:[],mappings:[],allowUnmappedUserChoice:true},groupDateRule:{enabled:true,resetAtMonthEnd:false,maxBeforeDays:2,afterDaysWhenOldestIsMaxBefore:0,afterDaysWhenOldestIsOneDayBefore:2,afterDaysWhenOldestIsWorkDay:2,action:"BLOCK",warningText:"วันที่บิลไม่อยู่ในช่วงที่ใช้ได้"}}}
  function normalize(raw={},brandId=""){
    const base=defaultRule(brandId),g=raw.groupDateRule||{};
    const number=(v,fallback)=>Number.isFinite(Number(v))?Math.max(0,Math.min(31,Number(v))):fallback;
    return {...base,...raw,brandId:raw.brandId||brandId,customerCounterMode:raw.customerCounterMode||((g.resetAtMonthEnd)?"MONTHLY_RESET":"CONTINUOUS"),posIdentityRule:normalizePosIdentityRule(raw.posIdentityRule||{}),groupDateRule:{...base.groupDateRule,...g,maxBeforeDays:Math.min(2,number(g.maxBeforeDays,2)),afterDaysWhenOldestIsMaxBefore:number(g.afterDaysWhenOldestIsMaxBefore,0),afterDaysWhenOldestIsOneDayBefore:number(g.afterDaysWhenOldestIsOneDayBefore,2),afterDaysWhenOldestIsWorkDay:number(g.afterDaysWhenOldestIsWorkDay,2),action:"BLOCK"}};
  }
  function validate(workDateValue,records,rawRule){
    const work=parseIso(workDateValue),rule=normalize(rawRule).groupDateRule;
    if(!work||!rule.enabled)return{valid:true,issues:[],minDate:null,maxDate:null};
    const parsed=(records||[]).filter(x=>!x.noReceipt&&x.billDate).map(x=>({...x,date:parseThai(x.billDate)}));
    const issues=parsed.filter(x=>!x.date).map(x=>({posNumber:x.posNumber,code:"DATE_FORMAT",message:"วันที่ต้องเป็นรูปแบบ dd/MM/yyyy"}));
    const valid=parsed.filter(x=>x.date);
    if(!valid.length)return{valid:issues.length===0,issues,minDate:null,maxDate:null};
    if(rule.resetAtMonthEnd)valid.filter(x=>x.date.getUTCFullYear()!==work.getUTCFullYear()||x.date.getUTCMonth()!==work.getUTCMonth()).forEach(x=>issues.push({posNumber:x.posNumber,code:"DATE_CROSS_MONTH",message:"แบรนด์นี้ตัดยอดสิ้นเดือน วันที่บิลต้องอยู่เดือนเดียวกับวันทำงาน"}));
    const earliest=new Date(Math.min(...valid.map(x=>x.date.getTime()))),offset=days(earliest,work),absoluteMin=add(work,-rule.maxBeforeDays);
    if(offset<0){valid.forEach(x=>issues.push({posNumber:x.posNumber,code:"DATE_GROUP_NO_WORKDAY",message:"ต้องมีบิลวันที่ทำงานหรือก่อนวันทำงานในช่วงที่กำหนด"}));return{valid:false,issues,minDate:iso(earliest),maxDate:iso(work)}}
    if(offset>rule.maxBeforeDays){valid.filter(x=>x.date<absoluteMin).forEach(x=>issues.push({posNumber:x.posNumber,code:"DATE_TOO_OLD",message:`วันที่บิลย้อนหลังเกิน ${rule.maxBeforeDays} วัน`}));return{valid:false,issues,minDate:iso(absoluteMin),maxDate:iso(work)}}
    const after=offset>=2?rule.afterDaysWhenOldestIsMaxBefore:offset===1?rule.afterDaysWhenOldestIsOneDayBefore:rule.afterDaysWhenOldestIsWorkDay,max=add(work,after);
    valid.filter(x=>x.date<earliest||x.date>max).forEach(x=>issues.push({posNumber:x.posNumber,code:"DATE_OUTSIDE_GROUP",message:`${rule.warningText} (${iso(earliest)} - ${iso(max)})`}));
    return{valid:issues.length===0,issues,minDate:iso(earliest),maxDate:iso(max)};
  }
  return{defaultRule,normalize,validate,normalizePosIdentityRule,resolvePosIdentity};
});
''', encoding="utf-8")


# 2) Admin OCR page behavior.
core = Path("web-admin/ocr-simple.js")
s = core.read_text(encoding="utf-8")
old_resolver = '''function resolveConfiguredPos(value){
  const numeric=posNumberValue(value);
  const key=normalizePosIdentityKey(value);
  if(!key)return null;
  const prefix=(key.match(/^[A-Z]+/)||[""])[0];
  if(!prefix)return numeric;

  // The test must use the mapping the admin can actually see/edit.  Read the
  // current textarea first and fall back to the last rule loaded from storage.
  // This avoids losing B01=LAST through an intermediate normalized rule state.
  const item=configuredPosMappingItems().find(x=>normalizePosIdentityKey(x.receiptPos)===key);
  if(item){
    const isLast=item.useLastWorkPos===true||String(item.target||"").toUpperCase()==="LAST";
    if(isLast){
      const values=configuredTestPosValues();
      return values.length?Math.max(...values):null;
    }
    const workPos=Number(item.workPos);
    return Number.isInteger(workPos)&&workPos>0?workPos:null;
  }

  const rule=buildReceiptRule().posIdentityRule||{};
  return rule.enabled?null:numeric;
}'''
new_resolver = '''function resolveConfiguredPos(value){
  return ReceiptDateRules.resolvePosIdentity(
    value,
    buildReceiptRule().posIdentityRule||{},
    configuredTestPosValues()
  );
}'''
s = replace_once(s, old_resolver, new_resolver, "Admin resolver")

old_render = '''  const p=brandReceiptRule.posIdentityRule||{enabled:false,allowedPrefixes:[],mappings:[],allowUnmappedUserChoice:true};
  $("posIdentityMode").value=(p.enabled||(p.mappings||[]).length)?"PREFIX_MAPPING":"NORMAL";
  $("brandPosPrefixes").value=(p.allowedPrefixes||[]).join(",");
  $("brandPosMappings").value=formatBrandPosMappings(p.mappings);
  $("allowUnmappedPosChoice").checked=p.allowUnmappedUserChoice!==false;
  $("posIdentityRuleExample").textContent=p.enabled
    ?`แยกรหัสเครื่องตามอักษรนำหน้า • ตั้งไว้ ${(p.mappings||[]).length} รายการ`
    :"ค่าเริ่มต้น: ใช้เลข POS แบบเดิม จึงไม่กระทบแบรนด์ที่ใช้งานอยู่";'''
new_render = '''  const p=ReceiptDateRules.normalizePosIdentityRule(brandReceiptRule.posIdentityRule||{});
  const lastPrefixes=p.lastWorkPosPrefixes||[];
  const visibleMappings=(p.mappings||[]).filter(item=>{
    const key=normalizePosIdentityKey(item?.receiptPos);
    const prefix=key?(key.match(/^[A-Z]+/)||[""])[0]:"";
    return !(item?.useLastWorkPos===true&&prefix&&lastPrefixes.includes(prefix));
  });
  $("posIdentityMode").value=(p.enabled||visibleMappings.length||lastPrefixes.length)?"PREFIX_MAPPING":"NORMAL";
  $("brandPosPrefixes").value=(p.allowedPrefixes||[]).join(",");
  $("brandLastPosPrefixes").value=lastPrefixes.join(",");
  $("brandPosMappings").value=formatBrandPosMappings(visibleMappings);
  $("allowUnmappedPosChoice").checked=p.allowUnmappedUserChoice!==false;
  $("posIdentityRuleExample").textContent=(p.enabled||lastPrefixes.length||visibleMappings.length)
    ?`กติกาหมายเลขเครื่อง • POS สุดท้าย: ${lastPrefixes.length?lastPrefixes.join(", "):"ไม่มี"} • จับคู่เฉพาะ ${visibleMappings.length} รายการ`
    :"ค่าเริ่มต้น: ใช้เลข POS แบบเดิม จึงไม่กระทบแบรนด์ที่ใช้งานอยู่";'''
s = replace_once(s, old_render, new_render, "Admin render rule")

old_build = '''  const mode=$("dateCountingMode").value;
  const posMappings=parseBrandPosMappings($("brandPosMappings").value);
  return ReceiptDateRules.normalize({'''
new_build = '''  const mode=$("dateCountingMode").value;
  const posMappings=parseBrandPosMappings($("brandPosMappings").value);
  const lastWorkPosPrefixes=String($("brandLastPosPrefixes")?.value||"")
    .split(/[,;\\s]+/).map(x=>x.trim().toUpperCase()).filter(x=>/^[A-Z]{1,4}$/.test(x));
  return ReceiptDateRules.normalize({'''
s = replace_once(s, old_build, new_build, "Admin build preface")

old_rule = '''    posIdentityRule:{
      enabled:$("posIdentityMode").value==="PREFIX_MAPPING"||posMappings.length>0,
      allowedPrefixes:[...new Set([
        ...String($("brandPosPrefixes").value||"").split(/[,;\\s]+/).map(x=>x.trim().toUpperCase()).filter(Boolean),
        ...parseBrandPosMappings($("brandPosMappings").value).map(item=>(normalizePosIdentityKey(item.receiptPos)?.match(/^[A-Z]+/)||[""])[0]).filter(Boolean)
      ])],
      mappings:posMappings,
      allowUnmappedUserChoice:$("allowUnmappedPosChoice").checked
    },'''
new_rule = '''    posIdentityRule:{
      enabled:$("posIdentityMode").value==="PREFIX_MAPPING"||posMappings.length>0||lastWorkPosPrefixes.length>0,
      allowedPrefixes:[...new Set([
        ...String($("brandPosPrefixes").value||"").split(/[,;\\s]+/).map(x=>x.trim().toUpperCase()).filter(Boolean),
        ...lastWorkPosPrefixes,
        ...parseBrandPosMappings($("brandPosMappings").value).map(item=>(normalizePosIdentityKey(item.receiptPos)?.match(/^[A-Z]+/)||[""])[0]).filter(Boolean)
      ])],
      lastWorkPosPrefixes,
      mappings:posMappings,
      allowUnmappedUserChoice:$("allowUnmappedPosChoice").checked
    },'''
s = replace_once(s, old_rule, new_rule, "Admin POS rule")
core.write_text(s, encoding="utf-8")


# 3) Admin UI: a clear brand-level terminal-prefix field.
index = Path("web-admin/index.html")
s = index.read_text(encoding="utf-8")
old_ui = '''          <label>อักษรนำหน้าที่อนุญาต
            <input id="brandPosPrefixes" placeholder="เช่น N,B">
          </label>
          <label style="grid-column:1/-1">จับคู่รหัสบนบิล → POS ในงาน
            <textarea id="brandPosMappings" rows="4" placeholder="N01=1&#10;N02=2&#10;B01=3"></textarea>
            <span class="small">หนึ่งบรรทัดต่อหนึ่งรหัส เช่น B01=3 หมายถึงบิล B01 ให้ลง POS 3</span>
          </label>'''
new_ui = '''          <label>อักษรนำหน้าที่อนุญาต
            <input id="brandPosPrefixes" placeholder="เช่น N,B">
          </label>
          <label>อักษรนำหน้าที่ให้ใช้ POS สุดท้าย
            <input id="brandLastPosPrefixes" placeholder="เช่น B">
            <span class="small">ใส่ B แล้ว B01, B02, B99 จะใช้ POS สุดท้ายของร้าน โดยไม่ใช้เลขหลัง B เป็นเลข POS</span>
          </label>
          <label style="grid-column:1/-1">จับคู่รหัสเฉพาะบนบิล → POS ในงาน
            <textarea id="brandPosMappings" rows="4" placeholder="N01=1&#10;N02=2"></textarea>
            <span class="small">ใช้เฉพาะรหัสที่ต้องจับคู่เป็นรายตัว ส่วนรหัสที่ขึ้นต้นด้วย B ให้กำหนดในช่อง “POS สุดท้าย” ด้านบน</span>
          </label>'''
s = replace_once(s, old_ui, new_ui, "Admin prefix-last UI")
s = s.replace(f'ocr-simple.js?v={GEN_OLD}', f'ocr-simple.js?v={GEN_NEW}')
index.write_text(s, encoding="utf-8")


# 4) Android config model.
model = Path("android-app/app/src/main/java/com/receiptocr/app/config/ReceiptRuleModels.kt")
s = model.read_text(encoding="utf-8")
old_model = '''data class PosIdentityRule(
    /** ปิดไว้เป็นค่าเริ่มต้นเพื่อรักษาพฤติกรรม Round93 */
    val enabled: Boolean = false,
    /** ตัวอักษรนำหน้าที่แบรนด์นี้อนุญาต เช่น N,B,A */
    val allowedPrefixes: List<String> = emptyList(),
    /** จับคู่ เช่น N01 -> POS 1, B01 -> POS สุดท้ายของร้าน */
    val mappings: List<PosIdentityMapping> = emptyList(),'''
new_model = '''data class PosIdentityRule(
    /** ปิดไว้เป็นค่าเริ่มต้นเพื่อรักษาพฤติกรรม Round93 */
    val enabled: Boolean = false,
    /** ตัวอักษรนำหน้าที่แบรนด์นี้อนุญาต เช่น N,B,A */
    val allowedPrefixes: List<String> = emptyList(),
    /** ถ้ารหัสเครื่องขึ้นต้นด้วยอักษรเหล่านี้ ให้ใช้ POS สุดท้ายของร้าน เช่น B01/B02/B99 -> POS สุดท้าย */
    val lastWorkPosPrefixes: List<String> = emptyList(),
    /** จับคู่รหัสเฉพาะ เช่น N01 -> POS 1 */
    val mappings: List<PosIdentityMapping> = emptyList(),'''
s = replace_once(s, old_model, new_model, "Android PosIdentityRule model")
model.write_text(s, encoding="utf-8")


# 5) Android cloud parser. Legacy exact LAST is promoted to prefix-wide LAST.
repo = Path("android-app/app/src/main/java/com/receiptocr/app/data/remote/OcrTemplateRepository.kt")
s = repo.read_text(encoding="utf-8")
start = s.index('    private fun parsePosIdentityRule(root: JSONObject?): PosIdentityRule {')
end = s.index('\n    private fun parseTemplate(o: JSONObject): UniversalOcrTemplate?', start)
new_parser = '''    private fun parsePosIdentityRule(root: JSONObject?): PosIdentityRule {
        if (root == null) return PosIdentityRule()
        val prefixes = root.optJSONArray("allowedPrefixes")
        val lastPrefixes = root.optJSONArray("lastWorkPosPrefixes")
        val mappingsJson = root.optJSONArray("mappings")
        val parsedMappings = buildList {
            if (mappingsJson != null) {
                for (i in 0 until mappingsJson.length()) {
                    val item = mappingsJson.optJSONObject(i) ?: continue
                    val receiptPos = item.optString("receiptPos").trim().uppercase()
                    val workPos = item.optInt("workPos", 0)
                    val useLastWorkPos = item.optBoolean("useLastWorkPos", false) ||
                        item.optString("target").equals("LAST", ignoreCase = true)
                    if (receiptPos.isNotBlank() && (workPos > 0 || useLastWorkPos)) {
                        add(PosIdentityMapping(receiptPos = receiptPos, workPos = workPos, useLastWorkPos = useLastWorkPos))
                    }
                }
            }
        }
        val explicitLastPrefixes = buildList {
            if (lastPrefixes != null) {
                for (i in 0 until lastPrefixes.length()) {
                    lastPrefixes.optString(i).trim().uppercase().takeIf { it.matches(Regex("^[A-Z]{1,4}$")) }?.let(::add)
                }
            }
        }
        // Older Admin versions stored B01=LAST. Promote its alphabetic prefix so
        // B01, B02, B99 all follow the same brand rule without requiring re-entry.
        val legacyLastPrefixes = parsedMappings.filter { it.useLastWorkPos }
            .map { it.receiptPos.takeWhile(Char::isLetter).uppercase() }
            .filter { it.isNotBlank() }
        val terminalPrefixes = (explicitLastPrefixes + legacyLastPrefixes).distinct()
        val allowed = buildList {
            if (prefixes != null) {
                for (i in 0 until prefixes.length()) {
                    prefixes.optString(i).trim().uppercase().takeIf { it.isNotBlank() }?.let(::add)
                }
            }
            addAll(terminalPrefixes)
            addAll(parsedMappings.map { it.receiptPos.takeWhile(Char::isLetter).uppercase() }.filter { it.isNotBlank() })
        }.distinct()
        return PosIdentityRule(
            enabled = root.optBoolean("enabled", false) || parsedMappings.isNotEmpty() || terminalPrefixes.isNotEmpty(),
            allowedPrefixes = allowed,
            lastWorkPosPrefixes = terminalPrefixes,
            mappings = parsedMappings,
            allowUnmappedUserChoice = root.optBoolean("allowUnmappedUserChoice", true)
        )
    }
'''
s = s[:start] + new_parser + s[end:]
repo.write_text(s, encoding="utf-8")


# 6) Android resolver: prefix rule wins over suffix and exact mappings.
resolver = Path("android-app/app/src/main/java/com/receiptocr/app/ocr/PosIdentityResolver.kt")
s = resolver.read_text(encoding="utf-8")
old_active = '''        if (!rule.enabled) {
            return ResolvedPosIdentity(display, key, numeric, mappedByBrandRule = false)
        }

        val prefix = key.takeWhile(Char::isLetter)
        if (prefix.isBlank()) {
            return ResolvedPosIdentity(display, key, numeric, mappedByBrandRule = false)
        }

        // Explicit mapping wins over a stale allowedPrefixes list. The mapping itself
        // is the brand-specific authorization for this receipt identity.
        val mapping = rule.mappings.firstOrNull { item ->'''
new_active = '''        val active = rule.enabled || rule.mappings.isNotEmpty() || rule.lastWorkPosPrefixes.isNotEmpty()
        if (!active) {
            return ResolvedPosIdentity(display, key, numeric, mappedByBrandRule = false)
        }

        val prefix = key.takeWhile(Char::isLetter)
        if (prefix.isBlank()) {
            return ResolvedPosIdentity(display, key, numeric, mappedByBrandRule = false)
        }

        val terminalPrefixes = rule.lastWorkPosPrefixes.map { it.trim().uppercase() }.filter { it.isNotBlank() }.toSet()
        if (prefix in terminalPrefixes) {
            val lastPos = availableWorkPos.filter { it > 0 }.maxOrNull()
                ?: rule.runtimeLastWorkPos.takeIf { it > 0 }
                ?: return null
            return ResolvedPosIdentity(display, key, lastPos, mappedByBrandRule = true)
        }

        // Exact mapping remains available for prefixes that are not terminal-prefix rules.
        val mapping = rule.mappings.firstOrNull { item ->'''
s = replace_once(s, old_active, new_active, "Android resolver prefix rule")
s = s.replace('        if (!rule.enabled) return emptyList()', '        if (!rule.enabled && rule.mappings.isEmpty() && rule.lastWorkPosPrefixes.isEmpty()) return emptyList()', 1)
resolver.write_text(s, encoding="utf-8")


# 7) Android behavior tests.
test = Path("android-app/app/src/test/java/com/receiptocr/app/ocr/PosIdentityResolverRound10417Test.kt")
test.write_text(r'''package com.receiptocr.app.ocr

import com.receiptocr.app.config.PosIdentityMapping
import com.receiptocr.app.config.PosIdentityRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PosIdentityResolverRound10417Test {
    private fun prefixRule(lastWorkPos: Int = 0) = PosIdentityRule(
        enabled = true,
        allowedPrefixes = listOf("N", "B"),
        lastWorkPosPrefixes = listOf("B"),
        mappings = listOf(
            PosIdentityMapping("N01", workPos = 1),
            PosIdentityMapping("N02", workPos = 2),
            PosIdentityMapping("N03", workPos = 3)
        ),
        runtimeLastWorkPos = lastWorkPos
    )

    @Test fun every_b_code_uses_last_pos_for_three_pos_store() {
        val r = prefixRule()
        assertEquals(1, PosIdentityResolver.resolve("N01", r, listOf(1, 2, 3))?.workPos)
        assertEquals(3, PosIdentityResolver.resolve("B01", r, listOf(1, 2, 3))?.workPos)
        assertEquals(3, PosIdentityResolver.resolve("B02", r, listOf(1, 2, 3))?.workPos)
        assertEquals(3, PosIdentityResolver.resolve("B99", r, listOf(1, 2, 3))?.workPos)
    }

    @Test fun every_b_code_uses_last_pos_for_five_pos_store() {
        val r = prefixRule()
        listOf("B01", "B02", "B09", "B99").forEach { code ->
            assertEquals(5, PosIdentityResolver.resolve(code, r, listOf(1, 2, 3, 4, 5))?.workPos)
        }
    }

    @Test fun terminal_prefix_does_not_use_digits_after_b() {
        val r = prefixRule()
        assertEquals(4, PosIdentityResolver.resolve("B01", r, listOf(1, 2, 3, 4))?.workPos)
        assertEquals(4, PosIdentityResolver.resolve("B02", r, listOf(1, 2, 3, 4))?.workPos)
    }

    @Test fun runtime_last_pos_supports_interpreter_without_explicit_plan_list() {
        val r = prefixRule(lastWorkPos = 5)
        assertEquals(5, PosIdentityResolver.resolve("B88", r)?.workPos)
    }

    @Test fun terminal_prefix_does_not_guess_when_store_plan_is_unknown() {
        assertNull(PosIdentityResolver.resolve("B01", prefixRule()))
    }

    @Test fun fixed_mapping_for_other_prefix_remains_supported() {
        val fixed = PosIdentityRule(
            enabled = true,
            allowedPrefixes = listOf("N", "B"),
            lastWorkPosPrefixes = listOf("B"),
            mappings = listOf(PosIdentityMapping("N01", 1), PosIdentityMapping("N02", 2))
        )
        assertEquals(1, PosIdentityResolver.resolve("N01", fixed, listOf(1, 2, 3))?.workPos)
        assertEquals(2, PosIdentityResolver.resolve("N02", fixed, listOf(1, 2, 3))?.workPos)
        assertEquals(3, PosIdentityResolver.resolve("B01", fixed, listOf(1, 2, 3))?.workPos)
    }

    @Test fun legacy_exact_last_mapping_still_works() {
        val legacy = PosIdentityRule(
            enabled = true,
            mappings = listOf(PosIdentityMapping("B01", useLastWorkPos = true))
        )
        assertEquals(3, PosIdentityResolver.resolve("B01", legacy, listOf(1, 2, 3))?.workPos)
    }
}
''', encoding="utf-8")


# 8) JS regression is now behavioral, not just text-presence.
js_test = Path("tests/round10417-pos-terminal.test.js")
js_test.write_text(r'''const assert = require('assert');
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
assert(gradle.includes('versionCode = 109'), 'Android versionCode remains Round104.17');
assert(gradle.includes('versionName = "0.104.17"'), 'Android versionName remains 0.104.17');

console.log('Round104.17.6 prefix-wide terminal POS behavior checks passed');
''', encoding="utf-8")


# 9) Cache generation.
spa = Path("web-admin/admin-spa.js")
s = spa.read_text(encoding="utf-8").replace(f'VERSION="{GEN_OLD}"', f'VERSION="{GEN_NEW}"')
spa.write_text(s, encoding="utf-8")

admin = Path("web-admin/admin.html")
s = admin.read_text(encoding="utf-8")
s = s.replace(f'admin-auth.js?v={GEN_OLD}', f'admin-auth.js?v={GEN_NEW}')
s = s.replace(f'admin-spa.js?v={GEN_OLD}', f'admin-spa.js?v={GEN_NEW}')
s = s.replace('<!-- Round104.17.4 mapped POS precedence deployment -->', '<!-- Round104.17.6 B-prefix terminal POS deployment -->')
admin.write_text(s, encoding="utf-8")

print("Round104.17.6 prefix-wide terminal POS patch applied")
