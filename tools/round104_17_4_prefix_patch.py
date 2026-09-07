from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"{label} target not found")
    return text.replace(old, new, 1)


core = Path("web-admin/ocr-simple.js")
s = core.read_text(encoding="utf-8")
s = replace_once(
    s,
    '''  const prefix=(key.match(/^[A-Z]+/)||[""])[0];
  if(!prefix)return numeric;
  const allowed=(rule.allowedPrefixes||[]).map(x=>String(x).toUpperCase());
  if(allowed.length&&!allowed.includes(prefix))return null;
  const item=(rule.mappings||[]).find(x=>normalizePosIdentityKey(x.receiptPos)===key);
  if(!item)return null;''',
    '''  const prefix=(key.match(/^[A-Z]+/)||[""])[0];
  if(!prefix)return numeric;
  // Explicit mapping is authoritative. A saved B01=LAST / B01=3 must not be
  // rejected just because an older allowed-prefix list has not been refreshed.
  const item=(rule.mappings||[]).find(x=>normalizePosIdentityKey(x.receiptPos)===key);
  if(!item)return null;''',
    "Admin resolver",
)
s = replace_once(
    s,
    '''      allowedPrefixes:String($("brandPosPrefixes").value||"").split(/[,;\\s]+/).map(x=>x.trim().toUpperCase()).filter(Boolean),
      mappings:parseBrandPosMappings($("brandPosMappings").value),''',
    '''      allowedPrefixes:[...new Set([
        ...String($("brandPosPrefixes").value||"").split(/[,;\\s]+/).map(x=>x.trim().toUpperCase()).filter(Boolean),
        ...parseBrandPosMappings($("brandPosMappings").value).map(item=>(normalizePosIdentityKey(item.receiptPos)?.match(/^[A-Z]+/)||[""])[0]).filter(Boolean)
      ])],
      mappings:parseBrandPosMappings($("brandPosMappings").value),''',
    "Admin mapped prefix persistence",
)
core.write_text(s, encoding="utf-8")

resolver = Path("android-app/app/src/main/java/com/receiptocr/app/ocr/PosIdentityResolver.kt")
s = resolver.read_text(encoding="utf-8")
s = replace_once(
    s,
    '''        val allowed = rule.allowedPrefixes.map { it.trim().uppercase() }.filter { it.isNotBlank() }.toSet()
        if (allowed.isNotEmpty() && prefix !in allowed) return null

        val mapping = rule.mappings.firstOrNull { item ->
            OcrTextNormalizer.normalizePosIdentity(item.receiptPos) == key &&
                (item.workPos > 0 || item.useLastWorkPos)
        } ?: return null''',
    '''        // Explicit mapping wins over a stale allowedPrefixes list. The mapping itself
        // is the brand-specific authorization for this receipt identity.
        val mapping = rule.mappings.firstOrNull { item ->
            OcrTextNormalizer.normalizePosIdentity(item.receiptPos) == key &&
                (item.workPos > 0 || item.useLastWorkPos)
        } ?: return null''',
    "Android resolver",
)
resolver.write_text(s, encoding="utf-8")

test = Path("android-app/app/src/test/java/com/receiptocr/app/ocr/PosIdentityResolverRound10417Test.kt")
s = test.read_text(encoding="utf-8")
marker = '''    @Test fun last_mapping_does_not_guess_when_store_plan_is_unknown() {'''
addition = '''    @Test fun explicit_mapping_wins_when_allowed_prefix_list_is_stale() {
        val r = PosIdentityRule(
            enabled = true,
            allowedPrefixes = listOf("N"),
            mappings = listOf(PosIdentityMapping("B01", useLastWorkPos = true))
        )
        assertEquals(3, PosIdentityResolver.resolve("B01", r, listOf(1, 2, 3))?.workPos)
    }

'''
if addition not in s:
    if marker not in s:
        raise SystemExit("Android test marker not found")
    s = s.replace(marker, addition + marker, 1)
test.write_text(s, encoding="utf-8")

js = Path("tests/round10417-pos-terminal.test.js")
s = js.read_text(encoding="utf-8")
anchor = "assert(core.includes('return `${receiptPos}=LAST`'), 'Admin OCR core must render saved LAST mappings back to the editor');"
extra = "\nassert(!core.includes('if(allowed.length&&!allowed.includes(prefix))return null'), 'Explicit Admin mapping must not be rejected by a stale prefix list');\nassert(core.includes('normalizePosIdentityKey(item.receiptPos)?.match(/^[A-Z]+/)'), 'Admin must derive allowed prefixes from explicit mappings');\nassert(!resolver.includes('if (allowed.isNotEmpty() && prefix !in allowed) return null'), 'Android explicit mapping must win over a stale prefix list');"
if extra.strip() not in s:
    if anchor not in s:
        raise SystemExit("JS test anchor not found")
    s = s.replace(anchor, anchor + extra, 1)
s = s.replace(
    "Round104.17.3 native terminal POS core checks passed",
    "Round104.17.4 mapped-prefix precedence checks passed",
)
js.write_text(s, encoding="utf-8")

for path in ["web-admin/admin-spa.js", "web-admin/admin.html", "web-admin/index.html"]:
    p = Path(path)
    t = p.read_text(encoding="utf-8").replace("104173", "104174")
    p.write_text(t, encoding="utf-8")

print("Round104.17.4 prefix precedence patch applied")
