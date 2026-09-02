"use strict";

const assert=require("node:assert/strict");
const engine=require("../web-admin/ocr-pattern-engine.js");

const field=(type,extra={})=>({type,required:true,minLength:1,maxLength:18,...extra});

const cjRow=[
  field("BILL_DATE",{example:"20/08/2026",minLength:10,maxLength:10}),
  field("BILL_TIME",{example:"22:41",minLength:5,maxLength:5}),
  field("LITERAL",{example:"BNO:S",literal:"BNO:S"}),
  field("YEAR_VALUE",{example:"26",minLength:2,maxLength:2}),
  field("MONTH_VALUE",{example:"08",minLength:2,maxLength:2}),
  field("STORE_ID",{example:"0652",minLength:4,maxLength:4}),
  field("POS_NUMBER",{example:"N02",posPrefixes:"N,B",posDigits:2,minLength:3,maxLength:3}),
  field("SEPARATOR",{example:"-",separatorValue:"-"}),
  // ตั้งใจตั้งเงื่อนไขไว้ 5 หลัก แต่ข้อความจริงมี 6 หลัก ต้องอ่านครบแล้วค่อยเตือน
  field("CUSTOMER_VALUE",{example:"00184",minLength:5,maxLength:5})
];

const cj=engine.findRecords([cjRow],"20/08/2026 22:41 BNO:S26080652N02-004184");
assert.equal(cj.records.length,1);
assert.deepEqual(cj.records[0].fields,{
  BILL_DATE:"20/08/2026",BILL_TIME:"22:41",YEAR_VALUE:"26",MONTH_VALUE:"08",
  STORE_ID:"0652",POS_NUMBER:"N02",CUSTOMER_VALUE:"004184"
});

const cjSplit=engine.findRecords([cjRow],"20/08/2026 22:41\nBNO : S26080652N02-004184");
assert.equal(cjSplit.records.length,1);
assert.equal(cjSplit.records[0].fields.CUSTOMER_VALUE,"004184");

const cjOcrConfusable=engine.findRecords([cjRow],"2O/O8/2O26 22:4I 8N0 ; S26O8O652N0I-OO4I84");
assert.equal(cjOcrConfusable.records.length,1);
assert.equal(cjOcrConfusable.records[0].fields.BILL_DATE,"20/08/2026");
assert.equal(cjOcrConfusable.records[0].fields.POS_NUMBER,"N01");
assert.equal(cjOcrConfusable.records[0].fields.CUSTOMER_VALUE,"004184");

const cjCompositeRow=[
  cjRow[0],cjRow[1],
  field("COMPOSITE_CODE",{
    prefix:"BNO:S",separator:"-",
    segments:[
      {type:"YEAR_VALUE",length:2,example:"26"},{type:"MONTH_VALUE",length:2,example:"08"},
      {type:"STORE_ID",length:4,example:"0652"},{type:"POS_NUMBER",length:3,example:"N02"},
      {type:"CUSTOMER_VALUE",length:5,example:"00184"}
    ]
  })
];
const cjComposite=engine.findRecords([cjCompositeRow],"20/08/2026 22:41 BNO:S26080652N02-004184");
assert.equal(cjComposite.records.length,1);
assert.equal(cjComposite.records[0].fields.CUSTOMER_VALUE,"004184");

const lgoRow=[
  field("BILL_DATE",{example:"22/08/2026",minLength:10,maxLength:10}),
  field("BILL_TIME",{example:"21:54",minLength:5,maxLength:5}),
  field("STORE_ID",{example:"1705",minLength:4,maxLength:4}),
  field("POS_NUMBER",{example:"001",posDigits:3,minLength:3,maxLength:3}),
  field("COMPOSITE_CODE",{example:"17052001",minLength:8,maxLength:8}),
  field("CUSTOMER_VALUE",{example:"6766",minLength:1,maxLength:12})
];
const lgoText=[
  "22/08/2026 21:54 1705 001 17052001 6766",
  "22/08/2026 14:57 1705 002 17053001 0911",
  "24/08/2026 20:21 1705 003 17051002 8997"
].join("\n");
const lgo=engine.findRecords([lgoRow],lgoText);
assert.equal(lgo.records.length,3);
assert.deepEqual(lgo.records.map(record=>record.fields.POS_NUMBER),["001","002","003"]);
assert.deepEqual(lgo.records.map(record=>record.fields.CUSTOMER_VALUE),["6766","0911","8997"]);

// ข้อความจากภาพ L-go จริงอาจมีขีด/จุดรบกวนระหว่างช่อง แต่ต้องยังพบทุก POS
const lgoNoisy=engine.findRecords([lgoRow],[
  "21/08/2026 | 21:54 - 1705 001 17052001 6766",
  "22/08/2026 ; 14:57 1705-002 17053001 0911",
  "24/08/2026 20:21 1705 003 17051002 8997"
].join("\n"));
assert.equal(lgoNoisy.records.length,3);
assert.deepEqual(lgoNoisy.records.map(record=>record.fields.POS_NUMBER),["001","002","003"]);

// อีกแบรนด์/อีกรูปแบบจาก Admin: ภาพเดียวมี N01-N04 และ OCR อาจอ่าน S เป็น $ หรือ 5
const cj2125Row=[
  field("BILL_DATE",{example:"24/08/2026",minLength:10,maxLength:10}),
  field("BILL_TIME",{example:"11:17",minLength:5,maxLength:5}),
  field("LITERAL",{example:"BNO:S",literal:"BNO:S"}),
  field("YEAR_VALUE",{example:"26",minLength:2,maxLength:2,compareTo:"BILL_DATE"}),
  field("MONTH_VALUE",{example:"08",minLength:2,maxLength:2,compareTo:"BILL_DATE"}),
  field("STORE_ID",{example:"2125",minLength:4,maxLength:4}),
  field("POS_NUMBER",{example:"N01",posPrefixes:"N",posDigits:2,minLength:3,maxLength:3}),
  field("SEPARATOR",{example:"-",separatorValue:"-"}),
  field("CUSTOMER_VALUE",{example:"003163",minLength:6,maxLength:6})
];
const cjFour=engine.findRecords([cj2125Row],[
  "24/08/2026 11:17 BNO:S26082125N01-003163",
  "24/08/2026 17:04 BNO:$26082125N02-005203",
  "24/08/2026 18:11 BNO:526082125N03-004175",
  "22/08/2026 22:22 BNO:S26082125N04-000486"
].join("\n"));
assert.equal(cjFour.records.length,4);
assert.deepEqual(cjFour.records.map(record=>record.fields.POS_NUMBER),["N01","N02","N03","N04"]);

// ML Kit บนโทรศัพท์มักแยกหนึ่งแถวจริงเป็น 3 บรรทัดเมื่อบิลเอียงหรือซ้อนกัน
// ต้องยังอ่านครบทุก POS โดยไม่ยึดว่าหนึ่งชุดข้อมูลต้องอยู่ใน Text.Line เดียว
const cjFourFragmented=engine.findRecords([cj2125Row],[
  "24/08/2026", "11:17", "BNO:S26082125N01-003163",
  "24/08/2026", "17:04", "BNO:$26082125N02-005203",
  "24/08/2026", "18:11", "BNO:526082125N03-004175",
  "22/08/2026", "22:22", "BNO:S26082125N04-000486"
].join("\n"),{maxJoin:4});
assert.equal(cjFourFragmented.records.length,4);
assert.deepEqual(cjFourFragmented.records.map(record=>record.fields.POS_NUMBER),["N01","N02","N03","N04"]);

// รูปแบบหลายแถวที่ Admin กำหนด: วันที่/เวลาอยู่แถวแรก รหัสรวมอยู่แถวถัดไป
const cjTwoRows=engine.findRecords([
  [cj2125Row[0],cj2125Row[1]],
  [cj2125Row[2],cj2125Row[3],cj2125Row[4],cj2125Row[5],cj2125Row[6],cj2125Row[7],cj2125Row[8]]
],[
  "24/08/2026 11:17", "BNO:S26082125N01-003163",
  "24/08/2026 17:04", "BNO:$26082125N02-005203",
  "24/08/2026 18:11", "BNO:526082125N03-004175",
  "22/08/2026 22:22", "BNO:S26082125N04-000486"
].join("\n"),{lineTolerance:1});
assert.equal(cjTwoRows.records.length,4);
assert.deepEqual(cjTwoRows.records.map(record=>record.fields.POS_NUMBER),["N01","N02","N03","N04"]);

const dateWarning=engine.findRecords([cjRow],"19/07/2025 22:41 BNO:S26080652N02-004184");
assert.equal(dateWarning.records.length,1);
assert.equal(dateWarning.records[0].fields.BILL_DATE,"19/07/2025");

// MB: POS คือหลักสุดท้ายของรหัส 3 หลักหลัง R ไม่ใช่เลข 3 หลักทั้งชุด
const mbRow=[
  field("LITERAL",{example:"R",literal:"R",minLength:1,maxLength:1}),
  field("NUMBER_TEXT",{example:"20",minLength:2,maxLength:2}),
  field("POS_NUMBER",{example:"1",posDigits:1,minLength:1,maxLength:1}),
  field("CUSTOMER_VALUE",{example:"051846",minLength:6,maxLength:6}),
  field("LITERAL",{example:"U",literal:"U",minLength:1,maxLength:1}),
  field("NUMBER_TEXT",{example:"110030",minLength:6,maxLength:6}),
  field("BILL_DATE",{example:"20/08/69",minLength:8,maxLength:8}),
  field("BILL_TIME",{example:"17:51",minLength:5,maxLength:5})
];
const mb=engine.findRecords([mbRow],[
  "R201051846U110030 20/08/69 17:51",
  "R202039030U400072 20/08/69 17:18"
].join("\n"));
assert.equal(mb.records.length,2);
assert.deepEqual(mb.records.map(record=>record.fields.POS_NUMBER),["1","2"]);
assert.deepEqual(mb.records.map(record=>record.fields.CUSTOMER_VALUE),["051846","039030"]);
assert.deepEqual(mb.records.map(record=>record.fields.BILL_DATE),["20/08/69","20/08/69"]);
assert.deepEqual(mb.records.map(record=>record.fields.BILL_TIME),["17:51","17:18"]);


// Round90: วันที่ต้องจับตามลำดับและจำนวนหลักของปีที่ Admin ตั้ง ไม่ใช้ regex วันที่แบบเดียวทุกแบรนด์
const ymdRow=[
  field("BILL_DATE",{example:"2026/08/20",dateOrder:"YMD",dateCalendar:"GREGORIAN",dateYearDigits:4,minLength:10,maxLength:10}),
  field("BILL_TIME",{example:"07:55",minLength:5,maxLength:5}),
  field("LITERAL",{example:"Rcpt#10",literal:"Rcpt#10"}),
  field("POS_NUMBER",{example:"1",posDigits:1,minLength:1,maxLength:1}),
  field("CUSTOMER_VALUE",{example:"002715",minLength:6,maxLength:6})
];
const ymdOk=engine.findRecords([ymdRow],"2026/08/20 07:55 Rcpt#101002715");
assert.equal(ymdOk.records.length,1);
const ymdWrong=engine.findRecords([ymdRow],"20/08/2026 07:55 Rcpt#101002715");
assert.equal(ymdWrong.records.length,0);

console.log("OCR pattern engine: Admin-driven CJ/L-go, noisy text, four POS and warning-value tests passed");
