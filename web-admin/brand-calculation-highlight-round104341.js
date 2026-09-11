(function(){
'use strict';
function mark(target){
  if(!target?.closest?.('.r34Calc'))return;
  requestAnimationFrame(()=>{
    document.querySelectorAll('.r34Calc .r34Field.active,.r34Calc .r34RankThreshold.active').forEach(x=>x.classList.remove('active'));
    const field=target.closest('.r34Field,.r34RankThreshold');
    if(field)field.classList.add('active');
  });
}
document.addEventListener('focusin',e=>mark(e.target));
document.addEventListener('change',e=>mark(e.target));
document.addEventListener('input',e=>mark(e.target));
})();
