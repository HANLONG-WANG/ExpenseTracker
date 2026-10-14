async page=>{
 const rows=[];await page.setViewportSize({width:390,height:844});
 for(const theme of ['magazine','journal','objects'])for(const language of ['zh','ja'])for(const link of [false,true]){
  await page.goto(`http://127.0.0.1:4173/design/app.html?theme=${theme}`);
  await page.evaluate(({language,link})=>{book().language=language;render();document.querySelector('main').innerHTML=`<div id="section-fixture" style="width:340px"><div id="before" style="height:20px"></div>${section(t('持有的资金','持っている資金'),'AVAILABLE FUNDS',link?'ledger':'',t('全部流水','すべて'))}<div id="after" style="height:20px"></div></div>`;},{language,link});
  await page.addStyleTag({content:'*,*::before,*::after{animation:none!important;transition:none!important}'});
  await page.evaluate(()=>document.fonts.ready);
  rows.push(await page.evaluate(({theme,language,link})=>{
   const before=document.querySelector('#before').getBoundingClientRect(),after=document.querySelector('#after').getBoundingClientRect(),h=document.querySelector('.section-head').getBoundingClientRect();
   return {theme,language,link,beforeGap:h.top-before.bottom,height:h.height,afterGap:after.top-h.bottom,nodes:[...document.querySelectorAll('.section-head,.section-head>*')].map(e=>({tag:e.tagName,rect:e.getBoundingClientRect().toJSON()}))};
  },{theme,language,link}));
  await page.locator('#section-fixture').screenshot({path:`android/verification/reference/section-${theme}-${language}-${link}.png`});
 }
 return rows;
}
