async page=>{
 const rows=[];await page.setViewportSize({width:390,height:844});
 for(const theme of ['magazine','journal','objects'])for(const language of ['zh','ja'])for(const mode of ['closed','expanded','empty']){
  await page.goto(`http://127.0.0.1:4173/design/ledger.html?theme=${theme}&language=${language}&expanded=${mode==='expanded'}&empty=${mode==='empty'}`);
  const f=page.frames().find(f=>f.url().includes('/design/app.html'));
  await f.waitForSelector('#ledger-list');
  await f.evaluate(({mode})=>{book().currency='JPY';book().accounts=[{id:'cash',name:'现金',ja:'现金',type:'cash',currency:'JPY',balance:10000}];book().categories=[{id:'food',name:['餐饮','餐饮'],icon:'bag'}];book().records=mode==='empty'?[]:[{id:'record',type:'expense',account:'cash',category:'food',amount:300,date:'2026-10-14',merchant:'喫茶と余白'}];render();navigate('ledger');document.querySelectorAll('.theme-empty').forEach(e=>e.style.transform='none');},{mode});
  await f.evaluate(()=>document.fonts.ready);await f.evaluate(()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r))));
  rows.push(await f.evaluate(({theme,language,mode})=>{const origin=document.querySelector('.page-title h1').getBoundingClientRect().top;const nodes=[['heading',document.querySelector('.page-title h1')],['actions',document.querySelector('.pill-row')],['search',document.querySelector('.search-field')],['type',document.querySelector('.custom-select')],['filters',document.querySelector('.entry-details')],['records',document.querySelector('#ledger-list')]];return{theme,language,mode,nodes:nodes.map(([key,e])=>{const r=e.getBoundingClientRect();return{key,top:r.top-origin,height:r.height};})};},{theme,language,mode}));
  await page.screenshot({path:`android/verification/reference/ledger-${theme}-${language}-${mode}.png`});
 }
 return rows;
}
