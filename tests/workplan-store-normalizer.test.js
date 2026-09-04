const assert=require('assert');
const normalizer=require('../web-admin/workplan-store-normalizer.js');

assert.strictEqual(normalizer.normalizeStoreCode('CJ2125',4).receiptStoreId,'2125');
assert.strictEqual(normalizer.normalizeStoreCode('CJ539',4).receiptStoreId,'0539');
assert.strictEqual(normalizer.normalizeStoreCode('JF3017',4).receiptStoreId,'3017');
assert.strictEqual(normalizer.normalizeStoreCode('2982',4).receiptStoreId,'2982');
assert.strictEqual(normalizer.normalizeStoreCode('0652',4).receiptStoreId,'0652');
assert.strictEqual(normalizer.normalizeStoreCode('CJ',4).ok,false);
assert.strictEqual(normalizer.normalizeStoreCode('CJ12345',4).ok,false);
assert.strictEqual(normalizer.looksTemporaryStoreCode('TEMP-CJ-00001'),true);
assert.strictEqual(normalizer.looksTemporaryStoreCode('CJ539'),false);

const lengths=normalizer.templateStoreLengths([
  {template:{active:true,recognition:{rows:[{fields:[{type:'STORE_ID',example:'0652',minLength:1,maxLength:12}]}]}}}
]);
assert.deepStrictEqual(lengths,[4]);
assert.strictEqual(normalizer.resolveFixedLength([{template:{recognition:{rows:[{fields:[{type:'STORE_ID',example:'0652'}]}]}}}]),4);

(async()=>{
  const load=async brand=>({items:[{template:{active:true,recognition:{rows:[{fields:[{type:'STORE_ID',example:brand==='CJ'?'0652':'2982'}]}]}}}]});
  const result=await normalizer.enrichRows([
    {brand:'CJ',storeCode:'CJ539'},
    {brand:'CJ',storeCode:'CJ2125'},
    {brand:'L-go fresh',storeCode:'2982'},
    {brand:'CJ',storeCode:'TEMP-CJ-00001'},
    {brand:'CJ',storeCode:'TEMP-CJ-00002',receiptStoreId:'1600'}
  ],load);
  assert.deepStrictEqual(result.errors,[]);
  assert.strictEqual(result.rows[0].receiptStoreId,'0539');
  assert.strictEqual(result.rows[1].receiptStoreId,'2125');
  assert.strictEqual(result.rows[2].receiptStoreId,'2982');
  assert.strictEqual(result.rows[3].receiptStoreId,'');
  assert.strictEqual(result.rows[3].receiptStoreIdPending,true);
  assert.strictEqual(result.rows[4].receiptStoreId,'1600');
  assert.strictEqual(result.rows[4].receiptStoreIdPending,false);
  console.log('workplan-store-normalizer tests passed');
})().catch(error=>{console.error(error);process.exit(1)});
