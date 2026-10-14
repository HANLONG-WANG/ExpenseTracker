async page => {
 const rows=[];await page.setViewportSize({width:390,height:844});
 for(const theme of ['magazine','journal','objects'])for(const language of ['zh','ja']){
  await page.goto(`http://127.0.0.1:4173/design/app.html?theme=${theme}`);
  await page.evaluate(({language})=>{
   book().language=language;render();
   const r=book().records.find(r=>r.type==='expense');
   Object.assign(r,{id:'11111111-2222-4333-8444-555555555555',merchant:'喫茶と余白',date:'2026-10-14',amount:300,note:'午后咖啡 / 午後のコーヒー',attachments:[],receipts:[]});
   recordDetail(r.id);
   document.querySelector('.record-facts').innerHTML=rows([[t('账户','口座'),'随身现金'],[t('分类','カテゴリ'),'餐饮 / 咖啡'],[t('备注','メモ'),'午后咖啡 / 午後のコーヒー']]);
   document.querySelector('.record-cover').style.width='340px';document.querySelector('.record-facts').style.width='340px';
  },{language});
  await page.addStyleTag({content:'*,*::before,*::after{animation:none!important;transition:none!important}'});await page.evaluate(()=>document.fonts.ready);
  await page.locator('.record-cover').screenshot({path:`android/verification/reference/record-cover-${theme}-${language}.png`});
  rows.push(await page.evaluate(({theme,language})=>({theme,language,nodes:[...document.querySelectorAll('.record-cover,.record-cover>span:not(.capture-tape),.record-cover h3,.record-cover>div,.record-cover>small,.record-facts,.record-facts .detail-row')].map(e=>{const s=getComputedStyle(e);return {selector:e.className||e.tagName,text:e.textContent,rect:e.getBoundingClientRect().toJSON(),offsetWidth:e.offsetWidth,offsetHeight:e.offsetHeight,fontSize:s.fontSize,lineHeight:s.lineHeight,padding:s.padding,margin:s.margin,fontWeight:s.fontWeight};})}),{theme,language}));
 }
 return rows;
}
