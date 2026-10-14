async page=>{const rows=[];await page.setViewportSize({width:390,height:844});for(const theme of ['magazine','journal','objects'])for(const language of ['zh','ja']){
 await page.goto(`http://127.0.0.1:4173/design/lifecycle.html?theme=${theme}&language=${language}`);await page.evaluate(()=>document.fonts.ready);
 rows.push(await page.evaluate(({theme,language})=>({theme,language,rows:[...document.querySelectorAll('.impact-fact')].map(e=>({height:e.getBoundingClientRect().height,padding:getComputedStyle(e).padding,border:getComputedStyle(e).border,text:e.textContent}))}),{theme,language}));
 await page.screenshot({path:`android/verification/reference/impact-${theme}-${language}.png`});}return rows;}
