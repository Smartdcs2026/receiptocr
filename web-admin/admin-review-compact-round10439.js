/* Round104.39 — keep the Admin shell compact on the review route only. */
(function(){
  'use strict';
  function isReview(){return String(location.hash||'').replace(/^#/,'').split('?')[0]==='review'}
  function apply(){
    const on=isReview();
    document.body.classList.toggle('reviewRoute10439',on);
    const title=document.getElementById('spaTitle');
    if(on&&title&&title.textContent!=='ศูนย์ตรวจงาน')title.textContent='ศูนย์ตรวจงาน';
  }
  window.addEventListener('hashchange',()=>requestAnimationFrame(apply));
  const root=document.getElementById('spaRoot');
  if(root)new MutationObserver(()=>requestAnimationFrame(apply)).observe(root,{childList:true,subtree:true});
  apply();
})();
