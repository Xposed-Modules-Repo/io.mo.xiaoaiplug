package io.mo.xiaoaiplug.hook

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.min
import kotlin.math.roundToInt

/** 保留原生答案 TextView，在它上方插入可收起、可独立滚动的思考区域。仅在主线程使用。 */
internal object ReplyTextRenderer {
    private class ThinkingBlock(
        val root: LinearLayout,
        val region: LinearLayout,
        val header: TextView,
        val scroll: ThinkingScrollView,
        val content: TextView,
        val loading: NativeThinkingIndicator?
    )

    // Value 也使用弱引用；区域自身持有状态，避免通过 map 把原生视图永久留在内存里。
    private val blocks = WeakHashMap<TextView, WeakReference<ThinkingBlock>>()

    fun render(view: TextView, display: ReplyPresentation.Display, onToggle: () -> Unit) {
        if (view.text.toString() != display.text) view.text = display.text
        view.visibility = if (display.text.isBlank()) View.GONE else View.VISIBLE
        var block = blocks[view]?.get()
        if (block == null && display.thinking.isBlank() && !display.waiting) {
            return
        }
        if (block == null || view.parent !== block.root) {
            block = createBlock(view) ?: return
            blocks[view] = WeakReference(block)
        }
        block.loading?.setWaiting(display.waiting)
        if (display.thinking.isBlank()) {
            block.region.visibility = View.GONE
            return
        }
        block.region.visibility = View.VISIBLE
        val baseColor = view.currentTextColor
        val thinkingColor = withAlpha(baseColor, 0.6f)
        for (textView in listOf(block.header, block.content)) {
            textView.setTextColor(thinkingColor)
            textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, view.textSize * 0.85f)
            textView.typeface = view.typeface
            textView.letterSpacing = view.letterSpacing
        }
        block.region.background = GradientDrawable().apply {
            cornerRadius = dp(view.context, 10).toFloat()
            setColor(withAlpha(baseColor, 0.05f))
            setStroke(dp(view.context, 1), withAlpha(baseColor, 0.08f))
        }
        block.header.text = display.title
        block.header.contentDescription = "${display.title}，点击${if (display.expanded) "收起" else "展开"}"
        block.header.setOnClickListener { onToggle() }
        block.scroll.visibility = if (display.expanded) View.VISIBLE else View.GONE
        block.scroll.updateText(block.content, display.thinking)
    }

    private fun createBlock(answer: TextView): ThinkingBlock? {
        val parent = answer.parent as? ViewGroup ?: return null
        val index = parent.indexOfChild(answer)
        if (index < 0) return null
        val context = answer.context
        val originalParams = answer.layoutParams
        originalParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val loading = NativeThinkingIndicator.create(context)
        if (loading != null) root.addView(loading.view, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = answer.paddingLeft
            rightMargin = answer.paddingRight
        })
        val region = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
        }
        val header = TextView(context).apply {
            setPadding(0, dp(context, 4), 0, dp(context, 4))
            minHeight = dp(context, 32)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val scroll = ThinkingScrollView(context)
        val content = TextView(context).apply { setLineSpacing(dp(context, 2).toFloat(), 1f) }
        scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        region.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        region.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(context, 4) })
        root.addView(region, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = answer.paddingLeft
            rightMargin = answer.paddingRight
            bottomMargin = dp(context, 8)
        })
        // wrapper 继承原生位置/边距，原 TextView 保留 id 和 ViewHolder 引用。
        parent.removeViewAt(index)
        parent.addView(root, index, originalParams)
        root.addView(answer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        return ThinkingBlock(root, region, header, scroll, content, loading).also { root.tag = it }
    }

    private fun withAlpha(color: Int, ratio: Float): Int = Color.argb(
        (Color.alpha(color) * ratio).roundToInt(), Color.red(color), Color.green(color), Color.blue(color))

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    /** 最大 160dp；短思考自然收缩，长思考只滚动区域内部。 */
    private class ThinkingScrollView(context: Context) : ScrollView(context) {
        private var pendingScroll: Int? = null
        private var touching = false

        init {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            isNestedScrollingEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }

        fun updateText(content: TextView, text: String) {
            if (content.text.toString() == text) return
            // 阅读旧内容时保持位置；停留在底部时跟随新内容。
            pendingScroll = if (!touching && !canScrollVertically(1)) Int.MAX_VALUE else scrollY
            content.text = text
            requestLayout()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val cap = dp(context, 160)
            val available = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) cap
                else min(cap, MeasureSpec.getSize(heightMeasureSpec))
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST))
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            pendingScroll?.let { target ->
                val end = ((getChildAt(0)?.height ?: 0) - height + paddingTop + paddingBottom).coerceAtLeast(0)
                scrollTo(0, min(target, end))
                pendingScroll = null
            }
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touching = true
                    pendingScroll = null
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> pendingScroll = null
            }
            val handled = super.dispatchTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                touching = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            return handled
        }
    }
}
