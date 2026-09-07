const fs=require("fs");
const vm=require("vm");
const assert=require("assert");

const source=fs.readFileSync("web-admin/ocr-save-reliability-round104162.js","utf8");

function makeHarness({read,post}){
  const calls=[];
  const document={getElementById:id=>id==="brandId"?{value:""}:null};
  const window={
    ReceiptDateRules:{normalize:(rule,brand)=>({...rule,brandId:brand||rule?.brandId||""})}
  };
  window.AdminAuth={
    json:async(path,opts={})=>{
      calls.push({path,opts});
      const method=String(opts.method||"GET").toUpperCase();
      return method==="GET"?read(path,opts):post(path,opts);
    }
  };
  const fastTimeout=fn=>{queueMicrotask(fn);return 0;};
  const context={window,document,console,Promise,Map,JSON,Date,Error,encodeURIComponent,decodeURIComponent,setTimeout:fastTimeout,queueMicrotask};
  vm.createContext(context);
  vm.runInContext(source,context);
  return {auth:window.AdminAuth,calls,window};
}

const rule={brandId:"Brand A",customerCounterMode:"CONTINUOUS"};
const template={
  schemaVersion:4,templateId:"t1",brandId:"Brand A",templateName:"รูปแบบ A",version:1,priority:100,active:true,sampleText:"",
  recognition:{rowCount:1,groupAsSingleRecord:true,rows:[]},validation:{},duplicatePolicy:{}
};

(async()=>{
  {
    let posts=0;
    const h=makeHarness({
      read:async()=>({receiptRule:rule,items:[]}),
      post:async()=>{posts++;return {ok:true};}
    });
    const out=await h.auth.json("/api/admin/brand-receipt-rules",{method:"POST",body:JSON.stringify({receiptRule:rule})});
    assert.equal(out.unchanged,true,"กติกาเดิมต้องไม่เขียน D1 ซ้ำ");
    assert.equal(posts,0,"กติกาเดิมต้องไม่มี POST");
  }

  {
    let posts=0,saved=false;
    const h=makeHarness({
      read:async()=>({receiptRule:saved?rule:{brandId:"Brand A"},items:[]}),
      post:async()=>{
        posts++;
        saved=true;
        throw new Error("D1_ERROR: D1 DB storage operation exceeded timeout which caused object to be reset");
      }
    });
    const out=await h.auth.json("/api/admin/brand-receipt-rules",{method:"POST",body:JSON.stringify({receiptRule:rule})});
    assert.equal(out.verifiedAfterTimeout,true,"timeout หลังเขียนสำเร็จต้องอ่านกลับมายืนยัน");
    assert.equal(posts,1,"ต้องอ่านกลับก่อน retry เพื่อไม่เขียนซ้ำ");
  }

  {
    let posts=0;
    const h=makeHarness({
      read:async()=>({receiptRule:rule,items:[]}),
      post:async()=>{posts++;await Promise.resolve();return {ok:true};}
    });
    const opts={method:"POST",body:JSON.stringify({template})};
    await Promise.all([h.auth.json("/api/ocr-templates",opts),h.auth.json("/api/ocr-templates",opts)]);
    assert.equal(posts,1,"การกดซ้ำพร้อมกันต้องรวมเป็นการเขียนครั้งเดียว");
  }

  {
    let posts=0,message="";
    const h=makeHarness({
      read:async()=>({receiptRule:rule,items:[]}),
      post:async()=>{posts++;throw new Error("D1_ERROR: D1 DB storage operation exceeded timeout");}
    });
    try{
      await h.auth.json("/api/ocr-templates",{method:"POST",body:JSON.stringify({template})});
    }catch(error){message=String(error.message||error);}
    assert.equal(posts,2,"timeout ที่ยังไม่บันทึกให้ retry ได้เพียงหนึ่งครั้ง");
    assert(message.includes("รูปแบบการอ่านบิล"),"ต้องบอกขั้นตอนที่ล้มเหลว");
    assert(message.includes("ยังไม่ยืนยันการบันทึก"),"ต้องบอกว่ายังยืนยันไม่ได้");
    assert(!message.includes("D1_ERROR"),"ผู้ใช้ไม่ควรเห็นข้อความเทคนิค D1");
  }

  console.log("ocr-save-reliability tests passed");
})().catch(error=>{console.error(error);process.exit(1);});
