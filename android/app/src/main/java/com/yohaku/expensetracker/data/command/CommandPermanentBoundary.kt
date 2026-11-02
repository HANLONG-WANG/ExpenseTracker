package com.yohaku.expensetracker.data.command

internal val permanentAlternatives=linkedMapOf(
    "backup.restore" to "请在手机备份设置中操作 / 端末のバックアップ設定で操作してください",
    "cloud.credentials" to "请在手机连接设置中操作 / 端末の接続設定で操作してください",
    "cloud.device.manage" to "请在手机设备管理中操作 / 端末のデバイス管理で操作してください",
    "category.mergeDelete" to "用 entry.update 批量改分类、template.save、budget.save（目标设额，原分类各生效月设 null）、activity.save 的 categoryBudgets、recurrence.save 和 occurrence.update 迁移，再 category.lifecycle ARCHIVE；预算空值历史行保留，普通/金融草稿引用归阶段5–6，在本机处理 / entry.update・template.save・budget.save（移行先を設定、元の各適用月をnull）・activity.save の categoryBudgets・recurrence.save・occurrence.update で移行後、category.lifecycle ARCHIVE。空の予算履歴は保持。通常・金融下書きは段階5–6、端末で処理してください",
    "category.deleteEmpty" to "改用 category.lifecycle ARCHIVE / category.lifecycle ARCHIVE を使ってください",
    "account.delete" to "改用 account.setActive false；账户无回收站 / account.setActive false を使ってください。口座にはごみ箱がありません",
    "entry.purge" to "改用 entry.lifecycle TRASH；保留回收站恢复能力 / entry.lifecycle TRASH を使い、復元可能な状態にしてください",
    "entry.emptyTrash" to "逐笔 entry.lifecycle RESTORE 或保留在回收站 / entry.lifecycle RESTORE またはごみ箱に保持してください",
    "book.purge" to "改用 book.lifecycle ARCHIVE/TRASH/RESTORE / book.lifecycle ARCHIVE・TRASH・RESTORE を使ってください",
    "book.emptyTrash" to "保留在账本回收站或用 book.lifecycle RESTORE / 帳簿のごみ箱に保持、または book.lifecycle RESTORE を使ってください",
    "template.delete" to "用 template.save 修改或 template.move 调整排序；模板没有回收站 / template.save で編集、または template.move で並べ替えてください。テンプレートにごみ箱はありません",
    "merchantLocation.remove" to "用 merchantLocation.save 调整地点，或 merchant.setActive false 停用商户 / merchantLocation.save で場所を編集、または merchant.setActive false でお店を停止してください",
    "budget.delete" to "用 budget.save amount=null 清空额度，保留历史行 / budget.save amount=null で上限を解除し、履歴行を保持してください",
    "budget.clearAllRows" to "逐月/分类用 budget.save amount=null；不能调用物理清表 / 各月・カテゴリを budget.save amount=null で解除し、行を一括削除しないでください",
    "activity.purge" to "用 activity.lifecycle TRASH/RESTORE / activity.lifecycle TRASH・RESTORE を使ってください",
    "activity.emptyTrash" to "保留活动历史或 activity.lifecycle RESTORE / シーン履歴を保持、または activity.lifecycle RESTORE を使ってください",
    "activityBudget.delete" to "用 activity.save 完整保存分类预算，作为活动编辑 / activity.save でカテゴリ予算全体を編集してください",
    "activityReserve.delete" to "用 activityReserve.release 释放剩余预留 / activityReserve.release で残りの確保を解放してください",
    "activityParticipant.remove" to "保留参与历史；需要时 friend.setActive false 停用朋友 / 参加履歴を保持し、必要なら friend.setActive false で友達を停止してください",
    "activityTreat.purge" to "用 activityTreat.lifecycle TRASH/RESTORE，保留分摊历史 / activityTreat.lifecycle TRASH・RESTORE で分担履歴を保持してください",
    "recurrence.delete" to "用 recurrence.setActive false 停用规则 / recurrence.setActive false でルールを停止してください",
    "occurrence.delete" to "用 occurrence.lifecycle CANCEL/RESTORE / occurrence.lifecycle CANCEL・RESTORE を使ってください",
    "expectedIncome.delete" to "用 expectedIncome.cancel；已到账的实际记录由 entry.lifecycle 处理 / expectedIncome.cancel を使い、入金済みの実記録は entry.lifecycle で処理してください",
    "trash.empty" to "保留回收站或恢复对象 / ごみ箱に保持するか対象を復元してください",
    "trash.purgeExpired" to "保留本机自动清理策略，不由云端触发 / 端末の自動消去に任せ、クラウドから実行しないでください",
    "any.permanentDelete" to "使用归档、停用或移入回收站；无替代功能时在手机界面处理 / アーカイブ・停止・ごみ箱を使用し、代替がない操作は端末で行ってください"
)
internal fun permanentAlternative(name:String)="永久删除不能由云端办理 / クラウドから完全削除はできません。"+permanentAlternatives.getValue(name)
