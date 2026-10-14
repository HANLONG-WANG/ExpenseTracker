async page=>{
 const results=[];await page.setViewportSize({width:390,height:844});
 for(const theme of ['magazine','journal','objects'])for(const language of ['zh','ja']){
  await page.goto(`http://127.0.0.1:4173/design/app.html?theme=${theme}`);
  await page.evaluate(language=>{
   book().language=language;render();const a=book().accounts.find(a=>a.type==='cash'),b=book().accounts.find(a=>a.type==='bank');
   book().accounts=[a,b];a.name=a.ja=t('现金','現金');b.name=b.ja=t('银行账户','銀行口座');
   entry({type:'expense',category:book().categories[0].id,account:a.id,amount:680,date:'2026-10-14'});
   const select=document.querySelector('input[name=account]').closest('.custom-select');
   select.style.width='340px';select.querySelector('.select-trigger>span').textContent=a.name;
   select.querySelectorAll('.select-options button>span').forEach((span,i)=>span.textContent=i===0?a.name:b.name);
   document.querySelector('input[name=date]').closest('.field').style.width='340px';
  },language);
  await page.addStyleTag({content:'*,*::before,*::after{animation:none!important;transition:none!important}'});await page.evaluate(()=>document.fonts.ready);
  const select=page.locator('.custom-select').filter({has:page.locator('input[name=account]')});
  await select.screenshot({path:`android/verification/reference/select-${theme}-${language}-closed.png`});
  await page.locator('.field').filter({has:page.locator('input[name=date]')}).screenshot({path:`android/verification/reference/date-${theme}-${language}-closed.png`});
  await page.locator('.date-trigger').first().click();
  await page.evaluate(()=>document.querySelector('.theme-date-popover:not([hidden])').style.width='340px');
  await page.locator('.theme-date-popover:not([hidden])').screenshot({path:`android/verification/reference/calendar-${theme}-${language}.png`});
  
  results.push(await select.evaluate((root,info)=>({...info,nodes:[document.querySelector('.theme-date-popover:not([hidden])'),...document.querySelectorAll('.theme-date-popover:not([hidden])>.date-menu-grid')].map(e=>({selector:e.className||e.tagName,width:e.offsetWidth,height:e.offsetHeight,rect:e.getBoundingClientRect().toJSON()}))}),{theme,language}));
 }
 return results;
}
