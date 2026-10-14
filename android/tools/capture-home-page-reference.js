async page=>{
 const rows=[];await page.setViewportSize({width:390,height:844});
 for(const theme of ['magazine','journal','objects'])for(const language of ['zh','ja'])for(const mode of ['budget','fresh']){
  await page.goto(`http://127.0.0.1:4173/design/app.html?theme=${theme}`);
  await page.evaluate(({language,mode})=>{
   const b=book();b.language=language;b.baseCurrency='JPY';b.name=b.ja='生活';b.budget=mode==='budget'?52500:0;b.activities=[];b.recurring=[];b.expected=[];b.accounts=mode==='budget'?[{id:'cash',type:'cash',name:'现金',ja:'现金',balance:9700,currency:'JPY'}]:[];b.categories=[{id:'food',name:['餐饮','餐饮'],icon:'bag'}];b.records=mode==='budget'?[{id:'record',type:'expense',amount:300,baseAmount:300,account:'cash',category:'food',merchant:'喫茶と余白',date:'2026-10-14'}]:[];
   render();navigate('home');
   const due=document.querySelector('.exp-due-list');due.previousElementSibling.remove();due.remove();document.querySelector('.exp-home-shortcuts').remove();
   document.querySelector('.home-cash-note>span').textContent=t('资金账户','資金口座');document.querySelector('.home-cash-note strong').textContent=mode==='budget'?'¥9,700':'—';document.querySelector('.home-cash-note small').textContent=t('账户按原币分别显示，信用额度不计入。','口座は通貨ごとに表示。利用枠は含みません。');
   document.querySelectorAll('.audit-daily .audit-link').forEach(e=>e.remove());
   const stats=document.querySelectorAll('.audit-three-stats b');stats[0].innerHTML='18<small>'+t('天','日')+'</small>';stats[1].textContent=mode==='budget'?'¥2,900':'—';stats[2].textContent=mode==='budget'?'¥300':'¥0';
   document.querySelectorAll('.exp-home-cover,.exp-lead,.home-cash-note,.audit-daily,.theme-empty').forEach(e=>e.style.transform='none');
  },{language,mode});
  await page.evaluate(()=>document.fonts.ready);await page.evaluate(()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r))));
  rows.push(await page.evaluate(({theme,language,mode})=>{const cover=document.querySelector('.exp-home-cover,.exp-lead'),origin=cover.getBoundingClientRect().top;const headings=[...document.querySelectorAll('.section-head')];const nodes=[['cover',cover],...(mode==='fresh'?[['set-budget',document.querySelector('[data-action="edit-budget"]')]]:[]),['context',document.querySelector('.home-spend-context')],['funds-heading',headings[0]],['funds',document.querySelector('.home-cash-note')],['recent-heading',headings[1]],['records',document.querySelector('.transaction-list')],['daily',document.querySelector('.audit-daily')]];return{theme,language,mode,nodes:nodes.map(([key,e])=>{const r=e.getBoundingClientRect();return{key,top:r.top-origin,height:r.height}})};},{theme,language,mode}));
  await page.screenshot({path:`android/verification/reference/home-${theme}-${language}-${mode}.png`,fullPage:true});
 }
 return rows;
}
