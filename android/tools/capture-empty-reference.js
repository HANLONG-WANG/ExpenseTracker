async page => {
 const rows=[];
 await page.setViewportSize({width:390,height:844});
 for(const theme of ['magazine','journal','objects'])for(const language of ['zh','ja']){
  await page.goto(`http://127.0.0.1:4173/design/app.html?theme=${theme}`);
  await page.evaluate(({language})=>{
   book().language=language;render();
   document.getElementById('content').innerHTML=empty(t('还没有记录。创建自己的分类，开始记录日常。','記録はまだありません。自分のカテゴリを作って、日々の記録を始めましょう。'),'capture',t('记一笔','記録'));
   document.getElementById('content').style.padding='0 24px';
  },{language});
  await page.addStyleTag({content:'*,*::before,*::after{animation:none!important;transition:none!important}'});
  await page.evaluate(()=>document.fonts.ready);
  await page.locator('.theme-empty').screenshot({path:`android/verification/reference/empty-${theme}-${language}.png`});
  rows.push(await page.evaluate(({theme,language})=>({theme,language,nodes:[...document.querySelectorAll('.theme-empty,.theme-empty svg,.empty-caption,.theme-empty p,.theme-empty button')].map(e=>({selector:e.tagName,rect:e.getBoundingClientRect().toJSON(),offsetWidth:e.offsetWidth,offsetHeight:e.offsetHeight,padding:getComputedStyle(e).padding,fontSize:getComputedStyle(e).fontSize,lineHeight:getComputedStyle(e).lineHeight,margin:getComputedStyle(e).margin}))}),{theme,language}));
 }
 return rows;
}
