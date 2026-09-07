from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"{label} target not found")
    return text.replace(old, new, 1)


core = Path("web-admin/ocr-simple.js")
s = core.read_text(encoding="utf-8")

old_resolver = '''function resolveConfiguredPos(value){
  const numeric=posNumberValue(value);
  const rule=buildReceiptRule().posIdentityRule||{};
  if(!rule.enabled)return numeric;
  const key=normalizePosIdentityKey(value);
  if(!key)return null;
  const prefix=(key.match(/^[A-Z]+/)||[""])[0];
  if(!prefix)return numeric;
  // Explicit mapping is authoritative. A saved B01=LAST / B01=3 must not be
  // rejected just because an older allowed-prefix list has not been refreshed.
  const item=(rule.mappings||[]).find(x=>normalizePosIdentityKey(x.receiptPos)===key);
  if(!item)return null;
  const isLast=item.useLastWorkPos===true||String(item.target||"").toUpperCase()==="LAST";
  if(isLast){
    const values=configuredTestPosValues();
    return values.length?Math.max(...values):null;
  }
  const workPos=Number(item.workPos);
  return Number.isInteger(workPos)&&workPos>0?workPos:null;
}'''

new_resolver = '''function configuredPosMappingItems(){
  const byKey=new Map();
  const saved=brandReceiptRule?.posIdentityRule?.mappings||[];
  saved.forEach(item=>{
    const key=normalizePosIdentityKey(item?.receiptPos);
    if(key)byKey.set(key,item);
  });
  const draft=parseBrandPosMappings($("brandPosMappings")?.value||"");
  draft.forEach(item=>{
    const key=normalizePosIdentityKey(item?.receiptPos);
    if(key)byKey.set(key,item);
  });
  return [...byKey.values()];
}
function resolveConfiguredPos(value){
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

if "function configuredPosMappingItems(){" not in s:
    s = replace_once(s, old_resolver, new_resolver, "direct test resolver")

s = s.replace(
    '$("posIdentityMode").value=p.enabled?"PREFIX_MAPPING":"NORMAL";',
    '$("posIdentityMode").value=(p.enabled||(p.mappings||[]).length)?"PREFIX_MAPPING":"NORMAL";',
    1,
)

old_build_head = '''function buildReceiptRule(){
  const mode=$("dateCountingMode").value;
  return ReceiptDateRules.normalize({'''
new_build_head = '''function buildReceiptRule(){
  const mode=$("dateCountingMode").value;
  const posMappings=parseBrandPosMappings($("brandPosMappings").value);
  return ReceiptDateRules.normalize({'''
if "const posMappings=parseBrandPosMappings" not in s:
    s = replace_once(s, old_build_head, new_build_head, "build rule mapping snapshot")

s = s.replace(
    'enabled:$("posIdentityMode").value==="PREFIX_MAPPING",',
    'enabled:$("posIdentityMode").value==="PREFIX_MAPPING"||posMappings.length>0,',
    1,
)
s = s.replace(
    'mappings:parseBrandPosMappings($("brandPosMappings").value),',
    'mappings:posMappings,',
    1,
)

core.write_text(s, encoding="utf-8")

# Cache generation: force the browser and iframe to load this exact build.
for path in ["web-admin/admin-spa.js", "web-admin/admin.html", "web-admin/index.html", "tests/round10417-pos-terminal.test.js", ".github/workflows/round104-finalize.yml"]:
    p = Path(path)
    t = p.read_text(encoding="utf-8").replace("104174", "104175")
    p.write_text(t, encoding="utf-8")

# Extend the regression with the exact failure mode seen in the Admin tester.
test = Path("tests/round10417-pos-terminal.test.js")
t = test.read_text(encoding="utf-8")
anchor = "assert(core.includes('Math.max(...values)'), 'Admin OCR core must resolve LAST to the highest current store POS');"
extra = "\nassert(core.includes('function configuredPosMappingItems()'), 'Admin tester must read mapping state directly');\nassert(core.includes('brandReceiptRule?.posIdentityRule?.mappings'), 'Admin tester must fall back to the saved mapping');\nassert(core.includes('parseBrandPosMappings($(\"brandPosMappings\")?.value||\"\")'), 'Admin tester must prefer the visible mapping textarea');\nassert(core.includes('enabled:$(\"posIdentityMode\").value===\"PREFIX_MAPPING\"||posMappings.length>0'), 'Mappings must activate POS identity mode even if the dropdown is stale');"
if extra.strip() not in t:
    if anchor not in t:
        raise SystemExit("test anchor not found")
    t = t.replace(anchor, anchor + extra, 1)
t = t.replace("Round104.17.4 mapped-prefix precedence checks passed", "Round104.17.5 direct test mapping checks passed")
test.write_text(t, encoding="utf-8")

print("Round104.17.5 direct test mapping patch applied")
