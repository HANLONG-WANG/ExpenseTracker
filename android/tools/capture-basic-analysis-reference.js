async page => {
 const rows=[];
 await page.setViewportSize({width:390,height:844});
 for(const theme of ['magazine','journal','objects'])for(const language of ['zh','ja']){
  await page.goto(`http://127.0.0.1:4173/design/app.html?theme=${theme}`);
  await page.evaluate(({theme,language})=>{
   book().language=language;render();
   const d={total:650,refund:0,rs:[300,200,100,50].map((amount,i)=>({amount,date:'2026-10-14',merchant:'记账 '+i})),categories:[{id:'food',name:['餐饮','餐饮'],amount:600,count:3,icon:'category'},{id:'daily',name:['日用','日用'],amount:50,count:1,icon:'category'}],merchants:[]};
   document.getElementById('content').innerHTML=analysisDesign[theme](d);
  },{theme,language});
  await page.addStyleTag({content:'*,*::before,*::after{animation:none!important;transition:none!important}'});
  await page.evaluate(()=>document.fonts.ready);
  await page.screenshot({path:`android/verification/reference/basic-analysis-${theme}-${language}.png`,fullPage:true});
  rows.push(await page.evaluate(({theme,language})=>({theme,language,nodes:[...document.querySelectorAll('.editorial-spend-lead,.notebook-tally,.spending-tray,.editorial-category,.notebook-category,.category-pocket,.editorial-spend-lead h2,.editorial-total,.notebook-total,.handwritten-label,.tally-stamp,.tray-total,.tray-label,.tray-caption,.editorial-category-name strong,.editorial-category-name small,.editorial-category-value,.notebook-category strong,.notebook-category small,.ink-amount,.category-pocket strong,.pocket-amount,.category-pocket small')].map(e=>({selector:e.className,rect:e.getBoundingClientRect().toJSON(),padding:getComputedStyle(e).padding,fontSize:getComputedStyle(e).fontSize,lineHeight:getComputedStyle(e).lineHeight,margin:getComputedStyle(e).margin}))}),{theme,language}));
 }
 return rows;
}
