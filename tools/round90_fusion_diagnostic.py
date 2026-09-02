from pathlib import Path

exec(Path("tools/round90_finalize_production_v3.py").read_text(encoding="utf-8"), {})

path = Path("android-app/app/src/main/java/com/receiptocr/app/ocr/PosEvidenceFusion.kt")
s = path.read_text(encoding="utf-8")
anchor = '''    /** Pure helper for unit tests. */
    internal fun fuseTextPasses(
'''
helper = '''    internal fun debugEvidenceForTest(
        rawTexts: List<String>,
        template: UniversalOcrTemplate,
        allowedPos: Set<Int>,
        referenceDate: LocalDate
    ): String {
        val candidates = buildLocalCandidates(rawTexts)
        val evidence = collectTemplateEvidence(template, candidates, allowedPos, referenceDate)
        return buildString {
            append("candidates=").append(candidates.size)
            append("; evidence=").append(evidence.size)
            evidence.forEach { item ->
                append("\\npass=").append(item.passIndex)
                append(" pos=").append(item.pos)
                append(" depth=").append(item.depth)
                append(" fields=").append(item.fields)
            }
        }
    }

'''
if "debugEvidenceForTest" not in s:
    if anchor not in s:
        raise SystemExit("debug helper anchor missing")
    s = s.replace(anchor, helper + anchor, 1)
path.write_text(s, encoding="utf-8")

path = Path("android-app/app/src/test/java/com/receiptocr/app/ocr/PosEvidenceFusionRound90Test.kt")
s = path.read_text(encoding="utf-8")
needle = '''        assertTrue(result.containsKey(2))
        assertEquals("039030", result.getValue(2)["CUSTOMER_VALUE"])
        assertEquals("20/08/2026", result.getValue(2)["BILL_DATE"])
        assertEquals("17:18", result.getValue(2)["BILL_TIME"])
    }
'''
replacement = '''        val debug = PosEvidenceFusion.debugEvidenceForTest(
            rawTexts = listOf(
                "R2020390300400072 20/08769 17:18",
                "R202039030O400072 20/08769 17:18",
                "R202039030V400072 20/0869 17:18"
            ),
            template = mb02,
            allowedPos = setOf(1, 2, 3),
            referenceDate = LocalDate.of(2026, 9, 2)
        )
        java.io.File("/tmp/round90_fusion_debug.txt").writeText("result=$result\\n$debug")
        assertTrue("result=$result", result.containsKey(2))
        assertEquals("039030", result.getValue(2)["CUSTOMER_VALUE"])
        assertEquals("20/08/2026", result.getValue(2)["BILL_DATE"])
        assertEquals("17:18", result.getValue(2)["BILL_TIME"])
    }
'''
# Replace only the LAST occurrence (the new noisy-only test) to avoid touching the earlier test.
pos = s.rfind(needle)
if pos < 0:
    raise SystemExit("target noisy-only assertion block not found")
s = s[:pos] + replacement + s[pos+len(needle):]
path.write_text(s, encoding="utf-8")

print("Round90 focused fusion diagnostic prepared")
