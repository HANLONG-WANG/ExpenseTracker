async page => {
 const rows=[];
 for(const width of [390,320])for(const theme of ['magazine','journal','objects'])for(const language of ['zh','ja']){
  await page.setViewportSize({width,height:844});
  await page.goto(`http://127.0.0.1:4173/design/app.html?theme=${theme}`);
  await page.evaluate(language=>{book().language=language;render();},language);
  await page.addStyleTag({content:'*,*::before,*::after{animation:none!important;transition:none!important}'});
  await page.evaluate(()=>document.fonts.ready);
  for(const kind of ['setup','balance','welcome']){
   await page.evaluate(({kind,language})=>{
    const ja=language==='ja';
    const title=kind==='welcome'?(ja?'今日から、少し余白を。':'从今天开始，留一点余量。'):(ja?'日々に、名前を。':'先给日常，一个名字。');
    const sub=kind==='welcome'?(ja?'過去の全記録は不要。今の残高と借入から、日々を少しずつ。':'不用补齐所有过去。从现有余额和欠款开始，慢慢记下日常。'):(ja?'基準通貨は予算・分析用。口座は別の通貨も持てます。':'本位币用于预算和分析；账户仍可持有不同币种。');
    const amount=kind==='welcome'?(ja?'こんにちは、ヨハク。':'你好，余量。'):kind==='balance'?'¥10,000':'';
    const aside=kind==='welcome'?(ja?'カテゴリは空白から':'分类从空白开始'):kind==='balance'?(ja?'口座残高':'账户余额'):'';
    document.getElementById('content').innerHTML=experienceLead(kind,title,sub,kind==='welcome'?'YOUR FIRST PAGE':'FIRST STEPS / 01',amount,aside);
   },{kind,language});
   await page.screenshot({path:`android/verification/reference/lead-${theme}-${language}-${kind}-${width}.png`});
   rows.push(await page.evaluate(({width,theme,language,kind})=>({width,theme,language,kind,nodes:[...document.querySelectorAll('.exp-lead,.exp-edition,.editorial-lead-columns,.exp-hand-caption,.exp-object-label,.exp-lead h1,.exp-lead p,.exp-lead-amount,.exp-hand-answer,.exp-object-amount,.exp-margin-note,.exp-object-tag,.exp-lead aside')].map(e=>{const c=getComputedStyle(e);return{selector:e.className||e.tagName,rect:e.getBoundingClientRect().toJSON(),offsetHeight:e.offsetHeight,fontSize:c.fontSize,lineHeight:c.lineHeight,margin:c.margin,font:c.fontFamily};})}),{width,theme,language,kind}));
  }
 }
 return rows;
}
