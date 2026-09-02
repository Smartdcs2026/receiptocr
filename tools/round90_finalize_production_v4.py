from pathlib import Path

# Apply the complete production changes from v3 first.
exec(Path("tools/round90_finalize_production_v3.py").read_text(encoding="utf-8"), {})


def read(path):
    return Path(path).read_text(encoding="utf-8")


def write(path, text):
    Path(path).write_text(text, encoding="utf-8")


def require(condition, message):
    if not condition:
        raise SystemExit(message)


path = "android-app/app/src/main/java/com/receiptocr/app/ocr/PosEvidenceFusion.kt"
s = read(path)
old = '''        // หน้าต่างซ้อนกันใน pass เดียวอาจสร้างหลักฐานซ้ำจำนวนมาก
        // เหลือเฉพาะชุดที่ลึก/ครบที่สุดต่อ pass + POS + ค่า core เดียวกัน
        return results.groupBy { evidence ->
            listOf(
                evidence.passIndex.toString(),
                evidence.template.templateId,
                evidence.pos.toString(),
                evidence.fields["CUSTOMER_VALUE"].orEmpty(),
                evidence.fields["BILL_DATE"].orEmpty(),
                evidence.fields["BILL_TIME"].orEmpty(),
                evidence.fields["STORE_ID"].orEmpty()
            ).joinToString("|")
        }.values.mapNotNull { group -> group.maxByOrNull { it.score } }
    }

    private fun resolvePosCandidate(
'''
new = '''        // OCR candidate หลายแบบใน pass เดียวกันอาจอ่านคนละส่วนของ record ได้ดี
        // เช่น candidate หนึ่งอ่านวันที่ได้ แต่อีก candidate ใน pass เดียวกันอ่านเวลาได้
        // รวมหลักฐานได้เฉพาะ pass + template + POS + ลูกค้าเดียวกันเท่านั้น
        // เพื่อไม่ให้ข้อมูลไหลข้าม POS/ข้ามบิล และถ้าค่า field เดียวกันขัดกันจะไม่เดา
        return mergeEvidenceWithinSamePass(results)
    }

    private fun mergeEvidenceWithinSamePass(results: List<Evidence>): List<Evidence> {
        if (results.isEmpty()) return emptyList()
        return results.groupBy { evidence ->
            listOf(
                evidence.passIndex.toString(),
                evidence.template.templateId,
                evidence.pos.toString(),
                evidence.fields["CUSTOMER_VALUE"].orEmpty()
            ).joinToString("|")
        }.values.mapNotNull { group ->
            val base = group.maxByOrNull { it.score } ?: return@mapNotNull null
            val merged = linkedMapOf<String, String>()
            val fieldTypes = group.flatMap { it.fields.keys }.toSet()

            fieldTypes.forEach { type ->
                val values = group.mapNotNull { evidence ->
                    evidence.fields[type]?.trim()?.takeIf { it.isNotBlank() }
                }.distinct()

                // รับเฉพาะเมื่อทุก candidate ที่อ่าน field นี้ได้ให้ค่าเดียวกัน
                // ถ้ามีหลายค่า ถือว่ายังคลุมเครือและปล่อยให้ consensus ข้าม pass ตัดสินแทน
                if (values.size == 1) merged[type] = values.single()
            }

            base.copy(
                fields = merged,
                depth = group.maxOf { it.depth },
                score = group.maxOf { it.score } +
                    merged.keys.count { it in setOf("CUSTOMER_VALUE", "BILL_DATE", "BILL_TIME", "STORE_ID") } * 10
            )
        }
    }

    private fun resolvePosCandidate(
'''
require(old in s, "same-pass evidence merge anchor not found")
s = s.replace(old, new, 1)
write(path, s)

print("Round90 v4 same-pass POS evidence merge applied")
