async page => {
 const rows=[];
 await page.setViewportSize({width:390,height:844});
 for(const theme of ['magazine','journal','objects'])for(const language of ['zh','ja']){
  await page.goto(`http://127.0.0.1:4173/design/app.html?theme=${theme}`);
  await page.evaluate(({language})=>{
   book().language=language;render();
   const toast=document.querySelector('.toast');
   toast.textContent=t('修改已保存到原条目','元の記録に変更を保存');toast.hidden=false;
  },{language});
  await page.addStyleTag({content:'*,*::before,*::after{animation:none!important;transition:none!important}'});
  await page.evaluate(()=>document.fonts.ready);
  await page.locator('.toast').screenshot({path:`android/verification/reference/toast-${theme}-${language}.png`});
  rows.push(await page.evaluate(({theme,language})=>{
   const e=document.querySelector('.toast'),s=getComputedStyle(e);
   return {theme,language,rect:e.getBoundingClientRect().toJSON(),offsetWidth:e.offsetWidth,offsetHeight:e.offsetHeight,padding:s.padding,fontSize:s.fontSize,lineHeight:s.lineHeight,border:s.border,before:{size:getComputedStyle(e,'::before').fontSize,lineHeight:getComputedStyle(e,'::before').lineHeight}};
  },{theme,language}));
 }
 return rows;
}
