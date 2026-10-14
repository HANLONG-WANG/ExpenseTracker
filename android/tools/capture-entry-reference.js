async page => {
 const rows=[];
 await page.setViewportSize({width:390,height:844});
 for(const theme of ['magazine','journal','objects']){
  await page.goto(`http://127.0.0.1:4173/design/app.html?theme=${theme}`);
  await page.evaluate(()=>{book().language='zh';render();const category=book().categories[0].id;const account=book().accounts.find(a=>a.type==='cash')||book().accounts[0];entry({type:'expense',category,account:account.id,amount:680,merchant:'喫茶と余白'});});
  await page.addStyleTag({content:'*,*::before,*::after{animation:none!important;transition:none!important}'});
  await page.evaluate(()=>document.fonts.ready);
  await page.screenshot({path:`android/verification/reference/entry-${theme}-390.png`});
  rows.push(await page.evaluate(theme=>({theme,nodes:[...document.querySelectorAll('.audit-entry-tools,.audit-entry-tools .audit-link,.entry-details,.entry-details summary,.field')].map(e=>{const c=getComputedStyle(e);return{selector:e.className||e.tagName,text:e.textContent.slice(0,40),fontSize:c.fontSize,lineHeight:c.lineHeight,padding:c.padding,margin:c.margin,borderStyle:c.borderBottomStyle,height:e.getBoundingClientRect().height};})}),theme));
 }
 return rows;
}
