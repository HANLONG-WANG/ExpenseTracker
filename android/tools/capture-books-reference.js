async page => {
 const rows=[];await page.setViewportSize({width:390,height:844});
 for(const theme of ['magazine','journal','objects'])for(const language of ['zh','ja']){
  await page.goto(`http://127.0.0.1:4173/design/app.html?theme=${theme}`);
  await page.evaluate(({theme,language})=>{
   const a=seedBook('book-a','生活',52500),b=seedBook('book-b','別の生活',123.45);
   Object.assign(a,{ja:a.name,language,theme,baseCurrency:'JPY',accounts:[a.accounts[0]]});
   Object.assign(b,{ja:b.name,language:'ja',theme:'journal',baseCurrency:'CNY',accounts:[],records:[]});
   books.splice(0,books.length,a,b);currentBook=a.id;render();navigate('books','',false);
   // Actual budget currency is per book; the old demo yen() uses the active book.
   const cards=[...document.querySelectorAll('.book-card')];
   cards[0].querySelector('small').textContent=t('1 个账户 · 月预算 ¥52,500','1 口座 · 月予算 ¥52,500');
   cards[1].querySelector('small').textContent=t('0 个账户 · 月预算 CN¥123.45','0 口座 · 月予算 CN¥123.45');
  },{theme,language});
  await page.addStyleTag({content:'*,*::before,*::after{animation:none!important;transition:none!important}'});await page.evaluate(()=>document.fonts.ready);
  await page.screenshot({path:`android/verification/reference/books-${theme}-${language}.png`,fullPage:true});
  await page.evaluate(()=>document.querySelectorAll('.audit-book-details').forEach(e=>e.style.transform='none'));
  rows.push(await page.evaluate(({theme,language})=>({theme,language,note:(()=>{const e=document.querySelector('[data-action="new-book"]').nextElementSibling,s=getComputedStyle(e);return{font:s.font,margin:s.margin,height:e.getBoundingClientRect().height}})(),blocks:(()=>{const origin=document.querySelector('.page-title h1').getBoundingClientRect().top;return [['heading',document.querySelector('.page-title h1')],['first',document.querySelectorAll('.book-card')[0]],['second',document.querySelectorAll('.book-card')[1]],['add',document.querySelector('[data-action="new-book"]')],['note',document.querySelector('[data-action="new-book"]').nextElementSibling],['details',document.querySelector('.audit-book-details')]].map(([key,e])=>({key,top:e.getBoundingClientRect().top-origin,height:e.getBoundingClientRect().height}));})(),nodes:[...document.querySelectorAll('.book-card,.book-symbol,.book-card strong,.book-card small,.audit-book-details,.audit-book-details .audit-panel-title,.audit-book-details .audit-link')].map(e=>{const s=getComputedStyle(e);return {selector:e.className||e.tagName,text:e.textContent,rect:e.getBoundingClientRect().toJSON(),offsetWidth:e.offsetWidth,offsetHeight:e.offsetHeight,fontSize:s.fontSize,lineHeight:s.lineHeight,padding:s.padding,margin:s.margin};})}),{theme,language}));
 }
 return rows;
}
