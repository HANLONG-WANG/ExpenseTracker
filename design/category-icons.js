/* Category glyphs and picker shared by the prototype and its comparison page. */
const categoryIcons=(()=>{
const ICONS=new Map(),GROUP_OF=new Map();
CATEGORY_ICON_GROUPS.forEach(g=>g.icons.forEach(i=>{ICONS.set(i.id,i);GROUP_OF.set(i.id,g.id);}));
const DEFAULT_ICON='category';

/* Symbols live next to the prototype's own icons, so icon('ci-…') works everywhere. */
const defs=document.querySelector('.svg-defs defs'),NS='http://www.w3.org/2000/svg';
ICONS.forEach(i=>{const s=document.createElementNS(NS,'symbol');s.id='i-ci-'+i.id;s.setAttribute('viewBox','0 0 24 24');i.paths.forEach(d=>{const p=document.createElementNS(NS,'path');p.setAttribute('d',d);s.appendChild(p);});defs.appendChild(s);});

const all=()=>[...book().categories,...(book().incomeCategories||[])];
const find=id=>all().find(c=>c.id===id);
const resolved=c=>[c?.icon,find(c?.parent)?.icon].find(id=>ICONS.has(id))||DEFAULT_ICON;
const iconLabel=id=>{const i=ICONS.get(id);return i?t(i.zh,i.ja):'';};
/* A dedicated tag keeps the prototype's generic span/i rules off the badge. */
const badge=(id,extra='ci-row')=>`<y-ci class="ci ci-g-${GROUP_OF.get(id)} ${extra}">${icon('ci-'+id)}</y-ci>`;

/* Suggest by name: zh/ja labels and keywords. */
function suggest(name){
 const q=(name||'').trim().toLowerCase();if(!q)return [];
 const scored=[];
 ICONS.forEach(i=>{
  const tokens=[...i.zh.split(' / '),...i.ja.split('・'),...i.kw.split(' ')].map(s=>s.toLowerCase()).filter(Boolean);
  let score=0;tokens.forEach(tok=>{if(tok===q)score=Math.max(score,100);else if(q.includes(tok))score=Math.max(score,20+tok.length);else if(q.length>=2&&tok.includes(q))score=Math.max(score,10);});
  if(score)scored.push([score,i.id]);
 });
 return scored.sort((a,b)=>b[0]-a[0]).slice(0,6).map(s=>s[1]);
}
function searchIcons(q){
 q=q.trim().toLowerCase();if(!q)return null;
 return [...ICONS.values()].filter(i=>[i.id,i.zh,i.ja,i.kw].join(' ').toLowerCase().includes(q)).map(i=>i.id);
}

/* Editor state survives the trip to the icon sheet and back. */
let edit=null;
function editorIconBlock(){
 const parent=edit.parent?find(edit.parent):null;
 const shown=chosen()||(parent?resolved(parent):null)||DEFAULT_ICON;
 const note=chosen()?(edit.picked?t('已自选','自分で選択'):t('按名称推荐','名前からおすすめ')):parent?t(`沿用一级「${categoryName(parent)}」`,`親「${categoryName(parent)}」と同じ`):t('通用图标','標準アイコン');
 const tips=suggest(edit.name).filter(id=>id!==shown).slice(0,5);
 return `<div class="ci-field" data-ci-field><span class="ci-field-label">${t('分类图标','カテゴリのアイコン')}</span>
  <button type="button" class="ci-field-current" data-ci-open>${badge(shown,'ci-lg')}<span><strong>${esc(iconLabel(shown))}</strong><small>${note}</small></span><i>${t('更换','変更')} ↗</i></button>
  ${tips.length?`<div class="ci-suggest"><small>${t('按名称推荐','名前からおすすめ')}</small><div>${tips.map(id=>`<button type="button" data-ci-pick="${id}" aria-label="${esc(iconLabel(id))}">${badge(id)}</button>`).join('')}</div></div>`:''}</div>`;
}
function renderEditorIcon(){const box=document.querySelector('[data-ci-field]');if(box)box.outerHTML=editorIconBlock();}
function chosen(){const parent=edit.parent?resolved(find(edit.parent)):null;return edit.picked?edit.icon:(suggest(edit.name)[0]===parent?null:suggest(edit.name)[0]||null);}
function beginEditor(id,parent,capture,keep,income){
 const c=find(id);
 if(!keep)edit={id,capture,income,parent:c?.parent||parent||'',name:c?categoryName(c):'',icon:c?.icon||null,picked:!!c};
 return edit;
}
document.addEventListener('input',e=>{
 if(e.target.matches('#hierarchy-category-form input[name=name]')&&edit){edit.name=e.target.value;renderEditorIcon();}
 if(e.target.matches('[data-ci-search]'))filterSheet(e.target.value);
});
document.addEventListener('change',e=>{if(e.target.matches('#hierarchy-category-form select[name=parent]')&&edit){edit.parent=e.target.value;renderEditorIcon();}});

/* Icon sheet: one group per tab; the tab holding the current pick carries a dot. */
let pending=null,activeTab=null;
const cell=id=>`<button type="button" class="ci-cell${id===pending?' selected':''}" data-ci-cell="${id}" aria-label="${esc(iconLabel(id))}" aria-pressed="${id===pending}">${badge(id,'ci-cell-badge')}</button>`;
function iconSheet(){
 pending=chosen()||(edit.parent&&resolved(find(edit.parent)))||DEFAULT_ICON;
 const tips=suggest(edit.name),parent=edit.parent?find(edit.parent):null;
 const tabs=[...(tips.length?[{id:'tips',zh:'推荐',ja:'おすすめ',icons:tips.map(id=>ICONS.get(id))}]:[]),...CATEGORY_ICON_GROUPS];
 activeTab=GROUP_OF.get(pending);
 openSheet(t('选择分类图标','アイコンを選ぶ'),`<div class="ci-sheet">
  <div class="ci-preview">${badge(pending,'ci-xl')}<div><span>${esc(edit.name||t('新分类','新しいカテゴリ'))}</span><strong data-ci-name>${esc(iconLabel(pending))}</strong><small>${t('显示于记账、流水、分析与分类管理','記録・明細・分析・管理に表示')}</small></div></div>
  <div class="ci-links"><button type="button" class="text-link" data-ci-back>← ${t('返回编辑','編集に戻る')}</button>${parent?`<button type="button" class="text-link" data-ci-inherit>${t(`沿用一级「${categoryName(parent)}」`,`親「${categoryName(parent)}」と同じ`)}</button>`:''}</div>
  <label class="hierarchy-search ci-search">${icon('search')}<input type="search" data-ci-search placeholder="${t('搜索图标：咖啡、電車、gift…','検索：カフェ、電車、gift…')}" aria-label="${t('搜索图标','アイコンを検索')}"></label>
  <div class="ci-tabs" role="tablist">${tabs.map(g=>`<button type="button" role="tab" id="ci-tab-${g.id}" data-ci-tab="${g.id}" aria-controls="ci-panel-${g.id}">${g.id==='tips'?'★ ':''}${t(g.zh,g.ja)}</button>`).join('')}</div>
  ${tabs.map(g=>`<section class="ci-group" role="tabpanel" id="ci-panel-${g.id}" aria-labelledby="ci-tab-${g.id}" data-ci-panel="${g.id}"><p class="ci-caption">${g.id==='tips'?t(`按「${esc(edit.name)}」推荐`,`「${esc(edit.name)}」のおすすめ`):t(g.zh,g.ja)}<small>${g.icons.length}</small></p><div class="ci-grid">${g.icons.map(i=>cell(i.id)).join('')}</div></section>`).join('')}
  <section class="ci-group" data-ci-results hidden><p class="ci-caption">${t('搜索结果','検索結果')}<small data-ci-count></small></p><div class="ci-grid"></div><p class="hierarchy-no-results" hidden>${t('没有匹配的图标','該当するアイコンがありません')}</p></section>
  <div class="ci-actions"><button type="button" class="full-button" data-ci-use>${t('使用这个图标','このアイコンを使う')}</button></div>
  <p class="ci-credit">Icons · ${CATEGORY_ICON_SOURCE} · MIT</p></div>`);
 showTab(activeTab);
}
function showTab(id){
 activeTab=id;
 document.querySelectorAll('[data-ci-tab]').forEach(b=>{const on=b.dataset.ciTab===id;b.setAttribute('aria-selected',on);b.tabIndex=on?0:-1;if(on)b.scrollIntoView({block:'nearest',inline:'center'});});
 document.querySelectorAll('[data-ci-panel]').forEach(p=>p.hidden=p.dataset.ciPanel!==id);
 markPickedTab();
 // Once the tab bar is stuck, a new tab starts at the top of its own grid.
 const bar=document.querySelector('.ci-tabs'),sheet=document.querySelector('.sheet'),panel=document.querySelector(`[data-ci-panel="${id}"]`);
 if(bar.getBoundingClientRect().top-sheet.getBoundingClientRect().top<=parseFloat(getComputedStyle(bar).top)+1)sheet.scrollTop+=panel.getBoundingClientRect().top-bar.getBoundingClientRect().bottom;
}
function markPickedTab(){
 const home=GROUP_OF.get(pending);
 document.querySelectorAll('[data-ci-tab]').forEach(b=>b.classList.toggle('has-pick',b.dataset.ciTab===home||(b.dataset.ciTab==='tips'&&!!document.querySelector(`[data-ci-panel="tips"] [data-ci-cell="${pending}"]`))));
}
function filterSheet(q){
 const ids=searchIcons(q),box=document.querySelector('[data-ci-results]');
 document.querySelector('.ci-tabs').hidden=!!ids;box.hidden=!ids;
 document.querySelectorAll('[data-ci-panel]').forEach(p=>p.hidden=!!ids||p.dataset.ciPanel!==activeTab);
 if(!ids)return;
 box.querySelector('.ci-grid').innerHTML=ids.map(cell).join('');
 box.querySelector('[data-ci-count]').textContent=ids.length;
 box.querySelector('.hierarchy-no-results').hidden=ids.length>0;
}
document.addEventListener('keydown',e=>{
 const tab=e.target.closest('[data-ci-tab]');if(!tab||!['ArrowLeft','ArrowRight'].includes(e.key))return;
 const all=[...document.querySelectorAll('[data-ci-tab]')],next=all[(all.indexOf(tab)+(e.key==='ArrowRight'?1:all.length-1))%all.length];
 showTab(next.dataset.ciTab);next.focus();e.preventDefault();
});
document.addEventListener('click',e=>{
 const el=e.target.closest('[data-ci-open],[data-ci-pick],[data-ci-cell],[data-ci-use],[data-ci-back],[data-ci-inherit],[data-ci-tab]');if(!el)return;
 e.preventDefault();e.stopPropagation();
 if(el.matches('[data-ci-open]')){edit.name=document.querySelector('#hierarchy-category-form input[name=name]').value;iconSheet();}
 else if(el.matches('[data-ci-pick]')){edit.icon=el.dataset.ciPick;edit.picked=true;renderEditorIcon();}
 else if(el.matches('[data-ci-cell]')){pending=el.dataset.ciCell;document.querySelectorAll('[data-ci-cell]').forEach(b=>{const on=b.dataset.ciCell===pending;b.classList.toggle('selected',on);b.setAttribute('aria-pressed',on);});
  document.querySelector('.ci-preview .ci').outerHTML=badge(pending,'ci-xl');document.querySelector('[data-ci-name]').textContent=iconLabel(pending);markPickedTab();}
 else if(el.matches('[data-ci-use]')){edit.icon=pending;edit.picked=true;categoryEditor(edit.id,edit.parent,edit.capture,true,edit.income);}
 else if(el.matches('[data-ci-inherit]')){edit.icon=null;edit.picked=true;categoryEditor(edit.id,edit.parent,edit.capture,true,edit.income);}
 else if(el.matches('[data-ci-back]'))categoryEditor(edit.id,edit.parent,edit.capture,true,edit.income);
 else if(el.matches('[data-ci-tab]'))showTab(el.dataset.ciTab);
},true);

/* States driven by category-icons.html. */
window.categoryIconState=(state,language)=>{
 books.forEach(b=>b.language=language);lang=language;closeSheet();
 if(state==='picker'){navigate('home');beginCategorySelection({type:'expense'});document.querySelectorAll('.hierarchy-group').forEach((g,i)=>{if(i<2)g.open=true;});}
 else if(state==='manage')navigate('categories');
 else if(state==='editor'){navigate('categories');categoryEditor('food-coffee','food');}
 else if(state==='create'){navigate('categories');categoryEditor('','');const n=document.querySelector('#hierarchy-category-form input[name=name]');n.value=t('猫咪','ネコ');n.dispatchEvent(new Event('input',{bubbles:true}));}
 else if(state==='sheet'){navigate('categories');categoryEditor('food-coffee','food');iconSheet();}
 else if(state==='ledger')navigate('ledger');
 else if(state==='analysis'){analysisPreview=true;navigate('analysis');}
 else if(state==='budget'){analysisTab='budget';navigate('analysis');}
 document.getElementById('content').scrollTop=0;
};
return {resolved,badge,suggest,beginEditor,editorIconBlock,chosen,find,iconLabel};
})();

function categoryBadge(c,size='row'){return categoryIcons.badge(categoryIcons.resolved(c),'ci-'+size);}
function categoryGlyph(c,extra=''){const id=categoryIcons.resolved(c);return `<y-ci class="ci-glyph ci-g-${CATEGORY_ICON_GROUPS.find(g=>g.icons.some(i=>i.id===id)).id} ${extra}">${icon('ci-'+id)}</y-ci>`;}
function categoryTransactionIcon(r){return ['expense','income'].includes(r.type)&&r.category?`<span class="tx-icon ci-tx ci-g-${CATEGORY_ICON_GROUPS.find(g=>g.icons.some(i=>i.id===categoryIcons.resolved(categoryIcons.find(r.category)))).id}">${categoryGlyph(categoryIcons.find(r.category))}</span>`:`<span class="tx-icon">${icon('wallet')}</span>`;}
