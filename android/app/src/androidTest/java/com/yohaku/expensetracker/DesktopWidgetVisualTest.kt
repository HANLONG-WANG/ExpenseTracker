package com.yohaku.expensetracker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.appwidget.AppWidgetManager
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.db.YohakuDatabase
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.category.CategoryIconChoice
import com.yohaku.expensetracker.domain.category.resolveCategoryIcon
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.platform.desktop.DesktopArtwork
import com.yohaku.expensetracker.platform.desktop.WidgetRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.roundToInt

/** 普通测试和截图只使用内存库；真实桌面种子必须显式开启。 */
@RunWith(AndroidJUnit4::class)
class DesktopWidgetVisualTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val today=LocalDate.of(2026,11,7)
    private val month="2026-11"
    private val directory get()=File(context.getExternalFilesDir(null),"desktop-widget").apply{mkdirs()}
    private val names=listOf("午饭","咖啡","电车","便利店")
    private val icons=listOf("bowl","coffee","train","shopping-bag")
    private val amounts=listOf<Long?>(null,480,220,null)
    private data class Fixture(val book:String,val account:String,val categories:List<String>,val templates:List<String>,val recurrence:RecurrenceInput)

    private suspend fun isolated(block:suspend (LedgerRepository)->Unit) {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {block(LedgerRepository(db,Clock.fixed(today.atStartOfDay().toInstant(ZoneOffset.UTC),ZoneOffset.UTC)))}
        finally {db.close()}
    }
    private suspend fun snapshot(repo:LedgerRepository,book:String)=repo.snapshot(requireNotNull(repo.dao.book(book))).first()
    private suspend fun fixture(repo:LedgerRepository,theme:String,language:String,budget:Long?=180000,name:String="日常生活"):Fixture {
        val book=repo.createBook(name,Currency.JPY,theme,language,startDate="$month-01",budgetMinor=budget)
        repo.updateBook(requireNotNull(repo.dao.book(book)).copy(initialized=true))
        val account=repo.saveAccount(book,"日常账户",AccountType.BANK,Currency.JPY,Money(Currency.JPY,300000),"$month-01")
        val categories=names.mapIndexed{index,label->repo.saveCategory(book,label,EntryKind.EXPENSE,null,icon=CategoryIconChoice.Set(icons[index]))}
        repo.saveEntry(book,EntryDraft(accountId=account,categoryId=categories.first(),amount="90360",date=today.toString()))
        val recurrence=RecurrenceInput("本月待付",account,categories.first(),Money(Currency.JPY,40000),null,false,RecurrenceFrequency.MONTHLY,"$month-20")
        val rule=repo.saveRecurrence(book,recurrence)
        repo.ensureOccurrences(book,month)
        val templates=names.mapIndexed{index,label->repo.saveTemplate(book,TemplateInput(label,EntryKind.EXPENSE,categories[index],account,Currency.JPY,amounts[index]))}
        return Fixture(book,account,categories,templates,recurrence.copy(id=rule))
    }
    private suspend fun invalidateFirst(repo:LedgerRepository,f:Fixture) {
        assertTrue(repo.commitCategoryOperation(repo.previewCategoryOperation(f.book,f.categories.first(),CategoryOperation.ARCHIVE)))
    }

    /** 在主线程展开生产布局；Android 12+ 直接选单尺寸，避免 apply 默认选错映射分支。 */
    private fun expanded(s:BookSnapshot?,look:Pair<String,String>,wide:Boolean):View {
        val widthDp=if(wide)356 else 172
        val renderer=WidgetRenderer(context)
        val views=if(Build.VERSION.SDK_INT>=31)renderer.layout(7001,s,today,look,wide,widthDp.toFloat())
        else renderer.render(7001,s,today,look,Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,widthDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,widthDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,180)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,180)
        })
        val view=views.apply(context,FrameLayout(context))
        val density=context.resources.displayMetrics.density
        val width=(widthDp*density).roundToInt();val height=(180*density).roundToInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
        view.layout(0,0,width,height)
        return view
    }
    private fun save(bitmap:Bitmap,name:String) {
        try {File(directory,name).outputStream().use{assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}}
        finally {bitmap.recycle()}
    }
    private suspend fun capture(s:BookSnapshot?,look:Pair<String,String>,state:String) {
        val bitmap=withContext(Dispatchers.Main) {
            val view=expanded(s,look,state.startsWith("wide-"))
            Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888).also {
                val canvas=Canvas(it);canvas.drawColor(Color.parseColor("#7f8f99"));view.draw(canvas)
            }
        }
        save(bitmap,"${look.first}-${look.second}-$state.png")
    }
    private fun shortcuts(s:BookSnapshot) {
        val artwork=DesktopArtwork(context,s.book.theme)
        val tiles=s.templates.take(4).mapIndexed{index,t->artwork.shortcut(t.name,resolveCategoryIcon(t.categoryId,s.categories),index)}
        val size=tiles.first().width
        val bitmap=Bitmap.createBitmap(size*4,size,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.drawColor(Color.parseColor("#7f8f99"))
        tiles.forEachIndexed{index,tile->
            canvas.save()
            val x=index*size.toFloat()
            canvas.clipPath(android.graphics.Path().apply{addCircle(x+size/2f,size/2f,size/2f,android.graphics.Path.Direction.CW)})
            canvas.drawBitmap(tile,null,android.graphics.RectF(x,0f,x+size,size.toFloat()),null)
            canvas.restore();tile.recycle()
        }
        save(bitmap,"${s.book.theme}-shortcuts.png")
    }

    @Test fun captureWidgetStatesAndShortcutIcons()=runBlocking {
        assumeTrue("需显式设置 visual=true",visualEnabled())
        isolated {repo->
            val capturedThemes=mutableSetOf<String>()
            for(look in testLooks(this@DesktopWidgetVisualTest)) {
                val f=fixture(repo,look.first,look.second)
                val normal=snapshot(repo,f.book)
                val budget=normal.budgetProjection(month)
                assertEquals(90360L,budget.spentMinor);assertEquals(49640L,budget.availableMinor)
                capture(normal,look,"wide-normal");capture(normal,look,"small-normal")
                if(capturedThemes.add(look.first))shortcuts(normal)

                val extra=repo.saveEntry(f.book,EntryDraft(accountId=f.account,categoryId=f.categories.first(),amount="102040",date=today.toString()))
                capture(snapshot(repo,f.book),look,"small-over")
                repo.deleteEntry(f.book,extra)
                val noBudget=fixture(repo,look.first,look.second,budget=null,name="兴趣")
                capture(snapshot(repo,noBudget.book),look,"small-nobudget")

                repo.saveRecurrence(f.book,f.recurrence.copy(amount=null,estimated=true))
                val unknown=snapshot(repo,f.book)
                assertNull(unknown.budgetProjection(month).availableMinor)
                capture(unknown,look,"wide-unknown")
                repo.saveRecurrence(f.book,f.recurrence)

                invalidateFirst(repo,f)
                repo.saveTemplate(f.book,TemplateInput("周末和朋友去的那家拉面店",EntryKind.EXPENSE,f.categories[1],f.account,Currency.JPY,480,id=f.templates[1]))
                capture(snapshot(repo,f.book),look,"wide-edge")
                f.templates.forEach{repo.deleteTemplate(f.book,it)}
                capture(snapshot(repo,f.book),look,"wide-empty")
                capture(null,look,"small-unavailable")
            }
        }
    }

    @Test fun templateDescriptionsAndClickTargetsRemainAvailable()=runBlocking {
        isolated {repo->
            val f=fixture(repo,"magazine","zh")
            invalidateFirst(repo,f)
            val s=snapshot(repo,f.book)
            withContext(Dispatchers.Main) {
                val view=expanded(s,"magazine" to "zh",true)
                val slots=view.findViewById<ViewGroup>(R.id.widget_templates)
                assertEquals(4,slots.childCount)
                assertTrue(slots.getChildAt(0).contentDescription.toString().contains("午饭，需要编辑"))
                val coffee=slots.getChildAt(1).contentDescription.toString()
                assertTrue(coffee.contains("咖啡"));assertTrue(coffee.contains("¥480"))
                assertTrue(view.findViewById<View>(R.id.widget_balance).hasOnClickListeners())
                for(index in 0 until slots.childCount)assertTrue(slots.getChildAt(index).hasOnClickListeners())
            }
        }
    }

    @Test fun seedRealDesktopBooks():Unit=runBlocking {
        assumeTrue("此方法会写入真实账本，需显式设置 desktopSeed=true",testOption("desktopSeed"))
        val app=context.applicationContext as YohakuApplication
        val books=app.repository.db.withTransaction {
            listOf("magazine","journal","objects").associateWith{theme->
                fixture(app.repository,theme,"zh",name="桌面验证 · $theme").book
            }
        }
        app.preferences.select(books.getValue("objects"))
        android.util.Log.i("YohakuDesktopTest","桌面种子已创建（2026-11）：$books")
    }
}
