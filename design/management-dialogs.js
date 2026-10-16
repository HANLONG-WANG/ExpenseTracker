/* UI26 dialog designs. Fixed review facts match the independent native verification books.
   These supplement management.html's scope overviews; the frozen app files stay unchanged. */
function renderManagementDialog(state) {
  if(!state.endsWith('-sheet'))return;
  const style=document.createElement('style');
  style.textContent=`
    .management-flow{display:flex;flex-direction:column;gap:15px}
    .management-flow p{margin:0;font-size:12px;line-height:1.65}
    .management-flow .management-name{font-size:18px}
    .management-flow .management-operation{font-size:14px}
    .management-flow .management-state-note{color:var(--warm)}
    .management-flow button{margin:0}
    .management-flow>.text-link{align-self:flex-start;min-height:40px;padding:8px 0;font-size:12px}
    .management-facts{display:flex;flex-direction:column}
    .management-facts .detail-row{font-size:10px;gap:12px;line-height:1.9;margin:0}
    .management-facts .detail-row>span:first-child{max-width:calc(45% - 5.4px)}
    .management-facts .detail-row>span:last-child{max-width:55%;margin-left:auto;overflow-wrap:anywhere}
    .journal .management-facts .detail-row{font-size:13px;border-bottom-style:dashed}
    .objects .management-facts{gap:6px}
    .management-flow .management-month{font-size:14px}.management-flow .management-submonth{font-size:13px}
  `;document.head.append(style);
  const p=(text,cls='')=>`<p class="${cls}">${text}</p>`;
  const name=text=>p(text,'management-name');
  const facts=items=>`<div class="management-facts">${rows(items)}</div>`;
  const primary=text=>`<button class="full-button">${text}</button>`;
  const link=text=>`<button class="text-link">${text}</button>`;
  const bookFacts=facts([[t('账户','口座'),'1'],[t('记录（含回收记录）','記録（ごみ箱を含む）'),'1'],[t('附件与小票原图','添付・レシート原画像'),'0'],[t('金融计划 / 核对资料','金融プラン・照合資料'),'0 / 0']]);
  const definitions={
    'book-trash-sheet':[t('整本移至回收站','帳簿をごみ箱へ'),name('生活 / 暮らし')+bookFacts+p(t('立即退出正常视图并取消本账本通知。30天内可恢复原ID和全部资料，到期永久清理。其他账本不受影响。','通常表示から外し、この帳簿の通知を取消。30日以内は元のIDと全情報を復元。期限後は完全削除。他の帳簿に影響しません。'))+primary(t('确认','確定'))],
    'account-stop-sheet':[t('账户状态与停用','口座の状態・停止'),name('銀行')+p(t('正在使用','使用中'),'management-state-note')+facts([[t('截至今日余额','本日までの残高'),'¥9,500'],[t('期初信息','期首情報'),'2026-10-01 · ¥10,000'],[t('未清偿的已知账单','既知の未返済請求'),'¥0'],[t('未来一年待处理付款','今後1年の未完了支払'),'0'],[t('历史记录（含回收）','履歴（ごみ箱を含む）'),'1'],[t('核对资料 / 草稿引用','照合資料・下書き参照'),'0 / 0']])+p(t('从日常选择隐藏，历史、欠款与未完成事项保留。仍可处理已有债务的还款；不会自动结清或取消安排。','通常の選択から非表示。履歴・債務・未完了予定を保持。既存債務の返済は可能で、完済や予定取消にはしません。'))+primary(t('确认停用账户','口座を停止'))+link(t('查看删除资格与影响','削除の可否・影響を確認'))],
    'category-stop-sheet':[t('分类状态与归属','カテゴリの状態と分類'),name('食事')+p(t('停用，保留历史与计划','停止し、履歴と予定を保持'),'management-operation')+facts([[t('记录（含回收）','記録（ごみ箱を含む）'),'1'],[t('周期规则 / 安排','定期ルール・予定'),'0 / 0'],[t('草稿','下書き'),'0'],[t('来源分类数','対象カテゴリ数'),'2']])+p(t('账户资金、消费总额及月份不变；分类汇总按新归属计算。','口座資金・支出総額・計上月は変わりません。カテゴリ集計は新しい分類に従います。'))+primary(t('确认分类调整','カテゴリ変更を確定'))+link(t('返回修改','戻って変更'))],
    'activity-delete-sheet':[t('删除活动的影响','シーン削除の影響'),name('秋の旅')+facts([[t('保留消费（含回收）','保持する支出（ごみ箱を含む）'),'2'],[t('保留互请历史','保持するごちそう履歴'),'1'],[t('解除未用预留','未使用の確保を解除'),'¥2,000'],[t('停止周期规则 / 未完成安排','停止する定期ルール・未完了予定'),'0 / 0']])+p(t('从活动列表和新记录选择中移除；已有消费回归日常，各历史月份重新计入月预算。消费、账户资金、附件和原记录ID保留，不转移互请到其他活动。','通常のシーン一覧と新規選択から除外。既存の支出は日常に戻し、各月の予算へ再計上します。支出・口座資金・添付・元の記録IDを保持し、ごちそうを他のシーンへ移しません。'))+[['2026-10','¥6,000'],['2026-11','¥3,000']].map(([month,amount])=>p(month,'management-month')+facts([[t('计入月预算的消费','月予算に含む支出'),`¥0 → ${amount}`],[t('活动独立支出','シーンの独立支出'),`${amount} → ¥0`],[t('全部消费','すべての支出'),`${amount} → ${amount}`]])).join('')+link(t('分类预算变化（2项）','カテゴリ予算の変化（2件）'))+p(t('删除无法撤销。原活动的消费和互请仍可从“已删除活动的历史”查看；此处不是还款或清偿。','削除は元に戻せません。「削除済みシーンの履歴」で元の支出とごちそうを確認できます。返済や清算の処理ではありません。'))+primary(t('确认删除活动','シーンを削除'))],
    'entry-purge-sheet':[t('永久删除记录','記録を完全削除'),name('喫茶 原名')+facts([[t('关联组记录数','関連する記録数'),'2'],[t('关联附件 / 原图','関連する添付・原画像'),'0'],[t('核对资料 / 金融计划','照合資料・金融プラン'),'0 / 0'],[t('编辑本组的草稿','対象の編集下書き'),'0']])+p('2026-10-14 · '+t('消费','支出')+' · ¥1,000')+p('2026-10-15 · '+t('退款','返金')+' · ¥200')+p(t('无法恢复。清理本组账目、修订及媒体引用，并放弃编辑本组的草稿；其他记录仍引用的文件保留。独立还款、合同、已确认账单与流水核对原始资料保留。','復元できません。対象記録・変更履歴・メディア参照と編集下書きを削除。他の記録が参照するファイルは保持。独立した返済・契約・確定請求・元の照合資料は保持します。'))+primary(t('永久删除记录','記録を完全削除'))]
  };
  const categories=link(t('分类预算变化（2项）','カテゴリ予算の変化（2件）'));
  definitions['activity-expanded-sheet']=[definitions['activity-delete-sheet'][0],definitions['activity-delete-sheet'][1].replace(categories,categories+[['2026-10','¥6,000'],['2026-11','¥3,000']].map(([month,amount])=>p(month+' · 食事','management-submonth')+facts([[t('计入分类预算的消费','カテゴリ予算に含む支出'),`¥0 → ${amount}`],[t('分类额度','カテゴリ上限'),'¥8,000']])).join(''))];
  const value=definitions[state];if(!value)throw Error('Unknown management dialog: '+state);
  openSheet(value[0],`<div class="management-flow">${value[1]}</div>`);
}
