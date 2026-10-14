async page => {
 const rows=[];await page.setViewportSize({width:390,height:844});
 for(const theme of ['magazine','journal','objects'])for(const language of ['zh','ja'])for(const on of [false,true]){
  await page.goto(`http://127.0.0.1:4173/design/app.html?theme=${theme}`);
  await page.evaluate(({language,on})=>{
   book().language=language;render();
   const content=document.getElementById('content');content.style.padding='0 24px';
   content.innerHTML=[t('常用分类','よく使うカテゴリ'),t('历史补录：已包含在期初余额','履歴追加：期首残高に含まれる')].map(text=>`<label class="toggle-row"><span>${text}</span><input type="checkbox" ${on?'checked':''}><i></i></label>`).join('');
  },{language,on});
  await page.addStyleTag({content:'*,*::before,*::after{animation:none!important;transition:none!important}'});await page.evaluate(()=>document.fonts.ready);
  await page.locator('#content').screenshot({path:`android/verification/reference/toggle-${theme}-${language}-${on?'on':'off'}.png`});
  rows.push(await page.evaluate(({theme,language,on})=>({theme,language,on,nodes:[...document.querySelectorAll('.toggle-row,.toggle-row i')].map(e=>{const s=getComputedStyle(e);return{selector:e.className||e.tagName,rect:e.getBoundingClientRect().toJSON(),fontSize:s.fontSize,lineHeight:s.lineHeight,padding:s.padding};})}),{theme,language,on}));
 }
 return rows;
}
