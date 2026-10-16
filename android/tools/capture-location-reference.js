// Serve the unchanged verified frozen v55 root on 127.0.0.1:8770.
async page => {
  const rows=[];
  await page.setViewportSize({width:390,height:844});
  for(const theme of ['magazine','journal','objects'])for(const language of ['zh','ja']) {
    await page.goto(`http://127.0.0.1:8770/design/app.html?theme=${theme}`);
    await page.evaluate(language=>{
      book().language=language;
      book().merchants=[{id:'location-reference',name:'喫茶 原名',place:'店頭',ja:'店頭'}];
      render();entry({type:'expense',category:book().categories[0].id,account:book().accounts[0].id,amount:500,merchant:'喫茶 原名'});
      const panel=document.querySelector('.location-candidates');
      panel.querySelector('[data-action="location-state"]')?.remove();
      panel.querySelector('span').textContent=t('附近常去商户','近くのいつものお店');
      panel.querySelector('.audit-note')?.remove();
    },language);
    await page.evaluate(()=>document.fonts.ready);
    await page.locator('.location-candidates').screenshot({path:`android/verification/phase-e-location-reference/${theme}-${language}-candidates.png`});
    rows.push({theme,language,nodes:await page.locator('.location-candidates').evaluate(e=>[e,...e.querySelectorAll('.merchant-chip,.merchant-chip small')].map(n=>{const s=getComputedStyle(n);return{text:n.textContent,rect:n.getBoundingClientRect().toJSON(),font:s.fontSize,line:s.lineHeight,padding:s.padding,border:s.border,bg:s.backgroundColor,radius:s.borderRadius}}))});
  }
  return rows;
}
