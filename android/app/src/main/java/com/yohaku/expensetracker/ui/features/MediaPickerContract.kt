package com.yohaku.expensetracker.ui.features

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

internal class MediaPickerContract(private val mime:String,private val title:String):ActivityResultContract<Unit,List<Uri>>() {
    override fun createIntent(context:Context,input:Unit):Intent=Intent.createChooser(Intent(Intent.ACTION_GET_CONTENT).apply{type=mime;addCategory(Intent.CATEGORY_OPENABLE);putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true)},title)
    override fun parseResult(resultCode:Int,intent:Intent?):List<Uri> {
        if(resultCode!=Activity.RESULT_OK)return emptyList()
        return intent?.clipData?.let{clip->List(clip.itemCount){clip.getItemAt(it).uri}}?:listOfNotNull(intent?.data)
    }
}
