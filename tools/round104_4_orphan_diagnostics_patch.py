from pathlib import Path
import sys

TARGET=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path('worker_round104_3.js').resolve()
text=TARGET.read_text(encoding='utf-8')

def once(old,new,label):
    global text
    if new in text:
        return
    c=text.count(old)
    if c!=1:
        raise SystemExit(f'{label}: expected 1 match, found {c}')
    text=text.replace(old,new,1)

once(
'        return json({ ok: true, service: "receiptocr-api", release: "0.104.3", evidenceRuntime: true, reviewEditor: true, evidenceFinalize: true, storageCoordinator: true, directBinaryEvidence: true, evidenceLinkerV2: true }, 200, { ...headers, "cache-control": "no-store" });',
'        return json({ ok: true, service: "receiptocr-api", release: "0.104.4", evidenceRuntime: true, reviewEditor: true, evidenceFinalize: true, storageCoordinator: true, directBinaryEvidence: true, evidenceLinkerV2: true, orphanDiagnostics: true, manualEvidenceLink: true }, 200, { ...headers, "cache-control": "no-store" });',
'health 1044')

health_anchor='''      if (url.pathname === "/api/storage/evidence-reconcile" && request.method === "POST") {'''
new_routes=r'''      if (url.pathname === "/api/storage/evidence-diagnostics" && request.method === "GET") {
        const admin=await requireAdmin(request,env); if(!admin)return unauthorized(headers); if(!env.R2)return json({error:"R2_BINDING_MISSING"},500,headers);
        const {results:incomplete}=await env.DB.prepare(`
          SELECT s.id,s.employee_code,u.full_name,s.work_date,s.brand,s.store_code,s.store_name,s.status,s.submitted_at,
            (SELECT COUNT(*) FROM field_submission_evidence e WHERE e.submission_id=s.id AND e.kind='R') AS receipt_count,
            (SELECT COUNT(*) FROM field_submission_evidence e WHERE e.submission_id=s.id AND e.kind='S') AS store_count,
            (SELECT COUNT(*) FROM field_submission_pos p WHERE p.submission_id=s.id AND COALESCE(p.no_receipt,0)=0) AS bill_required_count
          FROM field_submissions s LEFT JOIN app_users u ON u.employee_code=s.employee_code
          WHERE s.status IN ('EVIDENCE_PENDING','SUBMITTED','RETURNED')
            AND ((SELECT COUNT(*) FROM field_submission_evidence e WHERE e.submission_id=s.id AND e.kind='S')=0
              OR ((SELECT COUNT(*) FROM field_submission_pos p WHERE p.submission_id=s.id AND COALESCE(p.no_receipt,0)=0)>0
                AND (SELECT COUNT(*) FROM field_submission_evidence e WHERE e.submission_id=s.id AND e.kind='R')=0))
          ORDER BY s.updated_at DESC LIMIT 100
        `).all();
        const {results:orphanRows}=await env.DB.prepare(`
          SELECT r.object_key,r.size_bytes,r.content_type,r.created_at,r.updated_at
          FROM r2_objects r
          WHERE r.category='production' AND r.deleted=0
            AND NOT EXISTS(SELECT 1 FROM field_submission_evidence e WHERE e.object_key=r.object_key)
          ORDER BY r.updated_at DESC LIMIT 50
        `).all();
        const orphans=[];
        for(const row of (orphanRows||[])){
          const head=await env.R2.head(row.object_key); if(!head)continue;
          const meta=head.customMetadata||{},parsed=parseProductionEvidenceKey(row.object_key);
          const parts=String(row.object_key||'').split('/');
          const workDate=String(meta.workDate||parts[1]||'');
          const employeeCode=String(meta.employeeCode||parts[2]||'');
          const storeCode=String(meta.storeCode||parts[3]||'');
          const metaSubmissionId=Number(meta.submissionId||parsed?.submissionId||0);
          const candidateMap=new Map();
          if(metaSubmissionId>0){
            const exact=await env.DB.prepare(`SELECT id,employee_code,work_date,brand,store_code,store_name,status FROM field_submissions WHERE id=? LIMIT 1`).bind(metaSubmissionId).first();
            if(exact)candidateMap.set(Number(exact.id),exact);
          }
          if(employeeCode&&storeCode&&workDate){
            const {results:cands}=await env.DB.prepare(`SELECT id,employee_code,work_date,brand,store_code,store_name,status FROM field_submissions WHERE employee_code=? AND store_code=? AND work_date=? AND status IN ('EVIDENCE_PENDING','SUBMITTED','RETURNED') ORDER BY id DESC LIMIT 10`).bind(employeeCode,storeCode,workDate).all();
            for(const c of (cands||[]))candidateMap.set(Number(c.id),c);
          }
          const kind=String(meta.evidenceKind||parsed?.kind||'').toUpperCase();
          const slot=Number(meta.evidenceSlot??parsed?.slot??0);
          orphans.push({objectKey:row.object_key,sizeBytes:Number(head.size||row.size_bytes||0),contentType:head.httpMetadata?.contentType||row.content_type||null,uploadedAt:head.uploaded?head.uploaded.toISOString():row.created_at||null,metadata:meta,inferred:{workDate,employeeCode,storeCode,submissionId:metaSubmissionId,kind:['R','S'].includes(kind)?kind:'R',slot:Number.isInteger(slot)&&slot>=0?slot:0},candidates:[...candidateMap.values()]});
        }
        return json({ok:true,incompleteSubmissions:incomplete||[],orphans},200,{...headers,"cache-control":"no-store"});
      }

      if (url.pathname === "/api/storage/evidence-orphan-file" && request.method === "GET") {
        const admin=await requireAdmin(request,env); if(!admin)return unauthorized(headers); if(!env.R2)return json({error:"R2_BINDING_MISSING"},500,headers);
        const key=String(url.searchParams.get('key')||''); if(!key)return json({error:"OBJECT_KEY_REQUIRED"},400,headers);
        const tracked=await env.DB.prepare(`SELECT object_key FROM r2_objects WHERE object_key=? AND category='production' AND deleted=0 LIMIT 1`).bind(key).first();
        if(!tracked)return json({error:"OBJECT_NOT_TRACKED"},404,headers);
        const linked=await env.DB.prepare(`SELECT id FROM field_submission_evidence WHERE object_key=? LIMIT 1`).bind(key).first();
        if(linked)return json({error:"OBJECT_ALREADY_LINKED"},409,headers);
        const object=await env.R2.get(key); if(!object)return json({error:"R2_OBJECT_NOT_FOUND"},404,headers);
        const h=new Headers(headers); object.writeHttpMetadata(h); h.set('cache-control','private, no-store'); h.set('content-disposition','inline');
        return new Response(object.body,{headers:h});
      }

      if (url.pathname === "/api/storage/evidence-orphan-link" && request.method === "POST") {
        const admin=await requireAdmin(request,env); if(!admin)return unauthorized(headers); if(!env.R2)return json({error:"R2_BINDING_MISSING"},500,headers);
        const b=await readJson(request).catch(()=>({})); if(String(b.confirmText||'')!=='LINK')return json({error:"CONFIRM_LINK_REQUIRED"},400,headers);
        const objectKey=String(b.objectKey||''),submissionId=Number(b.submissionId||0),kind=String(b.kind||'').toUpperCase(),slot=Number(b.slot);
        if(!objectKey||!submissionId)return json({error:"OBJECT_AND_SUBMISSION_REQUIRED"},400,headers);
        if(!['R','S'].includes(kind)||!Number.isInteger(slot)||slot<0||slot>=(kind==='R'?3:10))return json({error:"EVIDENCE_SLOT_OR_KIND_INVALID"},400,headers);
        const already=await env.DB.prepare(`SELECT id,submission_id FROM field_submission_evidence WHERE object_key=? LIMIT 1`).bind(objectKey).first(); if(already)return json({error:"OBJECT_ALREADY_LINKED",evidenceId:already.id,submissionId:already.submission_id},409,headers);
        const sub=await env.DB.prepare(`SELECT id,employee_code,work_date,brand,store_code,status FROM field_submissions WHERE id=? LIMIT 1`).bind(submissionId).first(); if(!sub)return json({error:"SUBMISSION_NOT_FOUND"},404,headers);
        if(!['EVIDENCE_PENDING','SUBMITTED','RETURNED'].includes(String(sub.status||'').toUpperCase()))return json({error:"SUBMISSION_EDIT_LOCKED"},409,headers);
        const occupied=await env.DB.prepare(`SELECT id,object_key FROM field_submission_evidence WHERE submission_id=? AND kind=? AND slot=? LIMIT 1`).bind(submissionId,kind,slot).first(); if(occupied)return json({error:"SLOT_ALREADY_OCCUPIED",evidenceId:occupied.id,objectKey:occupied.object_key},409,headers);
        const head=await env.R2.head(objectKey); if(!head)return json({error:"R2_OBJECT_NOT_FOUND"},404,headers);
        const tracked=await env.DB.prepare(`SELECT object_key FROM r2_objects WHERE object_key=? AND category='production' AND deleted=0 LIMIT 1`).bind(objectKey).first(); if(!tracked)return json({error:"OBJECT_NOT_TRACKED"},404,headers);
        const contentType=head.httpMetadata?.contentType||null,meta=head.customMetadata||{};
        await env.DB.batch([
          env.DB.prepare(`INSERT INTO field_submission_evidence(submission_id,kind,slot,object_key,content_type,size_bytes,source,captured_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,NULL,COALESCE(?,CURRENT_TIMESTAMP),CURRENT_TIMESTAMP)`).bind(submissionId,kind,slot,objectKey,contentType,Number(head.size||0),'ADMIN_MANUAL_LINK',head.uploaded?head.uploaded.toISOString():null),
          env.DB.prepare(`UPDATE r2_objects SET protected=1,deleted=0,updated_at=CURRENT_TIMESTAMP WHERE object_key=?`).bind(objectKey)
        ]);
        let finalized=false;
        if(String(sub.status||'').toUpperCase()==='EVIDENCE_PENDING'){
          const counts=await env.DB.prepare(`SELECT SUM(CASE WHEN kind='R' THEN 1 ELSE 0 END) receipt_count,SUM(CASE WHEN kind='S' THEN 1 ELSE 0 END) store_count FROM field_submission_evidence WHERE submission_id=?`).bind(submissionId).first();
          const required=await env.DB.prepare(`SELECT COUNT(*) c FROM field_submission_pos WHERE submission_id=? AND COALESCE(no_receipt,0)=0`).bind(submissionId).first();
          if(Number(counts?.store_count||0)>0&&(Number(required?.c||0)===0||Number(counts?.receipt_count||0)>0)){
            await env.DB.prepare(`UPDATE field_submissions SET status='SUBMITTED',submitted_at=COALESCE(submitted_at,CURRENT_TIMESTAMP),updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='EVIDENCE_PENDING'`).bind(submissionId).run(); finalized=true;
          }
        }
        await audit(env,admin,'EVIDENCE_MANUAL_LINK',{objectKey,submissionId,kind,slot,finalized,sourceMetadata:meta},request);
        return json({ok:true,objectKey,submissionId,kind,slot,finalized},200,{...headers,'cache-control':'no-store'});
      }

'''+health_anchor
once(health_anchor,new_routes,'storage diagnostics routes')

TARGET.write_text(text,encoding='utf-8')
print('Round104.4 orphan diagnostics/manual link patch applied:',TARGET)
