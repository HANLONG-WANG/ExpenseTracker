package com.yohaku.expensetracker.data.command

internal val permanentAlternatives=linkedMapOf(
    "backup.restore" to "请在手机备份设置中操作 / 端末のバックアップ設定で操作してください",
    "cloud.credentials" to "请在手机连接设置中操作 / 端末の接続設定で操作してください",
    "cloud.device.manage" to "请在手机设备管理中操作 / 端末のデバイス管理で操作してください",
    "category.mergeDelete" to "用 entry.update 和 template.save 迁移分类后归档原分类；预算、活动预算、周期/实例、普通/金融草稿引用需在对应界面处理（后续阶段命令补齐） / entry.update・template.save で移行後、元カテゴリをアーカイブ。予算・シーン予算・定期予定・下書きの参照は各画面で変更してください",
    "category.deleteEmpty" to "改用 category.lifecycle ARCHIVE / category.lifecycle ARCHIVE を使ってください",
    "account.delete" to "改用 account.setActive false；账户无回收站 / account.setActive false を使ってください。口座にはごみ箱がありません",
    "entry.purge" to "改用 entry.lifecycle TRASH；保留回收站恢复能力 / entry.lifecycle TRASH を使い、復元可能な状態にしてください",
    "entry.emptyTrash" to "逐笔 entry.lifecycle RESTORE 或保留在回收站 / entry.lifecycle RESTORE またはごみ箱に保持してください",
    "book.purge" to "改用 book.lifecycle ARCHIVE/TRASH/RESTORE / book.lifecycle ARCHIVE・TRASH・RESTORE を使ってください",
    "book.emptyTrash" to "保留在账本回收站或用 book.lifecycle RESTORE / 帳簿のごみ箱に保持、または book.lifecycle RESTORE を使ってください",
    "template.delete" to "用 template.save 修改或 template.move 调整排序；模板没有回收站 / template.save で編集、または template.move で並べ替えてください。テンプレートにごみ箱はありません",
    "merchantLocation.remove" to "用 merchantLocation.save 调整地点，或 merchant.setActive false 停用商户 / merchantLocation.save で場所を編集、または merchant.setActive false でお店を停止してください",
    "trash.empty" to "保留回收站或恢复对象 / ごみ箱に保持するか対象を復元してください",
    "trash.purgeExpired" to "保留本机自动清理策略，不由云端触发 / 端末の自動消去に任せ、クラウドから実行しないでください",
    "any.permanentDelete" to "使用归档、停用或移入回收站；无替代功能时在手机界面处理 / アーカイブ・停止・ごみ箱を使用し、代替がない操作は端末で行ってください"
)
internal fun permanentAlternative(name:String)="永久删除不能由云端办理 / クラウドから完全削除はできません。"+permanentAlternatives.getValue(name)
