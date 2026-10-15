package com.yohaku.expensetracker

import android.net.Uri
import androidx.core.content.FileProvider

/** UUID storage paths retain the imported MIME and display name when handed to a viewer. */
class MediaFileProvider:FileProvider() {
    override fun getType(uri:Uri):String?=uri.getQueryParameter("mime")?:super.getType(uri)
}
