async page=>{
 const rows=[];await page.setViewportSize({width:390,height:844});
 for(const theme of ['magazine','journal','objects'])for(const language of ['zh','ja']){
  await page.goto(`http://127.0.0.1:4173/design/app.html?theme=${theme}`);
  await page.evaluate(language=>{book().language=language;render();navigate('accounts','',false);},language);
  await page.addStyleTag({content:'*,*::before,*::after{animation:none!important;transition:none!important}'});
  await page.evaluate(()=>document.fonts.ready);
  rows.push(await page.evaluate(({theme,language})=>{
   const list=document.querySelector('.exp-account-list');list.style.width='340px';
   const cards=[...list.children].slice(0,2).map(e=>{const transform=e.style.transform;e.style.setProperty('transform','none','important');const r=e.getBoundingClientRect();const c=getComputedStyle(e);const value={height:r.height,marginTop:parseFloat(c.marginTop),padding:c.padding,border:c.border};e.style.transform=transform;return value;});
   return {theme,language,gap:parseFloat(getComputedStyle(list).rowGap),cards};
  },{theme,language}));
  await page.locator('.exp-account-list').first().screenshot({path:`android/verification/reference/account-list-${theme}-${language}.png`});
 }
 return rows;
}
