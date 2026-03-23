package io.legado.app.ui.widget.image

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.util.AttributeSet
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatImageView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import io.legado.app.constant.AppPattern
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.lib.theme.accentColor
import io.legado.app.model.BookCover
import io.legado.app.utils.textHeight
import io.legado.app.utils.toStringArray
import io.legado.app.utils.toTimeAgo

/**
 * 封面
 */
@Suppress("unused")
class CoverImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {
    private var filletPath = Path()
    private var viewWidth: Float = 0f
    private var viewHeight: Float = 0f
    private var defaultCover = true
    var bitmapPath: String? = null
        private set
    private var name: String? = null
    private var author: String? = null
    private var fileSize: String? = null
    private var otherGroups: String? = null
    private var editTimeText: String? = null
    private var nameHeight = 0f
    private var authorHeight = 0f
    private val namePaint by lazy {
        val textPaint = TextPaint()
        textPaint.typeface = Typeface.DEFAULT_BOLD
        textPaint.isAntiAlias = true
        textPaint.textAlign = Paint.Align.CENTER
        textPaint
    }
    private val authorPaint by lazy {
        val textPaint = TextPaint()
        textPaint.typeface = Typeface.DEFAULT
        textPaint.isAntiAlias = true
        textPaint.textAlign = Paint.Align.CENTER
        textPaint
    }
    private val fileSizePaint by lazy {
        TextPaint().apply {
            isAntiAlias = true
            color = Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.LEFT
        }
    }
    private val fileSizeBgPaint by lazy {
        Paint().apply {
            isAntiAlias = true
            color = Color.argb(160, 0, 0, 0)
            style = Paint.Style.FILL
        }
    }
    private val fileSizeRect = RectF()

    override fun setLayoutParams(params: ViewGroup.LayoutParams?) {
        if (params != null) {
            val width = params.width
            if (width >= 0) {
                params.height = width * 7 / 5
            } else {
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
        super.setLayoutParams(params)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = MeasureSpec.getSize(widthMeasureSpec)
        val measuredHeight = measuredWidth * 7 / 5
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        viewWidth = width.toFloat()
        viewHeight = height.toFloat()
        filletPath.reset()
        if (width > 10 && viewHeight > 10) {
            filletPath.apply {
                moveTo(10f, 0f)
                lineTo(viewWidth - 10, 0f)
                quadTo(viewWidth, 0f, viewWidth, 10f)
                lineTo(viewWidth, viewHeight - 10)
                quadTo(viewWidth, viewHeight, viewWidth - 10, viewHeight)
                lineTo(10f, viewHeight)
                quadTo(0f, viewHeight, 0f, viewHeight - 10)
                lineTo(0f, 10f)
                quadTo(0f, 0f, 10f, 0f)
                close()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!filletPath.isEmpty) {
            canvas.clipPath(filletPath)
        }
        super.onDraw(canvas)
        if (defaultCover && !isInEditMode) {
            drawNameAuthor(canvas)
        }
        if (!isInEditMode) {
            drawFileSize(canvas)
            drawOtherGroups(canvas)
            drawEditTime(canvas)
        }
    }

    private fun drawNameAuthor(canvas: Canvas) {
        if (!BookCover.drawBookName) return
        var startX = width * 0.2f
        var startY = viewHeight * 0.2f
        name?.toStringArray()?.let { name ->
            namePaint.textSize = viewWidth / 6
            namePaint.strokeWidth = namePaint.textSize / 5
            name.forEachIndexed { index, char ->
                namePaint.color = Color.WHITE
                namePaint.style = Paint.Style.STROKE
                canvas.drawText(char, startX, startY, namePaint)
                namePaint.color = context.accentColor
                namePaint.style = Paint.Style.FILL
                canvas.drawText(char, startX, startY, namePaint)
                startY += namePaint.textHeight
                if (startY > viewHeight * 0.8) {
                    startX += namePaint.textSize
                    namePaint.textSize = viewWidth / 10
                    startY = (viewHeight - (name.size - index - 1) * namePaint.textHeight) / 2
                }
            }
        }
        if (!BookCover.drawBookAuthor) return
        author?.toStringArray()?.let { author ->
            authorPaint.textSize = viewWidth / 10
            authorPaint.strokeWidth = authorPaint.textSize / 5
            startX = width * 0.8f
            startY = viewHeight * 0.95f - author.size * authorPaint.textHeight
            startY = maxOf(startY, viewHeight * 0.3f)
            author.forEach {
                authorPaint.color = Color.WHITE
                authorPaint.style = Paint.Style.STROKE
                canvas.drawText(it, startX, startY, authorPaint)
                authorPaint.color = context.accentColor
                authorPaint.style = Paint.Style.FILL
                canvas.drawText(it, startX, startY, authorPaint)
                startY += authorPaint.textHeight
                if (startY > viewHeight * 0.95) {
                    return@let
                }
            }
        }
    }

    private fun drawFileSize(canvas: Canvas) {
        val size = fileSize ?: return
        fileSizePaint.textSize = viewWidth / 8
        val textWidth = fileSizePaint.measureText(size)
        val textHeight = fileSizePaint.textHeight
        val paddingH = viewWidth / 20
        val paddingV = viewWidth / 30
        val left = 4f
        val bottom = viewHeight - 4f
        val top = bottom - textHeight - paddingV * 2
        fileSizeRect.set(left, top, left + textWidth + paddingH * 2, bottom)
        canvas.drawRoundRect(fileSizeRect, 8f, 8f, fileSizeBgPaint)
        canvas.drawText(
            size,
            left + paddingH,
            bottom - paddingV - fileSizePaint.descent(),
            fileSizePaint
        )
    }

    fun setFileSize(sizeInBytes: Long) {
        val sizeText = if (sizeInBytes > 0) {
            val mb = sizeInBytes / 1048576.0
            if (mb >= 1) {
                String.format("%.1fMB", mb)
            } else {
                String.format("%.0fKB", sizeInBytes / 1024.0)
            }
        } else {
            null
        }
        if (fileSize != sizeText) {
            fileSize = sizeText
            invalidate()
        }
    }

    fun clearFileSize() {
        if (fileSize != null) {
            fileSize = null
            invalidate()
        }
    }

    private fun drawOtherGroups(canvas: Canvas) {
        val groups = otherGroups ?: return
        fileSizePaint.textSize = viewWidth / 9
        val paddingH = viewWidth / 20
        val paddingV = viewWidth / 30
        val lines = groups.split("\n")
        val lineHeight = fileSizePaint.textHeight
        val totalTextHeight = lineHeight * lines.size
        val top = 4f
        // 计算最宽行的宽度
        var maxWidth = 0f
        for (line in lines) {
            val w = fileSizePaint.measureText(line)
            if (w > maxWidth) maxWidth = w
        }
        val right = viewWidth - 4f
        val left = right - maxWidth - paddingH * 2
        val bottom = top + totalTextHeight + paddingV * 2
        fileSizeRect.set(left, top, right, bottom)
        canvas.drawRoundRect(fileSizeRect, 8f, 8f, fileSizeBgPaint)
        var y = top + paddingV + lineHeight - fileSizePaint.descent()
        for (line in lines) {
            canvas.drawText(line, left + paddingH, y, fileSizePaint)
            y += lineHeight
        }
    }

    fun setOtherGroups(text: String?) {
        if (otherGroups != text) {
            otherGroups = text
            invalidate()
        }
    }

    fun setEditTime(time: Long) {
        val text = if (time > 0) time.toTimeAgo() else null
        if (editTimeText != text) {
            editTimeText = text
            invalidate()
        }
    }

    private fun drawEditTime(canvas: Canvas) {
        val text = editTimeText ?: return
        fileSizePaint.textSize = viewWidth / 9
        val textWidth = fileSizePaint.measureText(text)
        val textHeight = fileSizePaint.textHeight
        val paddingH = viewWidth / 20
        val paddingV = viewWidth / 30
        val left = 4f
        val top = 4f
        val bottom = top + textHeight + paddingV * 2
        fileSizeRect.set(left, top, left + textWidth + paddingH * 2, bottom)
        canvas.drawRoundRect(fileSizeRect, 8f, 8f, fileSizeBgPaint)
        canvas.drawText(
            text,
            left + paddingH,
            bottom - paddingV - fileSizePaint.descent(),
            fileSizePaint
        )
    }

    fun setHeight(height: Int) {
        val width = height * 5 / 7
        minimumWidth = width
    }

    private val glideListener by lazy {
        object : RequestListener<Drawable> {

            override fun onLoadFailed(
                e: GlideException?,
                model: Any?,
                target: Target<Drawable>,
                isFirstResource: Boolean
            ): Boolean {
                defaultCover = true
                return false
            }

            override fun onResourceReady(
                resource: Drawable,
                model: Any,
                target: Target<Drawable>?,
                dataSource: DataSource,
                isFirstResource: Boolean
            ): Boolean {
                defaultCover = false
                return false
            }

        }
    }

    fun load(
        path: String? = null,
        name: String? = null,
        author: String? = null,
        loadOnlyWifi: Boolean = false,
        sourceOrigin: String? = null,
        fragment: Fragment? = null,
        lifecycle: Lifecycle? = null,
        onLoadFinish: (() -> Unit)? = null
    ) {
        this.bitmapPath = path
        this.name = name?.replace(AppPattern.bdRegex, "")?.trim()
        this.author = author?.replace(AppPattern.bdRegex, "")?.trim()
        defaultCover = true
        invalidate()
        if (AppConfig.useDefaultCover) {
            ImageLoader.load(context, BookCover.defaultDrawable)
                .centerCrop()
                .into(this)
        } else {
            var options = RequestOptions().set(OkHttpModelLoader.loadOnlyWifiOption, loadOnlyWifi)
            if (sourceOrigin != null) {
                options = options.set(OkHttpModelLoader.sourceOriginOption, sourceOrigin)
            }
            var builder = if (fragment != null && lifecycle != null) {
                ImageLoader.load(fragment, lifecycle, path)
            } else {
                ImageLoader.load(context, path)//Glide自动识别http://,content://和file://
            }
            builder = builder.apply(options)
                .placeholder(BookCover.defaultDrawable)
                .error(BookCover.defaultDrawable)
                .listener(glideListener)
            if (onLoadFinish != null) {
                builder = builder.addListener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable?>,
                        isFirstResource: Boolean
                    ): Boolean {
                        onLoadFinish.invoke()
                        return false
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable?>?,
                        dataSource: DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        onLoadFinish.invoke()
                        return false
                    }
                })
            }
            builder
                .centerCrop()
                .into(this)
        }
    }

}
