package com.yohaku.expensetracker.ui.features

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.yohaku.expensetracker.data.repository.AccountBalanceChange
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.word

@Composable internal fun NegativeBalanceSheet(changes:List<AccountBalanceChange>,busy:Boolean,onSave:()->Unit,onDismiss:()->Unit) {
    YSheet(word("账户余额将变为负数","口座残高がマイナスになります"),onDismiss) {
        YText(word("以下账户余额将减少至负数，请确认后保存。","次の口座残高が減少してマイナスになります。確認して保存してください。"),13)
        changes.forEach{c->ThemeAuditPanel(c.account.name){
            RecordFacts(listOf(word("变动前","変更前") to Money(Currency.valueOf(c.account.currency),c.before).display(),word("变动后","変更後") to Money(Currency.valueOf(c.account.currency),c.after).display()),audit=true)
        }}
        YActionRow {
            YButton(word("仍然保存","そのまま保存"),onSave,Modifier.testTag("negative-balance-save"),primary=true,enabled=!busy)
            YLink(word("返回修改","修正に戻る"),onDismiss,Modifier.testTag("negative-balance-edit"))
        }
    }
}
