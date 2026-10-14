async page=>{
 const rows=[];await page.setViewportSize({width:390,height:844});
 for(const theme of ['magazine','journal','objects'])for(const language of ['zh','ja'])for(const expanded of [false,true]){
  await page.goto(`http://127.0.0.1:4173/design/app.html?theme=${theme}`);
  await page.evaluate(({language,expanded})=>{
   book().language=language;book().name=book().ja='生活';book().startDate='2026-10-01';render();
   const cash=book().accounts.find(a=>a.type==='cash');cash.name=cash.ja='现金';
   entry({type:'expense',category:book().categories[0].id,account:cash.id,amount:680,merchant:'喫茶と余白',date:'2026-10-14'});
   document.querySelector('.capture-entry-summary strong').textContent='餐饮 / 咖啡';
   document.querySelectorAll('.original-currency-control,.currency-entry-note,.location-candidates,.capture-material-section,[data-action="manual-rate"],.disclaimer').forEach(e=>e.remove());
   const details=document.querySelector('.entry-details');details.open=expanded;
   details.querySelector('summary').textContent=t('备注','メモ');
   for(const name of ['activity','payment'])details.querySelector(`[name="${name}"]`)?.closest('.field')?.remove();
  },{language,expanded});
  await page.addStyleTag({content:'*,*::before,*::after{animation:none!important;transition:none!important}'});await page.evaluate(()=>document.fonts.ready);
  rows.push(await page.evaluate(({theme,language,expanded})=>{
   const summary=document.querySelector('.capture-entry-summary');const start=summary.getBoundingClientRect().top;
   const field=n=>document.querySelector(`[name="${n}"]`).closest('.field');
   const entries={summary,tools:document.querySelector('.audit-entry-tools'),amount:field('amount'),quick:document.querySelector('.audit-quick-amount'),base:document.querySelector('.entry-base-caption'),account:field('account'),merchant:field('merchant'),date:field('date'),notes:document.querySelector('.entry-details'),historical:document.querySelector('.exp-toggle'),save:document.querySelector('.capture-save-area>button')};
   return{theme,language,expanded,nodes:Object.entries(entries).map(([key,e])=>{const r=e.getBoundingClientRect(),s=getComputedStyle(e);return{key,top:r.top-start,height:r.height,margin:s.margin,padding:s.padding}})};
  },{theme,language,expanded}));
  await page.evaluate(()=>{document.querySelector('.sheet').scrollTop=0;});
  await page.screenshot({path:`android/verification/reference/entry-page-${theme}-${language}-${expanded}-top.png`});
  await page.evaluate(()=>{const sheet=document.querySelector('.sheet');sheet.scrollTop=sheet.scrollHeight;});
  await page.screenshot({path:`android/verification/reference/entry-page-${theme}-${language}-${expanded}-bottom.png`});
 }
 return rows;
}