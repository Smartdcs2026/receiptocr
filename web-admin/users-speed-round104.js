/* Round104.6: avoid one work-plan request per field user. */
(()=>{
  if(!window.AdminAuth)return;
  const original=AdminAuth.json.bind(AdminAuth);
  let plans=new Map();

  AdminAuth.json=async function(path,options={}){
    const method=String(options?.method||'GET').toUpperCase();
    const p=String(path||'');
    if(method==='GET'&&p==='/api/users'){
      const data=await original('/api/users?withPlan=1',options);
      plans=new Map((data.items||[]).map(u=>[String(u.employee_code||''),u.plan||null]));
      return data;
    }
    const m=p.match(/^\/api\/users\/([^/]+)\/work-plan-summary$/);
    if(method==='GET'&&m){
      const code=decodeURIComponent(m[1]);
      if(plans.has(code))return plans.get(code)||{employeeCode:code,itemCount:0,dayCount:0,firstDate:null,lastDate:null,recentImports:[]};
    }
    return original(path,options);
  };
})();