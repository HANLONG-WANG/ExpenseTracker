package com.yohaku.expensetracker.data.command

/** Display names shared by preview, history and domain failure messages. Never expose step IDs. */
private val workflowLabels=mapOf(
    "book.monthlyPlan.save" to ("保存月度计划" to "月の計画を保存"),
    "entryDraft.reclassify" to ("迁移草稿分类" to "下書きのカテゴリを移行"),"statement.exportOriginal" to ("导出流水原件" to "元明細を書き出す"),"command.exportResult" to ("另存本方案结果" to "このプランの結果を保存"),
    "statement.accept" to ("接受流水差异关联" to "明細の差異を確認して関連付け"),"statement.unlink" to ("解除流水差异关联" to "明細の差異関連を解除"),"statement.supplement" to ("从流水补记" to "明細から記録を補完"),"statement.confirmActual" to ("按流水确认实际金额" to "明細の実額で確定"),
    "receipt.items.save" to ("回填或编辑商品明细" to "商品明細を補完・編集"),"csvBatch.undo" to ("将导入批次移入回收站" to "取込バッチをごみ箱へ移動"),"book.select" to ("切换当前账本" to "現在の帳簿を切替"),
    "preferences.analysis" to ("分析模块与置顶" to "分析項目と固定表示"),"preferences.reminders" to ("提醒设置" to "通知設定"),"preferences.automaticRates" to ("自动汇率" to "自動レート取得"),"preferences.location" to ("定位开关" to "位置情報の切替"),"preferences.photoStorage" to ("照片保存方式" to "写真の保存方法"),
    "attachment.attachSource" to ("附上本任务源文件" to "このタスクの元ファイルを添付"),"entry.prepareDraft" to ("准备待补完的记账草稿" to "補完が必要な記録下書きを用意"),"backup.export" to ("选择位置导出备份" to "保存先を選んでバックアップ出力"),"local.restorePreference" to ("恢复原偏好" to "元の設定に戻す"))
private val financeCommandLabels=mapOf(
    "financialRepaymentAllocation.resolve" to ("选择还款分配" to "返済充当を選択"),
    "financialBill.resolve" to ("选择计划账期" to "プランの請求を選択"),"financialRule.validate" to ("核验规则与实际账单" to "条件と確定請求を照合"),
    "financialManualPlan.save" to ("记录已知逐期计划" to "既知の各回予定を記録"),"financialBill.confirm" to ("核对平台账单" to "返済先の請求を確認"),
    "financialPool.save" to ("建立、采纳或重算余额池" to "残高プールの作成・統合・再計算"),"financialArrangement.record" to ("记录已办理安排" to "手続き済みの予定を記録"),
    "financialArrangement.reconcile" to ("重新核对已办理安排" to "手続き済み予定を再確認"),"financialPool.changePayment" to ("记录余额池月付款变更" to "残高プールの月払い変更を記録"),
    "financialBatchRevolving.apply" to ("记录批量转循环" to "一括リボ変更を記録"),"financialBatchRevolving.undo" to ("撤销批量转循环" to "一括リボ変更を取消"),
    "financialBorrowing.save" to ("记录借入与合同" to "借入と契約を記録"),"financialRepayment.save" to ("记录还款及待确认清偿" to "支払と未確定の返済を記録"),
    "financialRepayment.confirm" to ("确认清偿与本金分配" to "返済と元金充当を確定"),"financialContract.linkPlan" to ("关联合同与计划" to "契約とプランを関連付け"),
    "creditLimit.save" to ("设置额度与占用基准" to "利用枠と使用額基準を設定"),"creditLimitGroup.save" to ("保存共享额度组" to "共通利用枠を保存"),
    "creditLimitRecovery.confirm" to ("确认平台恢复额度" to "返済先の利用枠回復を確認"),"creditLimitRecovery.void" to ("作废额度恢复确认" to "利用枠回復の確認を無効化"),
    "creditLimitObservation.record" to ("记录平台可用额度" to "返済先の利用可能枠を記録"),"financialDraft.reclassify" to ("迁移金融草稿分类" to "金融下書きのカテゴリを移行")
)
private fun businessName(zh:String,ja:String)=zh to ja
internal fun commandBusinessName(name:String):Pair<String,String> = workflowLabels[name]?:financeCommandLabels[name]?:when(name){
    "budget.save"->businessName("设置或清空预算","予算の設定・解除");"budget.saveBook"->businessName("账本与月预算","帳簿と月予算");"activity.setMode"->businessName("活动模式开关","シーンモードの切替");"activity.setCurrent"->businessName("设置当前活动","現在のシーンを設定");"activity.save"->businessName("保存活动与分类预算","シーンとカテゴリ予算を保存");"activity.end"->businessName("结束活动","シーンを終了");"activity.lifecycle"->businessName("活动回收与恢复","シーンの削除・復元");"activityReserve.save"->businessName("保存活动预留","シーンの確保を保存");"activityReserve.release"->businessName("释放预留","確保を解放");"activityParticipant.add"->businessName("添加活动参与人","シーンの参加者を追加");"activityTreat.save"->businessName("记录他人请客","友達のごちそうを記録");"activityTreat.lifecycle"->businessName("请客记录回收与恢复","ごちそう記録の削除・復元");"recurrence.save"->businessName("保存周期规则","定期ルールを保存");"recurrence.setActive"->businessName("周期规则启停","定期ルールの有効・停止");"occurrence.resolve"->businessName("选择周期实例","定期予定を選択");"occurrence.update"->businessName("调整周期实例分类","定期予定のカテゴリ変更");"occurrence.lifecycle"->businessName("取消或恢复周期实例","定期予定の取消・復元");"occurrence.link"->businessName("关联已有支付记录","既存の支払記録に関連付け");"occurrence.unlink"->businessName("解除支付记录关联","支払記録の関連を解除");"occurrence.pay"->businessName("记录周期支付","定期支払を記録");"expectedIncome.save"->businessName("保存预计收入","収入予定を保存");"expectedIncome.cancel"->businessName("取消预计收入","収入予定を取消");"expectedIncome.arrive"->businessName("确认收入到账","入金を確認");"account.save"->businessName("账户与期初信息","口座と期首情報");"financialRule.addVersion"->businessName("新增金融规则版本","金融条件のバージョン追加");"financialPrediction.save"->businessName("建立或重算预测","予測の作成・再計算");"local.entry.restoreCategory"->businessName("恢复原分类","元のカテゴリに戻す");"entry.update"->businessName("批量编辑记录","記録をまとめて編集");"entry.create"->businessName("新建记录","記録を作成");"entry.confirmActual"->businessName("确认实际金额","実額を確定");"entry.lifecycle"->businessName("记录回收站","記録のごみ箱");"local.restoreExecution"->businessName("恢复原值并移除新建对象","元の値に戻し、新規作成した対象を削除");"local.account.deleteUnused"->businessName("撤销新建的未使用账户","未使用口座の作成を取消");"book.create"->businessName("新建账本","帳簿を作成");"book.update"->businessName("账本设置","帳簿の設定");"book.completeSetup"->businessName("完成账本设置","帳簿の設定を完了");"book.lifecycle"->businessName("账本状态","帳簿の状態");"account.setActive"->businessName("账户启停","口座の有効・停止");"category.save"->businessName("保存分类","カテゴリを保存");"category.setFavorite"->businessName("分类收藏","カテゴリのお気に入り");"category.lifecycle"->businessName("分类归档","カテゴリのアーカイブ");"category.reparent"->businessName("分类层级","カテゴリの階層");"merchant.save"->businessName("保存商户","お店を保存");"merchantLocation.save"->businessName("保存地点","場所を保存");"merchant.setActive"->businessName("商户启停","お店の有効・停止");"friend.save"->businessName("保存朋友","友達を保存");"friend.setActive"->businessName("朋友启停","友達の有効・停止");"template.save"->businessName("保存模板","テンプレートを保存");"template.move"->businessName("模板排序","テンプレートの並べ替え");"template.createEntry"->businessName("新建记录","記録を作成");else->businessName("账本操作","帳簿の操作")}

internal fun commandStepTitle(number:Int,command:String):Pair<String,String> {
    val name=commandBusinessName(command)
    return "步骤 ${number} · ${name.first}" to "ステップ ${number} · ${name.second}"
}
