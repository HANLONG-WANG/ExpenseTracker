// Serve the unchanged verified frozen v55 root on 127.0.0.1:8770 before running.
async page => {
 await page.setViewportSize({width:390,height:844});
 for(const theme of ['magazine','journal','objects'])for(const language of ['zh','ja']){
 await page.goto(`http://127.0.0.1:8770/design/app.html?theme=${theme}`);
 await page.evaluate(language=>{book().language=language;book().name='通知 / お知らせ';book().ja='通知 / お知らせ';navigate('notification-settings','',false)},language);
 await page.evaluate(()=>document.fonts.ready);
 await page.locator('#content').screenshot({path:`android/verification/phase-e-notification-reference/${theme}-${language}.png`});
 }
 return 'captured six frozen notification settings references';
}