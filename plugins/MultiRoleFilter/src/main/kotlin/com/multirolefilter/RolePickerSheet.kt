package com.multirolefilter

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.aliucord.Utils
import com.aliucord.utils.DimenUtils.dpToPx
import com.aliucord.widgets.BottomSheet
import com.aliucord.wrappers.GuildRoleWrapper
import com.discord.utilities.color.ColorCompat
import com.lytefast.flexinput.R

/** A searchable list of the server's roles with check boxes. */
@SuppressLint("SetTextI18n")
class RolePickerSheet(
    private val roles: List<GuildRoleWrapper>,
    initial: Set<Long>,
    private val maxRoles: Int,
    private val onApply: (Set<Long>) -> Unit,
) : BottomSheet() {
    private val chosen = LinkedHashSet<Long>(initial)
    private var list: LinearLayout? = null
    private var applyButton: TextView? = null
    private var query = ""

    override fun onViewCreated(view: View, bundle: Bundle?) {
        super.onViewCreated(view, bundle)
        val ctx = requireContext()
        val textNormal = ColorCompat.getThemedColor(ctx, R.b.colorTextNormal)
        val textMuted = ColorCompat.getThemedColor(ctx, R.b.colorTextMuted)
        val fieldBg = ColorCompat.getThemedColor(ctx, R.b.colorBackgroundSecondary)
        setPadding(dpToPx(16))

        addView(
            TextView(ctx).apply {
                text = "Choose roles"
                textSize = 20f
                setTypeface(null, Typeface.BOLD)
                setTextColor(textNormal)
            },
        )
        addView(
            TextView(ctx).apply {
                text = "Pick up to $maxRoles roles. Then choose whether members need all of them or just one."
                textSize = 13f
                setTextColor(textMuted)
                setPadding(0, dpToPx(4), 0, dpToPx(12))
            },
        )

        addView(
            EditText(ctx).apply {
                hint = "Search roles"
                setHintTextColor(textMuted)
                setTextColor(textNormal)
                textSize = 15f
                setSingleLine(true)
                setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(10).toFloat()
                    setColor(fieldBg)
                }
                addTextChangedListener(
                    object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                        override fun afterTextChanged(s: Editable?) {
                            query = s?.toString()?.trim()?.lowercase() ?: ""
                            drawList()
                        }
                    },
                )
            },
        )

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dpToPx(8), 0, dpToPx(8))
        }
        list = container
        addView(container)

        applyButton = TextView(ctx).apply {
            gravity = Gravity.CENTER
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textNormal)
            setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
            setOnClickListener {
                onApply(chosen)
                dismiss()
            }
        }
        addView(applyButton)
        drawList()
    }

    override fun onDestroyView() {
        list = null
        applyButton = null
        super.onDestroyView()
    }

    private fun drawList() {
        val container = list ?: return
        val ctx = requireContext()
        val textNormal = ColorCompat.getThemedColor(ctx, R.b.colorTextNormal)
        val textMuted = ColorCompat.getThemedColor(ctx, R.b.colorTextMuted)
        container.removeAllViews()

        val shown = roles.filter { query.isEmpty() || it.name.lowercase().contains(query) }
        if (shown.isEmpty()) {
            container.addView(
                TextView(ctx).apply {
                    text = if (roles.isEmpty()) {
                        "This server has no roles to pick from."
                    } else {
                        "No roles match “$query”."
                    }
                    setTextColor(textMuted)
                    textSize = 14f
                    setPadding(dpToPx(4), dpToPx(12), dpToPx(4), dpToPx(12))
                },
            )
        }
        for (role in shown) {
            val box = CheckBox(ctx).apply {
                isChecked = chosen.contains(role.id)
                isClickable = false
                isFocusable = false
            }
            val label = TextView(ctx).apply {
                textSize = 16f
                setTextColor(textNormal)
                val sb = SpannableStringBuilder("●  ${role.name}")
                val dot = if (role.color != 0) role.color or 0xFF000000.toInt() else textMuted
                sb.setSpan(ForegroundColorSpan(dot), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text = sb
            }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpToPx(4), dpToPx(6), dpToPx(4), dpToPx(6))
                isClickable = true
                setOnClickListener {
                    if (chosen.contains(role.id)) {
                        chosen.remove(role.id)
                    } else if (chosen.size >= maxRoles) {
                        Utils.showToast("You can pick up to $maxRoles roles.")
                    } else {
                        chosen.add(role.id)
                    }
                    box.isChecked = chosen.contains(role.id)
                    updateApply()
                }
            }
            row.addView(box)
            row.addView(label, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dpToPx(4) })
            container.addView(row)
        }
        updateApply()
    }

    private fun updateApply() {
        val button = applyButton ?: return
        val accent = ColorUtils.setAlphaComponent(ContextCompat.getColor(requireContext(), R.c.brand_new_330), 255)
        button.text = when (chosen.size) {
            0 -> "Done (no roles)"
            1 -> "Use 1 role"
            else -> "Use ${chosen.size} roles"
        }
        button.background = GradientDrawable().apply {
            cornerRadius = dpToPx(12).toFloat()
            setColor(accent)
        }
        button.setTextColor(0xFFFFFFFF.toInt())
    }
}
