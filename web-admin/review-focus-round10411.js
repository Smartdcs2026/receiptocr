/* Round104.11 — compact filter drawer. No page-wide MutationObserver. */
(()=>{
  const root=document.querySelector('.reviewPage');
  const bar=document.querySelector('.reviewCommandBar');
  const filters=document.querySelector('.reviewFilters');
  if(!root||!bar||!filters)return;

  root.classList.add('reviewFocus10411');

  let toggle=bar.querySelector('.reviewFilterToggle');
  if(!toggle){
    toggle=document.createElement('button');
    toggle.type='button';
    toggle.className='reviewFilterToggle';
    toggle.id='reviewFilterToggle';
    toggle.textContent='ค้นหา / กรอง';
    toggle.setAttribute('aria-expanded','false');
    toggle.setAttribute('aria-controls','reviewFilters');
    filters.id=filters.id||'reviewFilters';
    bar.insertBefore(toggle,filters);
  }

  let hint=bar.querySelector('.reviewFocusHint');
  if(!hint){
    hint=document.createElement('span');
    hint.className='reviewFocusHint';
    bar.insertBefore(hint,bar.querySelector('.reviewLiveMeta'));
  }

  const select=id=>document.getElementById(id);
  function textOf(sel){
    if(!sel)return '';
    const opt=sel.selectedOptions?.[0];
    return String(opt?.textContent||'').trim();
  }
  function updateHint(){
    const status=textOf(select('reviewStatus'))||'ทั้งหมด';
    const employee=textOf(select('reviewEmployee'))||'พนักงานทุกคน';
    const term=String(select('reviewSearch')?.value||'').trim();
    const parts=[status,employee];
    if(term)parts.push(`ค้นหา: ${term}`);
    hint.textContent=parts.join(' · ');
  }
  function close(){root.classList.remove('filters-open');toggle.setAttribute('aria-expanded','false');}
  function open(){root.classList.add('filters-open');toggle.setAttribute('aria-expanded','true');requestAnimationFrame(()=>select('reviewSearch')?.focus());}
  function toggleDrawer(){root.classList.contains('filters-open')?close():open();}

  toggle.addEventListener('click',e=>{e.stopPropagation();toggleDrawer();});
  filters.addEventListener('click',e=>e.stopPropagation());
  document.addEventListener('click',e=>{if(root.classList.contains('filters-open')&&!e.target.closest('.reviewCommandBar'))close();});
  document.addEventListener('keydown',e=>{if(e.key==='Escape'&&root.classList.contains('filters-open')){close();toggle.focus();}});

  ['reviewStatus','reviewEmployee','reviewSort'].forEach(id=>select(id)?.addEventListener('change',()=>{updateHint();close();}));
  select('reviewSearch')?.addEventListener('input',updateHint);
  select('refreshReview')?.addEventListener('click',close);
  select('reviewNotify')?.addEventListener('click',close);

  updateHint();
})();
