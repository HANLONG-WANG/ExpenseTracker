package quality.fixture

import androidx.compose.material3.Button
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material.icons.Icons
import androidx.room.Entity

val literalColor = Color(0xFF123456)
val hexColor = "#1234AB"
val literalSpacing = 12.dp

fun localTheme() = MaterialTheme { }
fun AmountText(value: String) = value
fun unsafeTag(accountName: String) = Modifier.testTag("account_" + accountName)
