from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

def read(path):
    return (ROOT / path).read_text(encoding='utf-8')

def write(path, text):
    (ROOT / path).write_text(text, encoding='utf-8')

def rep(path, old, new, count=1):
    text = read(path)
    actual = text.count(old)
    if actual < count:
        raise SystemExit(f'{path}: expected at least {count} occurrence(s), found {actual}: {old[:120]!r}')
    text = text.replace(old, new, count)
    write(path, text)

# 1) Android model carries Admin date convention per BILL_DATE field.
rep('android-app/app/src/main/java/com/receiptocr/app/config/OcrTemplateModels.kt',
'''    val format: String = "ANY",
    val literal: String? = null,''',
'''    val format: String = "ANY",
    /** ลำดับวันที่บนบิล: DMY / MDY / YMD */
    val dateOrder: String = "DMY",
    /** ระบบปีบนบิล: AUTO / GREGORIAN / BUDDHIST */
    val dateCalendar: String = "AUTO",
    /** 0 = รับ 2/4 หลัก, หรือกำหนด 2 / 4 */
    val dateYearDigits: Int = 0,
    val literal: String? = null,''')

# 2) Android contract validates new optional date conventions while keeping schema v4 compatible.
rep('android-app/app/src/main/java/com/receiptocr/app/config/OcrTemplateContract.kt',
'''    val COUNTER_CYCLES = setOf("CONTINUOUS", "DAILY", "MONTHLY", "YEARLY")
''',
'''    val COUNTER_CYCLES = setOf("CONTINUOUS", "DAILY", "MONTHLY", "YEARLY")
    val DATE_ORDERS = setOf("DMY", "MDY", "YMD")
    val DATE_CALENDARS = setOf("AUTO", "GREGORIAN", "BUDDHIST")
    val DATE_YEAR_DIGITS = setOf(0, 2, 4)
''')
rep('android-app/app/src/main/java/com/receiptocr/app/config/OcrTemplateContract.kt',
'''                if (field.compareTo.uppercase() !in COMPARE_TARGETS) errors += "compare:${field.type}"
                if (field.type == "POS_NUMBER") {''',
'''                if (field.compareTo.uppercase() !in COMPARE_TARGETS) errors += "compare:${field.type}"
                if (field.type == "BILL_DATE") {
                    if (field.dateOrder.uppercase() !in DATE_ORDERS) errors += "dateOrder"
                    if (field.dateCalendar.uppercase() !in DATE_CALENDARS) errors += "dateCalendar"
                    if (field.dateYearDigits !in DATE_YEAR_DIGITS) errors += "dateYearDigits"
                }
                if (field.type == "POS_NUMBER") {''')

# 3) API parser keeps date convention fields.
rep('android-app/app/src/main/java/com/receiptocr/app/data/remote/OcrTemplateRepository.kt',
'''            format = f.optString("format", "ANY"),
            literal = f.optString("literal").takeIf { it.isNotBlank() && it != "null" },''',
'''            format = f.optString("format", "ANY"),
            dateOrder = f.optString("dateOrder", "DMY").uppercase().ifBlank { "DMY" },
            dateCalendar = f.optString("dateCalendar", "AUTO").uppercase().ifBlank { "AUTO" },
            dateYearDigits = f.optInt("dateYearDigits", 0).takeIf { it in setOf(0, 2, 4) } ?: 0,
            literal = f.optString("literal").takeIf { it.isNotBlank() && it != "null" },''')

# 4) Admin date controls in field editor.
rep('web-admin/index.html',
'''      <div id="yearMonthBox" class="hidden fieldSpecialBox">
        <label>ต้องตรวจให้ตรงกับ
          <select id="fieldCompareTo">
            <option value="NONE">ไม่ตรวจเพิ่ม</option>
            <option value="BILL_DATE">วันที่ในบิล</option>
            <option value="WORK_DATE">วันที่ทำงาน</option>
          </select>
        </label>
      </div>

      <div id="posBox" class="hidden fieldSpecialBox">''',
'''      <div id="yearMonthBox" class="hidden fieldSpecialBox">
        <label>ต้องตรวจให้ตรงกับ
          <select id="fieldCompareTo">
            <option value="NONE">ไม่ตรวจเพิ่ม</option>
            <option value="BILL_DATE">วันที่ในบิล</option>
            <option value="WORK_DATE">วันที่ทำงาน</option>
          </select>
        </label>
      </div>

      <div id="dateBox" class="hidden fieldSpecialBox">
        <div class="subSectionTitle">เงื่อนไขวันที่บนบิล</div>
        <div class="simpleFormGrid two">
          <label>ลำดับวัน เดือน ปี
            <select id="dateOrder">
              <option value="DMY">วัน / เดือน / ปี</option>
              <option value="MDY">เดือน / วัน / ปี</option>
              <option value="YMD">ปี / เดือน / วัน</option>
            </select>
          </label>
          <label>ระบบปีบนบิล
            <select id="dateCalendar">
              <option value="AUTO">รับทั้ง พ.ศ. และ ค.ศ.</option>
              <option value="BUDDHIST">พ.ศ. เท่านั้น</option>
              <option value="GREGORIAN">ค.ศ. เท่านั้น</option>
            </select>
          </label>
          <label>จำนวนหลักของปี
            <select id="dateYearDigits">
              <option value="0">รับทั้ง 2 และ 4 หลัก</option>
              <option value="2">2 หลักเท่านั้น</option>
              <option value="4">4 หลักเท่านั้น</option>
            </select>
          </label>
        </div>
        <div class="small">ตัวอย่าง: พ.ศ. 2569 / 69 และ ค.ศ. 2026 / 26 • เมื่ออ่านผ่าน ระบบจะเก็บเป็น dd/MM/yyyy เสมอ</div>
      </div>

      <div id="timeBox" class="hidden fieldSpecialBox">
        <div class="small">เวลาที่อ่านผ่านจะเก็บเป็น HH:mm เสมอ เช่น 7:05 หรือ 07:05:33 จะเก็บเป็น 07:05</div>
      </div>

      <div id="posBox" class="hidden fieldSpecialBox">''')
rep('web-admin/index.html','<script src="ocr-simple.js?v=88"></script>','<script src="ocr-simple.js?v=89"></script>')

# 5) Admin field state/load/save includes date convention.
rep('web-admin/ocr-simple.js',
'''return {id:crypto.randomUUID(),type,example:"",minLength:m.min,maxLength:m.max,format:m.format,required:type!=="IGNORE",literal:"",prefix:"",separator:"",segments:[],compareTo:"NONE",posPrefixes:"",posDigits:2,separatorValue:""};''',
'''return {id:crypto.randomUUID(),type,example:"",minLength:m.min,maxLength:m.max,format:m.format,required:type!=="IGNORE",literal:"",prefix:"",separator:"",segments:[],compareTo:"NONE",posPrefixes:"",posDigits:2,separatorValue:"",dateOrder:"DMY",dateCalendar:"AUTO",dateYearDigits:0};''')
rep('web-admin/ocr-simple.js',
'''compareTo:f.compareTo||"NONE",posPrefixes:f.posPrefixes||"",posDigits:f.posDigits||2,separatorValue:f.separatorValue||""''',
'''compareTo:f.compareTo||"NONE",posPrefixes:f.posPrefixes||"",posDigits:f.posDigits||2,separatorValue:f.separatorValue||"",dateOrder:f.dateOrder||"DMY",dateCalendar:f.dateCalendar||"AUTO",dateYearDigits:Number(f.dateYearDigits||0)''')
rep('web-admin/ocr-simple.js',
'''  $("fieldCompareTo").value=f.compareTo||"NONE";
  $("posBox").classList.toggle("hidden",f.type!=="POS_NUMBER");''',
'''  $("fieldCompareTo").value=f.compareTo||"NONE";
  $("dateBox").classList.toggle("hidden",f.type!=="BILL_DATE");
  $("timeBox").classList.toggle("hidden",f.type!=="BILL_TIME");
  $("dateOrder").value=f.dateOrder||"DMY";
  $("dateCalendar").value=f.dateCalendar||"AUTO";
  $("dateYearDigits").value=String(Number(f.dateYearDigits||0));
  $("posBox").classList.toggle("hidden",f.type!=="POS_NUMBER");''')
rep('web-admin/ocr-simple.js',
'''f.separatorValue=$("separatorValue").value;
  if(f.type==="COMPOSITE_CODE")''',
'''f.separatorValue=$("separatorValue").value;f.dateOrder=$("dateOrder").value;f.dateCalendar=$("dateCalendar").value;f.dateYearDigits=+$("dateYearDigits").value||0;
  if(f.type==="COMPOSITE_CODE")''')
rep('web-admin/ocr-simple.js',
'''["fieldExample","fieldFormat","fieldMinLength","fieldMaxLength","fieldRequired","fieldLiteral","fieldCompareTo","posPrefixes","posDigits","separatorValue","compositePrefix","compositeSeparator"]''',
'''["fieldExample","fieldFormat","fieldMinLength","fieldMaxLength","fieldRequired","fieldLiteral","fieldCompareTo","dateOrder","dateCalendar","dateYearDigits","posPrefixes","posDigits","separatorValue","compositePrefix","compositeSeparator"]''')
rep('web-admin/ocr-simple.js',
'''format:f.format,literal:f.literal||null,compareTo:f.compareTo||"NONE",posPrefixes:f.posPrefixes||null''',
'''format:f.format,dateOrder:f.dateOrder||"DMY",dateCalendar:f.dateCalendar||"AUTO",dateYearDigits:Number(f.dateYearDigits||0),literal:f.literal||null,compareTo:f.compareTo||"NONE",posPrefixes:f.posPrefixes||null''')

# Add Admin-side date/time normalization and rule warning before existing validateParsedRecord.
marker = 'function validateParsedRecord(fields,configuredRows,recordNumber){\n'
text = read('web-admin/ocr-simple.js')
if marker not in text:
    raise SystemExit('validateParsedRecord marker missing')
helper = r'''function normalizeTestTime(raw){
  const m=String(raw||"").trim().replace(/\./g,":").match(/^(\d{1,2}):(\d{2})(?::(\d{2}))?$/);
  if(!m)return {value:null,warning:"รูปแบบเวลาไม่ถูกต้อง"};
  const h=Number(m[1]),mi=Number(m[2]),s=m[3]===undefined?0:Number(m[3]);
  if(h>23||mi>59||s>59)return {value:null,warning:"เวลาไม่มีอยู่จริง"};
  return {value:`${String(h).padStart(2,"0")}:${String(mi).padStart(2,"0")}`,warning:""};
}
function normalizeTestDate(raw,field,workDateRaw){
  const cleaned=String(raw||"").trim().replace(/[.\-]/g,"/");
  const parts=cleaned.split("/").map(x=>x.trim());
  if(parts.length!==3)return {value:null,warning:"รูปแบบวันที่ไม่ตรงเงื่อนไข"};
  const order=String(field?.dateOrder||"DMY").toUpperCase();
  let d,m,yToken;
  if(order==="MDY"){m=parts[0];d=parts[1];yToken=parts[2]}
  else if(order==="YMD"){yToken=parts[0];m=parts[1];d=parts[2]}
  else {d=parts[0];m=parts[1];yToken=parts[2]}
  const yearDigits=Number(field?.dateYearDigits||0);
  const yd=String(yToken).replace(/\D/g,"");
  if(yearDigits&&yd.length!==yearDigits)return {value:null,warning:`ปีบนบิลต้องมี ${yearDigits} หลัก`};
  const day=Number(d),month=Number(m),rawYear=Number(yd);
  if(!Number.isInteger(day)||!Number.isInteger(month)||!Number.isInteger(rawYear))return {value:null,warning:"วันที่มีตัวเลขไม่ครบ"};
  const calendar=String(field?.dateCalendar||"AUTO").toUpperCase();
  const ref=workDateRaw?new Date(`${workDateRaw}T00:00:00`):new Date();
  const refYear=ref.getFullYear();
  let years=[];
  if(calendar==="BUDDHIST"){
    if(yd.length===4&&rawYear>=2400&&rawYear<=2999)years=[rawYear-543];
    else if(yd.length===2)years=[2500+rawYear-543];
  }else if(calendar==="GREGORIAN"){
    if(yd.length===4&&rawYear>=1900&&rawYear<=2200)years=[rawYear];
    else if(yd.length===2)years=[2000+rawYear,1900+rawYear];
  }else{
    if(yd.length===4){
      if(rawYear>=2400&&rawYear<=2999)years=[rawYear-543];
      else if(rawYear>=1900&&rawYear<=2200)years=[rawYear];
    }else if(yd.length===2)years=[2000+rawYear,1900+rawYear,2500+rawYear-543];
  }
  years=[...new Set(years)].filter(y=>y>=1900&&y<=2200).sort((a,b)=>Math.abs(a-refYear)-Math.abs(b-refYear));
  if(!years.length)return {value:null,warning:`ระบบปีบนบิลไม่ตรงเงื่อนไข (${calendar==="BUDDHIST"?"พ.ศ.":calendar==="GREGORIAN"?"ค.ศ.":"พ.ศ./ค.ศ."})`};
  for(const year of years){
    const dt=new Date(year,month-1,day);
    if(dt.getFullYear()===year&&dt.getMonth()===month-1&&dt.getDate()===day){
      return {value:`${String(day).padStart(2,"0")}/${String(month).padStart(2,"0")}/${year}`,warning:""};
    }
  }
  return {value:null,warning:"วันที่ไม่มีอยู่จริงตามปฏิทิน"};
}
'''
text = text.replace(marker, helper + marker, 1)
write('web-admin/ocr-simple.js', text)

# Inject date/time rule checks and normalized display at start of validateParsedRecord.
rep('web-admin/ocr-simple.js',
'''function validateParsedRecord(fields,configuredRows,recordNumber){
  const checks=[];
  let validationPassed=true;
  const label=`ชุดที่ ${recordNumber}`;''',
'''function validateParsedRecord(fields,configuredRows,recordNumber){
  fields={...fields};
  const checks=[];
  let validationPassed=true;
  const label=`ชุดที่ ${recordNumber}`;
  const dateField=configuredRows.flat().find(field=>field.type==="BILL_DATE");
  if(fields.BILL_DATE&&dateField){
    const normalized=normalizeTestDate(fields.BILL_DATE,dateField,$("testWorkDate").value);
    if(normalized.value){
      fields.BILL_DATE=normalized.value;
      checks.push({ok:true,text:`${label}: วันที่ตรงรูปแบบที่กำหนด (${normalized.value})`});
    }else{
      checks.push({ok:false,text:`${label}: ${normalized.warning} • อ่านได้ ${fields.BILL_DATE}`});
      validationPassed=false;
    }
  }
  if(fields.BILL_TIME){
    const normalized=normalizeTestTime(fields.BILL_TIME);
    if(normalized.value){fields.BILL_TIME=normalized.value;checks.push({ok:true,text:`${label}: เวลาใช้รูปแบบมาตรฐาน (${normalized.value})`})}
    else {checks.push({ok:false,text:`${label}: ${normalized.warning} • อ่านได้ ${fields.BILL_TIME}`});validationPassed=false}
  }''')

# 6) Web contract validates date settings.
rep('web-admin/ocr-template-contract.js',
'''  const COUNTER_CYCLES=["CONTINUOUS","DAILY","MONTHLY","YEARLY"];
''',
'''  const COUNTER_CYCLES=["CONTINUOUS","DAILY","MONTHLY","YEARLY"];
  const DATE_ORDERS=["DMY","MDY","YMD"];
  const DATE_CALENDARS=["AUTO","GREGORIAN","BUDDHIST"];
  const DATE_YEAR_DIGITS=[0,2,4];
''')
rep('web-admin/ocr-template-contract.js',
'''        if(!COMPARE_TARGETS.includes(String(field.compareTo||"NONE").toUpperCase()))errors.push(`${location}: เงื่อนไขเปรียบเทียบไม่รองรับ`);
        if(field.type==="POS_NUMBER"){''',
'''        if(!COMPARE_TARGETS.includes(String(field.compareTo||"NONE").toUpperCase()))errors.push(`${location}: เงื่อนไขเปรียบเทียบไม่รองรับ`);
        if(field.type==="BILL_DATE"){
          if(!DATE_ORDERS.includes(String(field.dateOrder||"DMY").toUpperCase()))errors.push(`${location}: ลำดับวันที่ไม่รองรับ`);
          if(!DATE_CALENDARS.includes(String(field.dateCalendar||"AUTO").toUpperCase()))errors.push(`${location}: ระบบปีไม่รองรับ`);
          if(!DATE_YEAR_DIGITS.includes(Number(field.dateYearDigits||0)))errors.push(`${location}: จำนวนหลักปีต้องเป็น 2 หรือ 4 หลัก`);
        }
        if(field.type==="POS_NUMBER"){''')
rep('web-admin/ocr-template-contract.js',
'''  return {SCHEMA_VERSION,MAX_ROWS,FIELD_TYPES,SEGMENT_TYPES,COMPARE_TARGETS,COUNTER_CYCLES,validate};''',
'''  return {SCHEMA_VERSION,MAX_ROWS,FIELD_TYPES,SEGMENT_TYPES,COMPARE_TARGETS,COUNTER_CYCLES,DATE_ORDERS,DATE_CALENDARS,DATE_YEAR_DIGITS,validate};''')

# 7) Universal strict interpreter must not convert 69 -> 2069 before central normalizer.
rep('android-app/app/src/main/java/com/receiptocr/app/ocr/UniversalTemplateInterpreter.kt',
'''            val dateRaw = match.fields["BILL_DATE"]
            val normalizedDate = dateRaw?.let { normalizeDate(it, workDate) }
            val time = match.fields["BILL_TIME"]?.replace('.', ':')''',
'''            val dateRaw = match.fields["BILL_DATE"]
            val dateField = match.template.recognition.rows.asSequence()
                .flatMap { it.fields.asSequence() }
                .firstOrNull { it.type == "BILL_DATE" }
            val dateResult = dateRaw?.let {
                ReceiptDateOcrNormalizer.normalize(
                    raw = it,
                    configuredFormat = dateField?.format,
                    referenceDate = workDate,
                    dateOrder = dateField?.dateOrder,
                    dateCalendar = dateField?.dateCalendar,
                    dateYearDigits = dateField?.dateYearDigits ?: 0
                )
            }
            val normalizedDate = dateResult?.value
            val time = match.fields["BILL_TIME"]?.let(ReceiptTimeOcrNormalizer::normalize)?.value''')
rep('android-app/app/src/main/java/com/receiptocr/app/ocr/UniversalTemplateInterpreter.kt',
'''                source = "OCR-TEMPLATE",
                ocrSourceImagePath = imagePath,
                ocrWarnings = posWarnings.distinct().joinToString(" • "),''',
'''                source = "OCR-TEMPLATE",
                ocrSourceImagePath = imagePath,
                ocrTemplateName = match.template.templateName,
                ocrWarnings = posWarnings.distinct().joinToString(" • "),''')
# Remove generic invalid-date warning now central result carries specific rule warning.
rep('android-app/app/src/main/java/com/receiptocr/app/ocr/UniversalTemplateInterpreter.kt',
'''            if (dateRaw != null && normalizedDate == null) posWarnings += "วันที่ที่อ่านได้มีรูปแบบไม่ถูกต้อง ($dateRaw)"
            posWarnings += comparisonWarnings''',
'''            if (dateRaw != null && normalizedDate == null) {
                posWarnings += dateResult?.warning ?: "วันที่ที่อ่านได้ไม่ตรงเงื่อนไขที่กำหนด ($dateRaw)"
            }
            posWarnings += comparisonWarnings''')

# 8) Sequence parser tags template and standardizes time; date remains raw for central rule handling.
rep('android-app/app/src/main/java/com/receiptocr/app/ocr/TemplateSequenceFallback.kt',
'''            val time = candidate.fields["BILL_TIME"].orEmpty().replace('.', ':')''',
'''            val time = ReceiptTimeOcrNormalizer.normalize(candidate.fields["BILL_TIME"].orEmpty()).value.orEmpty()''')
rep('android-app/app/src/main/java/com/receiptocr/app/ocr/TemplateSequenceFallback.kt',
'''                source = "OCR-SEQUENCE",
                ocrSourceImagePath = imagePath,
                ocrWarnings = "",''',
'''                source = "OCR-SEQUENCE",
                ocrSourceImagePath = imagePath,
                ocrTemplateName = candidate.template.templateName,
                ocrWarnings = "",''')

# 9) Evidence fusion standardizes time and preserves template tag (already tags name).
rep('android-app/app/src/main/java/com/receiptocr/app/ocr/PosEvidenceFusion.kt',
'''            val time = resolved.values["BILL_TIME"]''',
'''            val time = resolved.values["BILL_TIME"]?.let { resolvedTime ->
                ReceiptTimeOcrNormalizer.normalize(resolvedTime.value).value?.let {
                    resolvedTime.copy(value = it)
                }
            }''')

# 10) Real pipeline uses date rule from the template that produced each POS, keeps raw value visible on mismatch,
# and keeps normalized HH:mm for storage.
rep('android-app/app/src/main/java/com/receiptocr/app/ocr/RealOcrPipeline.kt',
'''        val dateFormat = configuredDateFormat(templates)
''',
'''        val defaultDateField = configuredDateField(templates)
''')
rep('android-app/app/src/main/java/com/receiptocr/app/ocr/RealOcrPipeline.kt',
'''            val dateResult = if (currentImagePos && rawCandidateDate.isNotBlank()) {
                ReceiptDateOcrNormalizer.normalize(
                    raw = rawCandidateDate,
                    configuredFormat = dateFormat,
                    referenceDate = workDate
                )
            } else null''',
'''            val recordDateField = dateFieldForRecord(record, templates) ?: defaultDateField
            val dateResult = if (currentImagePos && rawCandidateDate.isNotBlank()) {
                ReceiptDateOcrNormalizer.normalize(
                    raw = rawCandidateDate,
                    configuredFormat = recordDateField?.format,
                    referenceDate = workDate,
                    dateOrder = recordDateField?.dateOrder,
                    dateCalendar = recordDateField?.dateCalendar,
                    dateYearDigits = recordDateField?.dateYearDigits ?: 0
                )
            } else null''')
rep('android-app/app/src/main/java/com/receiptocr/app/ocr/RealOcrPipeline.kt',
'''            val safeExistingDate = if (!original.source.startsWith("OCR", ignoreCase = true)) original.billDate else ""
            val acceptedDate = when {
                dateResult?.value != null -> dateResult.value
                currentImagePos -> safeExistingDate
                else -> record.billDate
            }
            val dateWarning = when {
                !currentImagePos || rawCandidateDate.isBlank() -> ""
                dateResult?.value == null ->
                    "วันที่ที่อ่านจากภาพ ($rawCandidateDate) ห่างจากวันงานมากผิดปกติหรือไม่ใช่วันที่จริง จึงยังไม่นำมาใช้"
                dateResult.corrected ->
                    "วันที่ที่อ่านจากภาพ ${dateResult.original} ถูกปรับเป็น ${dateResult.value} ตามตำแหน่งวัน/เดือน กรุณาตรวจเทียบกับภาพ"
                else -> ""
            }''',
'''            val safeExistingDate = if (!original.source.startsWith("OCR", ignoreCase = true)) original.billDate else ""
            val acceptedDate = when {
                dateResult?.value != null -> dateResult.value
                currentImagePos && rawCandidateDate.isNotBlank() -> rawCandidateDate
                currentImagePos -> safeExistingDate
                else -> record.billDate
            }
            val dateWarning = when {
                !currentImagePos || rawCandidateDate.isBlank() -> ""
                dateResult?.value == null ->
                    (dateResult?.warning ?: "วันที่ที่อ่านจากภาพ ($rawCandidateDate) ไม่ตรงเงื่อนไขที่กำหนด") + " • แสดงค่าที่อ่านได้ไว้ให้ตรวจแก้"
                dateResult.corrected ->
                    "วันที่ที่อ่านจากภาพ ${dateResult.original} ถูกปรับเป็น ${dateResult.value} ตามเงื่อนไขวันที่ กรุณาตรวจเทียบกับภาพ"
                else -> ""
            }''')
# Standardize time field after OCR merge.
rep('android-app/app/src/main/java/com/receiptocr/app/ocr/RealOcrPipeline.kt',
'''            record.copy(
                billDate = acceptedDate,
                ocrStoreId = storeId,''',
'''            val standardizedTime = if (currentImagePos && record.billTime.isNotBlank()) {
                ReceiptTimeOcrNormalizer.normalize(record.billTime).value ?: record.billTime
            } else record.billTime
            record.copy(
                billDate = acceptedDate,
                billTime = standardizedTime,
                ocrStoreId = storeId,''')
# Replace old configuredDateFormat helper with field helpers.
old = '''    private fun configuredDateFormat(templates: List<UniversalOcrTemplate>): String {
        val formats = templates.asSequence()
            .filter { it.active }
            .flatMap { it.recognition.rows.asSequence() }
            .flatMap { it.fields.asSequence() }
            .filter { it.type == "BILL_DATE" }
            .map { it.format.trim() }
            .filter { it.isNotBlank() }
            .toList()
        return formats.firstOrNull { it.uppercase() !in setOf("DATE", "ANY") }
            ?: formats.firstOrNull()
            ?: "DD/MM/YYYY"
    }
'''
new = '''    private fun configuredDateField(templates: List<UniversalOcrTemplate>): OcrTemplateField? =
        templates.asSequence()
            .filter { it.active }
            .flatMap { it.recognition.rows.asSequence() }
            .flatMap { it.fields.asSequence() }
            .firstOrNull { it.type == "BILL_DATE" }

    private fun dateFieldForRecord(
        record: PosRecord,
        templates: List<UniversalOcrTemplate>
    ): OcrTemplateField? {
        val name = record.ocrTemplateName.trim()
        val template = templates.firstOrNull { it.active && name.isNotBlank() && it.templateName == name }
            ?: return null
        return template.recognition.rows.asSequence()
            .flatMap { it.fields.asSequence() }
            .firstOrNull { it.type == "BILL_DATE" }
    }
'''
rep('android-app/app/src/main/java/com/receiptocr/app/ocr/RealOcrPipeline.kt', old, new)
# Ensure import available via config wildcard already present, no extra import.

# 11) Add strict canonical time validation before submission.
rep('android-app/app/src/main/java/com/receiptocr/app/validation/ReceiptValidationEngine.kt',
'''                if (record.billTime.isBlank()) issues += block("TIME_REQUIRED", "POS ${record.posNumber}: ยังไม่มีเวลา")
            }
        }
    }''',
'''                if (record.billTime.isBlank()) {
                    issues += block("TIME_REQUIRED", "POS ${record.posNumber}: ยังไม่มีเวลา")
                } else {
                    val normalizedTime = ReceiptTimeOcrNormalizer.normalize(record.billTime)
                    if (normalizedTime.value == null || normalizedTime.value != record.billTime) {
                        issues += block("TIME_FORMAT_POS_${record.posNumber}", "POS ${record.posNumber}: เวลาไม่อยู่ในรูปแบบ HH:mm")
                    }
                }
            }
        }
    }''')
# Need import ReceiptTimeOcrNormalizer.
rep('android-app/app/src/main/java/com/receiptocr/app/validation/ReceiptValidationEngine.kt',
'''import com.receiptocr.app.model.WorkItem
''',
'''import com.receiptocr.app.model.WorkItem
import com.receiptocr.app.ocr.ReceiptTimeOcrNormalizer
''')

# 12) App version Round89.
rep('android-app/app/build.gradle.kts','versionCode = 89','versionCode = 90')
rep('android-app/app/build.gradle.kts','versionName = "0.88.0"','versionName = "0.89.0"')

# 13) Tests for new configured date modes.
test_path='android-app/app/src/test/java/com/receiptocr/app/ocr/ReceiptDateOcrNormalizerTest.kt'
text=read(test_path)
insert='''
    @Test
    fun buddhistTwoDigitRuleMaps69To2026() {
        val result = ReceiptDateOcrNormalizer.normalize(
            raw = "20/08/69",
            configuredFormat = "DATE",
            referenceDate = LocalDate.of(2026, 9, 2),
            dateOrder = "DMY",
            dateCalendar = "BUDDHIST",
            dateYearDigits = 2
        )
        assertEquals("20/08/2026", result.value)
    }

    @Test
    fun buddhistFourDigitRuleMaps2569To2026() {
        val result = ReceiptDateOcrNormalizer.normalize(
            raw = "20/08/2569",
            configuredFormat = "DATE",
            referenceDate = LocalDate.of(2026, 9, 2),
            dateOrder = "DMY",
            dateCalendar = "BUDDHIST",
            dateYearDigits = 4
        )
        assertEquals("20/08/2026", result.value)
    }

    @Test
    fun gregorianTwoDigitRuleMaps26To2026() {
        val result = ReceiptDateOcrNormalizer.normalize(
            raw = "20/08/26",
            configuredFormat = "DATE",
            referenceDate = LocalDate.of(2026, 9, 2),
            dateOrder = "DMY",
            dateCalendar = "GREGORIAN",
            dateYearDigits = 2
        )
        assertEquals("20/08/2026", result.value)
    }

    @Test
    fun mdyGregorianFourDigitRuleNormalizesToDmyStorage() {
        val result = ReceiptDateOcrNormalizer.normalize(
            raw = "08/20/2026",
            configuredFormat = "DATE",
            referenceDate = LocalDate.of(2026, 9, 2),
            dateOrder = "MDY",
            dateCalendar = "GREGORIAN",
            dateYearDigits = 4
        )
        assertEquals("20/08/2026", result.value)
    }

    @Test
    fun wrongCalendarRuleIsRejectedButRawRemainsAvailable() {
        val result = ReceiptDateOcrNormalizer.normalize(
            raw = "20/08/69",
            configuredFormat = "DATE",
            referenceDate = LocalDate.of(2026, 9, 2),
            dateOrder = "DMY",
            dateCalendar = "GREGORIAN",
            dateYearDigits = 2
        )
        assertNull(result.value)
        assertEquals("20/08/69", result.original)
        assertTrue(result.warning.orEmpty().isNotBlank())
    }
'''
text=text.rstrip()[:-1]+insert+'}\n'
write(test_path,text)

print('Round89 patches applied')
