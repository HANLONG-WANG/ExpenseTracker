async page => {
 const rows=[];await page.setViewportSize({width:390,height:844});
 for(const theme of ['magazine','journal','objects'])for(const language of ['zh','ja']){
  await page.goto(`http://127.0.0.1:4173/design/app.html?theme=${theme}`);
  await page.evaluate(language=>{book().language=language;render();navigate('settings','',false)},language);
  await page.addStyleTag({content:'*,*::before,*::after{animation:none!important;transition:none!important}'});await page.evaluate(()=>document.fonts.ready);
  await page.locator('.theme-option').first().evaluate(e=>e.parentElement.scrollIntoView());
  await page.locator('.theme-option').first().locator('..').screenshot({path:`android/verification/reference/settings-theme-${theme}-${language}.png`});
  rows.push(await page.evaluate(({theme,language})=>({theme,language,nodes:[...document.querySelectorAll('.theme-option,.theme-option strong,.theme-option small,.theme-preview,.panel h3')].slice(0,14).map(e=>{let s=getComputedStyle(e);return{selector:e.className||e.tagName,text:e.textContent,rect:e.getBoundingClientRect().toJSON(),lineHeight:s.lineHeight,fontSize:s.fontSize,fontWeight:s.fontWeight,padding:s.padding,margin:s.margin}})}),{theme,language}));
 }
 return rows;
}
