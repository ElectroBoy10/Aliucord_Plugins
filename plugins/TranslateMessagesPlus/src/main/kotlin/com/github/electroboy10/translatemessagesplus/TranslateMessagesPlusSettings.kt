package com.github.electroboy10.translatemessagesplus

import android.content.Intent
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.fragments.SelectDialog
import com.aliucord.fragments.SettingsPage
import com.aliucord.utils.DimenUtils
import com.aliucord.views.TextInput
import com.discord.views.CheckedSetting
import com.lytefast.flexinput.R

private class ChatRow(val id: Long, val name: String, val mode: Mode)

class TranslateMessagesPlusSettings(private val plugin: TranslateMessagesPlus) : SettingsPage() {
    private var languageSubtitle: TextView? = null
    private var defaultSubtitle: TextView? = null
    private var colorSubtitle: TextView? = null
    private var serviceSubtitle: TextView? = null
    private var chatsContainer: LinearLayout? = null
    private var serviceOptions: LinearLayout? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("TranslateMessagesPlus")
        setActionBarSubtitle("Message translation")

        section("Translation")
        languageSubtitle = action("Translate to", Languages.label(plugin.targetLanguage)) { pickLanguage() }
        colorSubtitle = action("Translated tag colour", TagColors.find(plugin.tagColor).label) { pickColor() }
        add(
            Utils
                .createCheckedSetting(
                    requireContext(),
                    CheckedSetting.ViewType.SWITCH,
                    "Preview translated replies",
                    "Check and edit the translation before a reply is sent",
                ).apply {
                    isChecked = plugin.previewReplies
                    setOnCheckedListener { plugin.previewReplies = it }
                },
        )

        divider()
        section("Auto-translate")
        defaultSubtitle = action("Default for all chats", plugin.defaultMode.label) { pickDefault() }
        note(
            linearLayout,
            "Chats below with their own setting ignore this default. To add one, long-press a channel or DM in your list " +
                "(or a message) and choose Auto-translate. In a chat set to Everyone or My messages only, your own messages " +
                "are also sent to the translation service.",
        )
        chatsContainer = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        add(chatsContainer!!)
        showChats()

        divider()
        section("Translation service")
        serviceSubtitle = action("Service", serviceName(plugin.serviceId)) { pickService() }
        serviceOptions = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        add(serviceOptions!!)
        showServiceOptions()

        divider()
        section("About")
        note(
            linearLayout,
            "TranslateMessagesPlus is based on TranslateMessages 1.0.2 by yutaplug. Most of the translation engine, " +
                "the Google, DeepL and LibreTranslate support, the language list, the /translate command and the " +
                "settings layout come from that original plugin.\n\n" +
                "Added by ElectroBoy10, with the code written by Claude (Anthropic) using Claude Code: per-chat " +
                "auto-translate, translated replies, the tag colour and the channel settings entries.",
        )
        action("Original plugin by yutaplug", "github.com/yutaplug/yutaplugins") {
            openUrl("https://github.com/yutaplug/yutaplugins")
        }
        action("This plugin's source", "github.com/ElectroBoy10/Claude") {
            openUrl("https://github.com/ElectroBoy10/Claude")
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Throwable) {
            Utils.showToast("Couldn't open the link")
        }
    }

    /** Lists the chats that have their own setting. Tap one to change or remove it. */
    private fun showChats() {
        val container = chatsContainer ?: return
        container.removeAllViews()
        val overrides = plugin.chatOverrides()
        if (overrides.isEmpty()) {
            note(container, "No chats have their own setting yet.")
            return
        }
        overrides
            .map { ChatRow(it.first, plugin.chatName(it.first), it.second) }
            .sortedBy { it.name.lowercase() }
            .forEach { row ->
                action(row.name, row.mode.label, container) {
                    plugin.showModePicker(row.name, row.mode, true, parentFragmentManager) { pick ->
                        plugin.setChatMode(row.id, pick)
                        showChats()
                    }
                }
            }
    }

    private fun pickDefault() {
        plugin.showModePicker("Default for all chats", plugin.defaultMode, false, parentFragmentManager) { pick ->
            if (pick == null) return@showModePicker
            plugin.defaultMode = pick
            defaultSubtitle?.text = pick.label
        }
    }

    private fun pickColor() {
        val current = plugin.tagColor
        SelectDialog().apply {
            title = "Translated tag colour"
            items = TagColors.all.map { if (it.id == current) "${it.label} ✓" else it.label }.toTypedArray()
            onResultListener = { which ->
                val option = TagColors.all[which]
                plugin.tagColor = option.id
                colorSubtitle?.text = option.label
            }
        }.show(Utils.appActivity.supportFragmentManager, "TranslateTagColor")
    }

    private fun serviceName(id: String) = services.firstOrNull { it.first == id }?.second ?: services[0].second

    private fun pickService() {
        val current = plugin.serviceId
        SelectDialog().apply {
            title = "Translation service"
            items = services.map { (id, name) -> if (id == current) "$name ✓" else name }.toTypedArray()
            onResultListener = { which ->
                plugin.serviceId = services[which].first
                serviceSubtitle?.text = services[which].second
                showServiceOptions()
            }
        }.show(Utils.appActivity.supportFragmentManager, "TranslateService")
    }

    /** Shows only the fields the selected service uses. */
    private fun showServiceOptions() {
        val container = serviceOptions ?: return
        container.removeAllViews()
        when (plugin.serviceId) {
            TranslateMessagesPlus.SERVICE_DEEPL -> {
                note(container, "Find your key in your DeepL account under API Keys & Limits.")
                input(container, "DeepL API key", plugin.deeplKey, InputType.TYPE_TEXT_VARIATION_PASSWORD) {
                    plugin.deeplKey = it
                }
            }

            TranslateMessagesPlus.SERVICE_LIBRE -> {
                note(container, "Any LibreTranslate-compatible endpoint, such as https://libretranslate.com/translate.")
                input(container, "API URL", plugin.apiUrl, InputType.TYPE_TEXT_VARIATION_URI) { plugin.apiUrl = it }
                input(container, "API key (optional)", plugin.apiKey, InputType.TYPE_TEXT_VARIATION_PASSWORD) {
                    plugin.apiKey = it
                }
            }

            else -> note(container, "Free and needs no API key.")
        }
    }

    private fun pickLanguage() {
        val languages = Languages.all
        val current = plugin.targetLanguage
        // Aliucord's SelectDialog is built from Discord's themed dialog, unlike a plain AlertDialog.
        SelectDialog().apply {
            title = "Translate to"
            items = languages.map { (code, _) ->
                val label = Languages.label(code)
                if (code == current) "$label ✓" else label
            }.toTypedArray()
            onResultListener = { which ->
                val code = languages[which].first
                plugin.targetLanguage = code
                languageSubtitle?.text = Languages.label(code)
            }
        }.show(Utils.appActivity.supportFragmentManager, "TranslateLanguage")
    }

    private fun input(parent: LinearLayout, hint: String, value: String, variation: Int, onChange: (String) -> Unit) {
        val input = TextInput(requireContext(), hint, value)
        input.editText.apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or variation
            addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                    override fun afterTextChanged(s: Editable?) = onChange(s?.toString().orEmpty())
                },
            )
        }
        parent.addView(
            input,
            LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(DimenUtils.dpToPx(16), 0, DimenUtils.dpToPx(16), DimenUtils.dpToPx(8))
            },
        )
    }

    private fun section(title: String) {
        add(
            TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_Header).apply {
                text = title
                setPaddingRelative(paddingStart, paddingTop, paddingEnd, 0)
            },
        )
    }

    private fun note(parent: LinearLayout, text: String) {
        parent.addView(
            TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_SubText).apply {
                this.text = text
                background = null
                setPaddingRelative(paddingStart, DimenUtils.dpToPx(4), paddingEnd, DimenUtils.dpToPx(8))
            },
            LinearLayout.LayoutParams(-1, -2),
        )
    }

    /** Returns the subtitle view so it can be updated. */
    private fun action(title: String, subtitle: String, parent: LinearLayout = linearLayout, onClick: () -> Unit): TextView {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val ripple = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
                setBackgroundResource(ripple.resourceId)
            }
            isFocusable = true
            setOnClickListener { onClick() }
        }
        row.addView(
            TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_Label).apply {
                text = title
                background = null
                setPaddingRelative(paddingStart, DimenUtils.dpToPx(8), paddingEnd, 0)
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        val sub = TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_SubText).apply {
            text = subtitle
            background = null
            setPadding(DimenUtils.dpToPx(16), 0, DimenUtils.dpToPx(16), DimenUtils.dpToPx(8))
        }
        row.addView(sub, LinearLayout.LayoutParams(-1, -2))
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
        return sub
    }

    private fun divider() {
        linearLayout.addView(
            View(requireContext(), null, 0, R.i.UiKit_Settings_Divider),
            LinearLayout.LayoutParams(-1, DimenUtils.dpToPx(1)),
        )
    }

    private fun add(view: View) {
        linearLayout.addView(view, LinearLayout.LayoutParams(-1, -2))
    }

    override fun onDestroyView() {
        languageSubtitle = null
        defaultSubtitle = null
        colorSubtitle = null
        serviceSubtitle = null
        chatsContainer = null
        serviceOptions = null
        super.onDestroyView()
    }

    companion object {
        private val services = listOf(
            TranslateMessagesPlus.SERVICE_GOOGLE to "Google Translate (default)",
            TranslateMessagesPlus.SERVICE_DEEPL to "DeepL",
            TranslateMessagesPlus.SERVICE_LIBRE to "LibreTranslate",
        )
    }
}
