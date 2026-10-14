async page => {
  const rows=[];
  await page.setViewportSize({width:390,height:844});
  for(const theme of ['magazine','journal','objects'])for(const language of ['zh','ja']){
    await page.goto(`http://127.0.0.1:4173/design/app.html?theme=${theme}`);
    await page.evaluate(language=>{
      book().language=language;render();
      openSheet(t('账户与期初信息','口座・期首情報'),'<div style="height:1200px"></div>');
    },language);
    await page.addStyleTag({content:'*,*::before,*::after{animation:none!important;transition:none!important}'});
    await page.evaluate(()=>document.fonts.ready);
    rows.push(await page.evaluate(({theme,language})=>{
      const sheet=document.querySelector('.sheet'),header=document.querySelector('.sheet-top'),grip=document.querySelector('.sheet-grip');
      const read=()=>({scroll:sheet.scrollTop,headerOffset:header.getBoundingClientRect().top-sheet.getBoundingClientRect().top,gripOffset:grip.getBoundingClientRect().top-sheet.getBoundingClientRect().top});
      const initial=read();sheet.scrollTop=sheet.scrollHeight;
      return {theme,language,initial,scrolled:read()};
    },{theme,language}));
    await page.screenshot({path:`android/verification/reference/sheet-scroll-${theme}-${language}.png`});
  }
  return rows;
}
