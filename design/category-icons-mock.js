/* Category icon proposal. Loaded into app.html by category-icons.html after category-icon-catalog.js. */
(()=>{
const ICONS=new Map(),GROUP_OF=new Map();
CATEGORY_ICON_GROUPS.forEach(g=>g.icons.forEach(i=>{ICONS.set(i.id,i);GROUP_OF.set(i.id,g.id);}));
const DEFAULT_ICON='category';

/* Symbols live next to the prototype's own icons, so icon('ci-…') works everywhere. */
const defs=document.querySelector('.svg-defs defs'),NS='http://www.w3.org/2000/svg';
ICONS.forEach(i=>{const s=document.createElementNS(NS,'symbol');s.id='i-ci-'+i.id;s.setAttribute('viewBox','0 0 24 24');i.paths.forEach(d=>{const p=document.createElementNS(NS,'path');p.setAttribute('d',d);s.appendChild(p);});defs.appendChild(s);});

/* Own icon per category; null means "follow parent" (children) or the generic icon (roots). */
const own={food:'tools-kitchen-2','food-out':'chef-hat','food-grocery':'basket','food-coffee':'coffee',shopping:'shopping-bag','shopping-daily':'toilet-paper','shopping-clothes':'shirt',transport:'train','transport-train':null,'transport-taxi':'car',housing:'home',other:null,work:'briefcase',salary:'cash-banknote',side:'coins'};
const all=()=>[...book().categories,...(book().incomeCategories||[])];
const find=id=>all().find(c=>c.id===id);
const resolved=c=>own[c.id]||(c.parent&&own[c.parent])||DEFAULT_ICON;
function syncIcons(){all().forEach(c=>{if(!(c.id in own))own[c.id]=null;c.icon='ci-'+resolved(c);});}
book().incomeCategories=[{id:'work',name:'工作收入',ja:'仕事の収入',icon:''},{id:'salary',parent:'work',name:'工资',ja:'給与',icon:''},{id:'side',parent:'work',name:'副业',ja:'副業',icon:''}];
syncIcons();

const iconLabel=id=>{const i=ICONS.get(id);return i?t(i.zh,i.ja):'';};
/* A dedicated tag keeps the prototype's generic span/i rules off the badge. */
const badge=(id,extra='')=>`<y-ci class="ci ci-g-${GROUP_OF.get(id)} ${extra}">${icon('ci-'+id)}</y-ci>`;

/* Every category icon the prototype renders gets this theme's badge, so all screens stay consistent. */
function decorate(root){
 root.querySelectorAll('svg>use[href^="#i-ci-"]').forEach(u=>{
  const svg=u.parentNode,parent=svg.parentNode;if(parent.classList.contains('ci'))return;
  const id=u.getAttribute('href').slice(6),group='ci-g-'+GROUP_OF.get(id);
  if(parent.classList.contains('tx-icon')){parent.classList.remove('food','train');parent.classList.add('ci-tx',group);return;}
  const wrap=document.createElement('y-ci');wrap.className='ci '+group;parent.insertBefore(wrap,svg);wrap.appendChild(svg);
 });
}
new MutationObserver(()=>decorate(document.body)).observe(document.body,{childList:true,subtree:true});

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
 const shown=edit.icon||(parent?resolved(parent):null)||suggest(edit.name)[0]||DEFAULT_ICON;
 const note=edit.icon?(edit.picked?t('已自选','自分で選択'):t('按名称推荐','名前からおすすめ')):parent?t(`沿用一级「${categoryName(parent)}」`,`親「${categoryName(parent)}」と同じ`):t('通用图标','標準アイコン');
 const tips=suggest(edit.name).filter(id=>id!==shown).slice(0,5);
 return `<div class="ci-field" data-ci-field><span class="ci-field-label">${t('分类图标','カテゴリのアイコン')}</span>
  <button type="button" class="ci-field-current" data-ci-open>${badge(shown,'ci-lg')}<span><strong>${esc(iconLabel(shown))}</strong><small>${note}</small></span><i>${t('更换','変更')} ↗</i></button>
  ${tips.length?`<div class="ci-suggest"><small>${t('按名称推荐','名前からおすすめ')}</small><div>${tips.map(id=>`<button type="button" data-ci-pick="${id}" aria-label="${esc(iconLabel(id))}">${badge(id)}</button>`).join('')}</div></div>`:''}</div>`;
}
function renderEditorIcon(){const box=document.querySelector('[data-ci-field]');if(box)box.outerHTML=editorIconBlock();}
const originalEditor=categoryEditor;
categoryEditor=(id='',parent='',capture=false,keep=false)=>{
 originalEditor(id,parent,capture);
 const c=find(id);
 if(!keep)edit={id,capture,parent:c?.parent||parent||'',name:c?categoryName(c):'',icon:c?own[c.id]:null,picked:!!c};
 const form=document.getElementById('hierarchy-category-form'),name=form.querySelector('input[name=name]');
 if(keep)name.value=edit.name;
 name.closest('label').insertAdjacentHTML('afterend',editorIconBlock());
 const select=form.querySelector('select[name=parent]');if(select&&keep)select.value=edit.parent;
};
document.addEventListener('input',e=>{
 if(e.target.matches('#hierarchy-category-form input[name=name]')&&edit){edit.name=e.target.value;if(!edit.picked)edit.icon=suggest(edit.name)[0]||null;renderEditorIcon();}
 if(e.target.matches('[data-ci-search]'))filterSheet(e.target.value);
});
document.addEventListener('change',e=>{if(e.target.matches('#hierarchy-category-form select[name=parent]')&&edit){edit.parent=e.target.value;renderEditorIcon();}});

/* Icon sheet: one group per tab; the tab holding the current pick carries a dot. */
let pending=null,activeTab=null;
const cell=id=>`<button type="button" class="ci-cell${id===pending?' selected':''}" data-ci-cell="${id}" aria-label="${esc(iconLabel(id))}" aria-pressed="${id===pending}">${badge(id)}</button>`;
function iconSheet(){
 pending=edit.icon||(edit.parent&&own[edit.parent])||suggest(edit.name)[0]||DEFAULT_ICON;
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
 else if(el.matches('[data-ci-use]')){edit.icon=pending;edit.picked=true;categoryEditor(edit.id,edit.parent,edit.capture,true);}
 else if(el.matches('[data-ci-inherit]')){edit.icon=null;edit.picked=true;categoryEditor(edit.id,edit.parent,edit.capture,true);}
 else if(el.matches('[data-ci-back]'))categoryEditor(edit.id,edit.parent,edit.capture,true);
 else if(el.matches('[data-ci-tab]'))showTab(el.dataset.ciTab);
},true);

/* Saving keeps the prototype's own submit and then stores the chosen icon. */
const submitBefore=experienceSubmit;
experienceSubmit=form=>{
 if(form.id!=='hierarchy-category-form'||!edit)return submitBefore(form);
 const before=new Set(all().map(c=>c.id)),result=submitBefore(form);
 const saved=edit.id||all().find(c=>!before.has(c.id))?.id;
 if(saved)own[saved]=edit.icon;syncIcons();edit=null;render();
 return result;
};

/* Management list: children now carry their own icon too. */
screens.categories=()=>{
 const group=(list)=>list.filter(c=>!c.parent).map(c=>`<section class="hierarchy-manage-group"><button data-action="edit-category" data-id="${c.id}" class="hierarchy-manage-parent">${icon(c.icon)}<strong>${esc(categoryName(c))}</strong><span>${t('一级分类','親カテゴリ')}${own[c.id]?'':' · '+t('通用图标','標準アイコン')}</span><i>↗</i></button><div class="hierarchy-manage-children">${list.filter(ch=>ch.parent===c.id).map(ch=>`<button data-action="edit-category" data-id="${ch.id}" class="ci-child-row">${icon(ch.icon)}<span>${esc(categoryName(ch))}${own[ch.id]?'':`<small>${t('沿用一级','親と同じ')}</small>`}</span><small>↗</small></button>`).join('')}${act('hierarchy-new',t('＋ 添加二级分类','＋ 子カテゴリを追加'),`data-parent="${c.id}"`,'hierarchy-add-child')}</div></section>`).join('');
 return experienceLead('categories',t('用途有层次，记录更清楚。','使い道を、親と子で整理。'),t('一级归纳，二级细分。每个分类都可以选择自己的图标。','親で集計、子で詳細。カテゴリごとにアイコンを選べます。'),'CATEGORIES / TWO LEVELS')+
  section(t('支出分类','支出カテゴリ'),'')+`<div class="hierarchy-management">${group(book().categories)}</div>`+
  section(t('收入分类','収入カテゴリ'),'')+`<div class="hierarchy-management">${group(book().incomeCategories)}</div>`;
};
const editAction=experienceAction;
experienceAction=(name,el)=>{if(name==='edit-category'&&el.dataset.id){const c=find(el.dataset.id);categoryEditor(c.id,c.parent||'');return true;}return editAction(name,el);};

/* States driven by category-icons.html. */
window.categoryIconState=(state,language)=>{
 books.forEach(b=>b.language=language);lang=language;closeSheet();syncIcons();
 if(state==='picker'){navigate('home');beginCategorySelection({type:'expense'});document.querySelectorAll('.hierarchy-group').forEach((g,i)=>{if(i<2)g.open=true;});}
 else if(state==='manage')navigate('categories');
 else if(state==='editor'){navigate('categories');categoryEditor('food-coffee','food');}
 else if(state==='create'){navigate('categories');categoryEditor('','');const n=document.querySelector('#hierarchy-category-form input[name=name]');n.value=t('猫咪','ネコ');n.dispatchEvent(new Event('input',{bubbles:true}));}
 else if(state==='sheet'){navigate('categories');categoryEditor('food-coffee','food');iconSheet();}
 else if(state==='ledger')navigate('ledger');
 else if(state==='analysis')navigate('analysis');
 document.getElementById('content').scrollTop=0;
};
})();
