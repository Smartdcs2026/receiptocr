const assert=require('assert');
const Engine=require('../web-admin/ocr-pattern-engine.js');

const row=[
  {type:'LITERAL',example:'Date :',literal:'Date :',minLength:5,maxLength:6,required:true},
  {type:'BILL_DATE',example:'14-08-26',dateOrder:'DMY',dateCalendar:'GREGORIAN',dateYearDigits:2,minLength:8,maxLength:8,required:true},
  {type:'BILL_TIME',example:'22:05',minLength:5,maxLength:5,required:true},
  {type:'NUMBER_TEXT',example:'20',minLength:2,maxLength:2,required:true},
  {type:'POS_NUMBER',example:'1',minLength:1,maxLength:1,posDigits:1,required:true},
  {type:'CUSTOMER_VALUE',example:'157464',minLength:6,maxLength:6,required:true}
];
const result=Engine.findRecords([row],'Date: 14-08-26 22:05 201157464');
assert.strictEqual(result.records.length,1,'Date : and Date: must be equivalent after normalization');
assert.strictEqual(result.records[0].fields.POS_NUMBER,'1');
console.log('Round94 Admin POS/literal regression passed');
