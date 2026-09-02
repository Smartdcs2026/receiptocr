(function(root,factory){
  const api=factory();
  if(typeof module==="object"&&module.exports)module.exports=api;
  root.ReceiptOcrTemplateContract=api;
})(typeof globalThis!=="undefined"?globalThis:this,function(){
  "use strict";
  const SCHEMA_VERSION=4;
  const MAX_ROWS=3;
  const FIELD_TYPES=["BILL_DATE","BILL_TIME","CUSTOMER_VALUE","STORE_ID","POS_NUMBER","YEAR_VALUE","MONTH_VALUE","DAY_VALUE","EMPLOYEE_CODE","COMPOSITE_CODE","LITERAL","SEPARATOR","NUMBER_TEXT","ALNUM_TEXT","IGNORE"];
  const SEGMENT_TYPES=["LITERAL","YEAR_VALUE","MONTH_VALUE","DAY_VALUE","STORE_ID","POS_NUMBER","EMPLOYEE_CODE","CUSTOMER_VALUE","SEPARATOR","NUMBER_TEXT","ALNUM_TEXT","IGNORE"];
  const COMPARE_TARGETS=["NONE","BILL_DATE","WORK_DATE"];
  const COUNTER_CYCLES=["CONTINUOUS","DAILY","MONTHLY","YEARLY"];
  const DATE_ORDERS=["DMY","MDY","YMD"];
  const DATE_CALENDARS=["AUTO","GREGORIAN","BUDDHIST"];
  const DATE_YEAR_DIGITS=[0,2,4];

  function validate(template){
    const errors=[];
    if(Number(template?.schemaVersion)!==SCHEMA_VERSION)errors.push(`แม่แบบต้องเป็นรุ่น ${SCHEMA_VERSION}`);
    if(!String(template?.templateId||"").trim())errors.push("ไม่มีรหัสแม่แบบ");
    if(!String(template?.brandId||"").trim())errors.push("ไม่ได้เลือกแบรนด์");
    if(!String(template?.templateName||"").trim())errors.push("ไม่มีชื่อรูปแบบ");
    const rows=template?.recognition?.rows;
    if(!Array.isArray(rows)||rows.length<1||rows.length>MAX_ROWS)errors.push(`ต้องมีข้อมูล 1-${MAX_ROWS} แถว`);
    let hasPos=false;
    (rows||[]).forEach((row,rowIndex)=>{
      if(!Array.isArray(row?.fields)||!row.fields.length)errors.push(`แถว ${rowIndex+1} ยังไม่มีข้อมูล`);
      (row?.fields||[]).forEach((field,fieldIndex)=>{
        const location=`แถว ${rowIndex+1} กล่อง ${fieldIndex+1}`;
        if(!FIELD_TYPES.includes(field.type))errors.push(`${location}: ชนิดข้อมูลไม่รองรับ`);
        const min=Number(field.minLength),max=Number(field.maxLength);
        if(!Number.isInteger(min)||!Number.isInteger(max)||min<0||max<Math.max(1,min)||max>40)errors.push(`${location}: จำนวนหลักไม่ถูกต้อง`);
        if(!COMPARE_TARGETS.includes(String(field.compareTo||"NONE").toUpperCase()))errors.push(`${location}: เงื่อนไขเปรียบเทียบไม่รองรับ`);
        if(field.type==="BILL_DATE"){
          if(!DATE_ORDERS.includes(String(field.dateOrder||"DMY").toUpperCase()))errors.push(`${location}: ลำดับวันที่ไม่รองรับ`);
          if(!DATE_CALENDARS.includes(String(field.dateCalendar||"AUTO").toUpperCase()))errors.push(`${location}: ระบบปีไม่รองรับ`);
          if(!DATE_YEAR_DIGITS.includes(Number(field.dateYearDigits||0)))errors.push(`${location}: จำนวนหลักปีต้องเป็น 2 หรือ 4 หลัก`);
        }
        if(field.type==="POS_NUMBER"){
          hasPos=true;
          const digits=Number(field.posDigits||2);
          if(!Number.isInteger(digits)||digits<1||digits>6)errors.push(`${location}: จำนวนหลัก POS ต้องอยู่ระหว่าง 1-6`);
        }
        if(field.type==="COMPOSITE_CODE"){
          const segments=field.composite?.segments||[];
          segments.forEach(segment=>{
            if(!SEGMENT_TYPES.includes(segment.type))errors.push(`${location}: ส่วนของรหัสชนิด ${segment.type||"-"} ไม่รองรับ`);
            if(segment.type==="POS_NUMBER")hasPos=true;
          });
        }
      });
    });
    if(!hasPos)errors.push("ต้องมีหมายเลขเครื่องอย่างน้อยหนึ่งกล่อง");
    const cycle=String(template?.duplicatePolicy?.customerCounterCycle||"CONTINUOUS").toUpperCase();
    if(!COUNTER_CYCLES.includes(cycle))errors.push("รอบยอด/เลขลูกค้าไม่รองรับ");
    return [...new Set(errors)];
  }

  return {SCHEMA_VERSION,MAX_ROWS,FIELD_TYPES,SEGMENT_TYPES,COMPARE_TARGETS,COUNTER_CYCLES,DATE_ORDERS,DATE_CALENDARS,DATE_YEAR_DIGITS,validate};
});
