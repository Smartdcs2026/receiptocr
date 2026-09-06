from pathlib import Path
import re
import sys

TARGET = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path('worker_round104_4.js').resolve()
text = TARGET.read_text(encoding='utf-8')


def once(old, new, label):
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected 1 match, found {count}')
    text = text.replace(old, new, 1)


once(
    'release: "0.104.4", evidenceRuntime: true, reviewEditor: true, evidenceFinalize: true, storageCoordinator: true, directBinaryEvidence: true, evidenceLinkerV2: true, orphanDiagnostics: true, manualEvidenceLink: true',
    'release: "0.104.6", evidenceRuntime: true, reviewEditor: true, evidenceFinalize: true, storageCoordinator: true, directBinaryEvidence: true, evidenceLinkerV2: true, orphanDiagnostics: true, manualEvidenceLink: true, officePerformance: true',
    'health release',
)

once(
    '  if (pathname === "/api/admin/me" || pathname === "/api/admin/logout" || pathname === "/api/admin/change-password") return true;\n',
    '  if (pathname === "/api/admin/me" || pathname === "/api/admin/logout" || pathname === "/api/admin/change-password") return true;\n  if (pathname === "/api/admin/office-summary" && readOnly) return true;\n',
    'office summary permission',
)

once(
    '''  await env.DB.prepare(`
    UPDATE admin_sessions SET last_seen_at=CURRENT_TIMESTAMP WHERE id=?
  `).bind(row.session_id).run();

  return row;''',
    '''  // Fixed session expiry: do not write to D1 on every read or image request.
  return row;''',
    'session write removal',
)

health_start = text.index('      if (url.pathname === "/api/storage/evidence-health" && request.method === "GET") {')
health_end = text.index('      if (url.pathname === "/api/storage/evidence-diagnostics"', health_start)
text = text[:health_start] + '''      if (url.pathname === "/api/storage/evidence-health" && request.method === "GET") {
        const admin=await requireAdmin(request,env); if(!admin)return unauthorized(headers);
        const [evidence,pending,tracked,orphans,untrackedRefs]=await Promise.all([
          env.DB.prepare(`SELECT COUNT(*) AS c FROM field_submission_evidence`).first(),
          env.DB.prepare(`SELECT COUNT(*) AS c FROM field_submissions WHERE status='EVIDENCE_PENDING'`).first(),
          env.DB.prepare(`SELECT COUNT(*) AS c,COALESCE(SUM(size_bytes),0) AS bytes FROM r2_objects WHERE category='production' AND deleted=0`).first(),
          env.DB.prepare(`SELECT COUNT(*) AS c FROM r2_objects r WHERE r.category='production' AND r.deleted=0 AND NOT EXISTS(SELECT 1 FROM field_submission_evidence e WHERE e.object_key=r.object_key)`).first(),
          env.DB.prepare(`SELECT COUNT(*) AS c FROM field_submission_evidence e WHERE NOT EXISTS(SELECT 1 FROM r2_objects r WHERE r.object_key=e.object_key AND r.deleted=0)`).first()
        ]);
        return json({ok:true,evidenceRows:Number(evidence?.c||0),pendingSubmissions:Number(pending?.c||0),trackedProductionObjects:Number(tracked?.c||0),trackedProductionBytes:Number(tracked?.bytes||0),orphanTrackedObjects:Number(orphans?.c||0),checkedEvidence:Number(evidence?.c||0),missingInR2:Number(untrackedRefs?.c||0),fastCheck:true},200,{...headers,"cache-control":"no-store"});
      }

''' + text[health_end:]

diag_start = text.index('        const orphans=[];\n        for(const row of (orphanRows||[])){', text.index('/api/storage/evidence-diagnostics'))
diag_end = text.index('        return json({ok:true,incompleteSubmissions:incomplete||[],orphans}', diag_start)
text = text[:diag_start] + '''        const orphans=[];
        for(const row of (orphanRows||[])){
          const parsed=parseProductionEvidenceKey(row.object_key);
          const parts=String(row.object_key||'').split('/');
          const workDate=String(parts[1]||''),employeeCode=String(parts[2]||''),storeCode=String(parts[3]||'');
          const metaSubmissionId=Number(parsed?.submissionId||parts[4]||0),candidateMap=new Map();
          if(metaSubmissionId>0){
            const exact=await env.DB.prepare(`SELECT id,employee_code,work_date,brand,store_code,store_name,status FROM field_submissions WHERE id=? LIMIT 1`).bind(metaSubmissionId).first();
            if(exact)candidateMap.set(Number(exact.id),exact);
          }
          if(employeeCode&&storeCode&&workDate){
            const {results:cands}=await env.DB.prepare(`SELECT id,employee_code,work_date,brand,store_code,store_name,status FROM field_submissions WHERE employee_code=? AND store_code=? AND work_date=? AND status IN ('EVIDENCE_PENDING','SUBMITTED','RETURNED') ORDER BY id DESC LIMIT 10`).bind(employeeCode,storeCode,workDate).all();
            for(const c of (cands||[]))candidateMap.set(Number(c.id),c);
          }
          const kind=String(parsed?.kind||'R').toUpperCase(),slot=Number(parsed?.slot||0);
          orphans.push({objectKey:row.object_key,sizeBytes:Number(row.size_bytes||0),contentType:row.content_type||null,uploadedAt:row.created_at||null,metadata:{},inferred:{workDate,employeeCode,storeCode,submissionId:metaSubmissionId,kind:['R','S'].includes(kind)?kind:'R',slot:Number.isInteger(slot)&&slot>=0?slot:0},candidates:[...candidateMap.values()]});
        }
''' + text[diag_end:]

anchor = '      if(url.pathname==="/api/admin/submissions" && request.method==="GET"){' 
office = '''      if(url.pathname==="/api/admin/office-summary" && request.method==="GET"){
        const admin=await requireAdmin(request,env); if(!admin)return unauthorized(headers);
        await ensureGovernance(env);
        const [statusCounts,userCount,settings,totals]=await Promise.all([
          env.DB.prepare(`SELECT status,COUNT(*) AS c FROM field_submissions WHERE status<>'EVIDENCE_PENDING' GROUP BY status`).all(),
          env.DB.prepare(`SELECT COUNT(*) AS c FROM app_users WHERE active=1`).first(),
          env.DB.prepare(`SELECT * FROM storage_governance WHERE id=1`).first(),
          env.DB.prepare(`SELECT COALESCE(SUM(CASE WHEN deleted=0 THEN size_bytes ELSE 0 END),0) AS used_bytes,SUM(CASE WHEN deleted=0 THEN 1 ELSE 0 END) AS object_count FROM r2_objects`).first()
        ]);
        const counts={SUBMITTED:0,RETURNED:0,APPROVED:0,REJECTED:0};
        for(const row of (statusCounts.results||[]))counts[String(row.status||'').toUpperCase()]=Number(row.c||0);
        const used=Number(totals?.used_bytes||0),quota=Number(settings?.free_quota_bytes||10000000000),pct=percent(used,quota);
        let level='OK'; if(pct>=Number(settings?.warn95||95))level='CRITICAL'; else if(pct>=Number(settings?.warn85||85))level='HIGH'; else if(pct>=Number(settings?.warn70||70))level='WARNING';
        return json({ok:true,counts,activeUsers:Number(userCount?.c||0),storage:{percentUsed:pct,objectCount:Number(totals?.object_count||0),level}},200,{...headers,'cache-control':'no-store'});
      }

'''
once(anchor, office + anchor, 'office summary route')

list_start = text.index(anchor)
list_end = text.index('      const subDetailMatch=', list_start)
text = text[:list_start] + '''      if(url.pathname==="/api/admin/submissions" && request.method==="GET"){
        const admin=await requireAdmin(request,env); if(!admin)return unauthorized(headers);
        const status=String(url.searchParams.get("status")||"").trim().toUpperCase();
        const dateFrom=String(url.searchParams.get("dateFrom")||url.searchParams.get("from")||"").trim();
        const dateTo=String(url.searchParams.get("dateTo")||url.searchParams.get("to")||"").trim();
        const summary=String(url.searchParams.get("summary")||"")==="1";
        const limit=Math.max(1,Math.min(500,Number(url.searchParams.get("limit")||500)||500));
        const where=[],binds=[];
        if(status){where.push('s.status=?');binds.push(status);}else where.push("s.status<>'EVIDENCE_PENDING'");
        if(/^\\d{4}-\\d{2}-\\d{2}$/.test(dateFrom)){where.push('s.work_date>=?');binds.push(dateFrom);}
        if(/^\\d{4}-\\d{2}-\\d{2}$/.test(dateTo)){where.push('s.work_date<=?');binds.push(dateTo);}
        const whereSql=where.length?'WHERE '+where.join(' AND '):'';
        if(summary){
          const row=await env.DB.prepare(`SELECT COUNT(*) AS c FROM field_submissions s ${whereSql}`).bind(...binds).first();
          return json({count:Number(row?.c||0)},200,{...headers,'cache-control':'no-store'});
        }
        const {results}=await env.DB.prepare(`
          WITH ev AS (SELECT submission_id,SUM(CASE WHEN kind='R' THEN 1 ELSE 0 END) AS receipt_evidence_count,SUM(CASE WHEN kind='S' THEN 1 ELSE 0 END) AS store_evidence_count FROM field_submission_evidence GROUP BY submission_id),
          pos AS (SELECT submission_id,SUM(CASE WHEN COALESCE(no_receipt,0)=0 THEN 1 ELSE 0 END) AS bill_required_count FROM field_submission_pos GROUP BY submission_id)
          SELECT s.id,s.work_plan_item_id,s.employee_code,u.full_name,s.work_date,s.brand,s.store_code,s.store_name,s.store_note,s.status,s.return_reason,s.submitted_at,s.reviewed_at,s.reviewed_by,
            COALESCE(ev.receipt_evidence_count,0) AS receipt_evidence_count,COALESCE(ev.store_evidence_count,0) AS store_evidence_count,COALESCE(pos.bill_required_count,0) AS bill_required_count
          FROM field_submissions s LEFT JOIN app_users u ON u.employee_code=s.employee_code LEFT JOIN ev ON ev.submission_id=s.id LEFT JOIN pos ON pos.submission_id=s.id
          ${whereSql} ORDER BY s.updated_at DESC LIMIT ?`).bind(...binds,limit).all();
        return json({items:results||[]},200,{...headers,'cache-control':'no-store'});
      }
''' + text[list_end:]

detail_start = text.index('      const subDetailMatch=url.pathname.match(/^\\/api\\/admin\\/submissions\\/(\\d+)$/);')
put_start = text.index('      if(subDetailMatch && request.method==="PUT"){', detail_start)
text = text[:detail_start] + '''      const subDetailMatch=url.pathname.match(/^\\/api\\/admin\\/submissions\\/(\\d+)$/);
      if(subDetailMatch && request.method==="GET"){
        const admin=await requireAdmin(request,env); if(!admin)return unauthorized(headers); const id=Number(subDetailMatch[1]);
        const [sub,recordsResult,evidenceResult]=await Promise.all([
          env.DB.prepare(`SELECT * FROM field_submissions WHERE id=? LIMIT 1`).bind(id).first(),
          env.DB.prepare(`SELECT * FROM field_submission_pos WHERE submission_id=? ORDER BY pos_number`).bind(id).all(),
          env.DB.prepare(`SELECT id,kind,slot,object_key,content_type,size_bytes,source,captured_at,created_at,updated_at FROM field_submission_evidence WHERE submission_id=? ORDER BY CASE kind WHEN 'R' THEN 0 ELSE 1 END,slot`).bind(id).all()
        ]);
        if(!sub)return json({error:"NOT_FOUND"},404,headers);
        const evidence=(evidenceResult.results||[]).map(e=>({...e,label:e.kind==="R"?`ภาพบิล ${Number(e.slot)+1}`:`ภาพร้าน ${Number(e.slot)+1}`,url:`/api/admin/submission-evidence/${e.id}?v=${encodeURIComponent(String(e.updated_at||e.id))}`}));
        const receiptImages=evidence.filter(e=>e.kind==="R").map(e=>e.url),storeImages=evidence.filter(e=>e.kind==="S").map(e=>e.url);
        return json({submission:sub,records:recordsResult.results||[],evidence,receiptImages,storeImages,evidenceSummary:{total:evidence.length,receipt:receiptImages.length,store:storeImages.length}},200,{...headers,"cache-control":"no-store"});
      }
''' + text[put_start:]

once(
    'responseHeaders.set("cache-control","private, no-store");',
    'responseHeaders.set("cache-control","private, max-age=300, stale-while-revalidate=60");',
    'image cache',
)

TARGET.write_text(text, encoding='utf-8')
print('Round104.6 office performance patch applied:', TARGET)
