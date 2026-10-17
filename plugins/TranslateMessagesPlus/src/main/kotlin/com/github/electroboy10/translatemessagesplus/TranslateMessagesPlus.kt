package com.github.electroboy10.translatemessagesplus

import android.content.Context
import android.text.InputType
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.core.widget.TextViewCompat
import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.CommandsAPI
import com.aliucord.entities.Plugin
import com.aliucord.fragments.InputDialog
import com.aliucord.fragments.SelectDialog
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.discord.api.channel.Channel
import com.discord.api.channel.ChannelUtils
import com.discord.api.commands.ApplicationCommandType
import com.discord.models.message.Message
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.widgets.channels.list.WidgetChannelsListItemChannelActions
import com.discord.widgets.chat.list.actions.WidgetChatListActions
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.MessageEntry
import com.facebook.drawee.span.DraweeSpanStringBuilder
import com.lytefast.flexinput.R
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@AliucordPlugin
class TranslateMessagesPlus : Plugin() {
    private class Translation(
        val original: String,
        val target: String,
        val text: String,
        val source: String?,
        val channelId: Long,
        val auto: Boolean,
        @Volatile var shown: Boolean,
    )

    // Kept in memory only (never written to disk) and capped, least recently used first out.
    private val translations: MutableMap<Long, Translation> = Collections.synchronizedMap(
        object : LinkedHashMap<Long, Translation>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Translation>?): Boolean =
                size > MAX_CACHED
        },
    )
    private val pending = ConcurrentHashMap.newKeySet<Long>()

    // Auto-translate failures are not retried on every rebind; manual requests still retry.
    private val failed = ConcurrentHashMap.newKeySet<Long>()
    private val chatModes = ConcurrentHashMap<Long, Mode>()
    private var executor: ExecutorService? = null
    private var adapterRef: WeakReference<WidgetChatListAdapter>? = null
    private val translateButtonId = View.generateViewId()
    private val replyButtonId = View.generateViewId()
    private val chatButtonId = View.generateViewId()

    // Set while Discord renders a message whose translation is shown.
    private val rendering = ThreadLocal<Translation?>()

    init {
        settingsTab = SettingsTab(TranslateMessagesPlusSettings::class.java, SettingsTab.Type.PAGE).withArgs(this)
    }

    var targetLanguage: String
        get() = settings.getString(KEY_LANGUAGE, null) ?: Languages.deviceDefault()
        set(value) {
            settings.setString(KEY_LANGUAGE, value)
            translations.clear()
            failed.clear()
            refreshChat()
        }

    /** What chats without their own setting do. */
    var defaultMode: Mode
        get() = Mode.fromId(settings.getString(KEY_DEFAULT_MODE, null)) ?: Mode.OFF
        set(value) {
            settings.setString(KEY_DEFAULT_MODE, value.id)
            dropAutoTranslations(null)
            failed.clear()
            refreshChat()
        }

    var previewReplies: Boolean
        get() = settings.getBool(KEY_PREVIEW, true)
        set(value) = settings.setBool(KEY_PREVIEW, value)

    var tagColor: String
        get() = TagColors.find(settings.getString(KEY_TAG_COLOR, null)).id
        set(value) {
            settings.setString(KEY_TAG_COLOR, value)
            refreshChat()
        }

    /** One of [SERVICE_GOOGLE], [SERVICE_DEEPL] or [SERVICE_LIBRE]. */
    var serviceId: String
        // Before the service picker existed, a filled-in API URL meant LibreTranslate.
        get() = settings.getString(KEY_SERVICE, null) ?: if (apiUrl.isNotEmpty()) SERVICE_LIBRE else SERVICE_GOOGLE
        set(value) {
            settings.setString(KEY_SERVICE, value)
            serviceChanged()
        }

    var apiUrl: String
        get() = settings.getString(KEY_API_URL, "").trim()
        set(value) {
            settings.setString(KEY_API_URL, value.trim())
            serviceChanged()
        }

    var apiKey: String
        get() = settings.getString(KEY_API_KEY, "").trim()
        set(value) {
            settings.setString(KEY_API_KEY, value.trim())
            failed.clear()
        }

    var deeplKey: String
        get() = settings.getString(KEY_DEEPL_KEY, "").trim()
        set(value) {
            settings.setString(KEY_DEEPL_KEY, value.trim())
            failed.clear()
        }

    private val service: Translator.Service
        get() = when (serviceId) {
            SERVICE_DEEPL -> Translator.Service.DeepL(deeplKey)
            SERVICE_LIBRE -> Translator.Service.Libre(apiUrl, apiKey)
            else -> Translator.Service.Google
        }

    private fun serviceChanged() {
        translations.clear()
        failed.clear()
        refreshChat()
    }

    // ---- Per-chat settings -------------------------------------------------------------------

    /** The chat's own setting, or null when it follows the default. */
    fun chatModeOverride(channelId: Long): Mode? = chatModes[channelId]

    fun chatOverrides(): List<Pair<Long, Mode>> = chatModes.entries.map { it.key to it.value }

    fun setChatMode(channelId: Long, mode: Mode?) {
        if (mode == null) chatModes.remove(channelId) else chatModes[channelId] = mode
        saveChatModes()
        dropAutoTranslations(channelId)
        failed.clear()
        refreshChat()
    }

    /** The mode that applies to a chat: its own, else its parent channel's (for threads), else the default. */
    fun modeFor(channelId: Long): Mode {
        chatModes[channelId]?.let { return it }
        val parent = threadParent(channelId)
        if (parent != null) chatModes[parent]?.let { return it }
        return defaultMode
    }

    private fun threadParent(channelId: Long): Long? =
        try {
            val channel = StoreStream.getChannels().getChannel(channelId)
            if (channel != null && channel.D() in THREAD_TYPES) channel.u().takeIf { it != 0L } else null
        } catch (e: Throwable) {
            null
        }

    private fun loadChatModes() {
        chatModes.clear()
        try {
            val json = JSONObject(settings.getString(KEY_CHAT_MODES, "{}"))
            for (key in json.keys()) {
                val id = key.toLongOrNull() ?: continue
                val mode = Mode.fromId(json.optString(key)) ?: continue
                chatModes[id] = mode
            }
        } catch (e: Throwable) {
            logger.error("Could not read per-chat settings", e)
        }
    }

    private fun saveChatModes() {
        val json = JSONObject()
        for ((id, mode) in chatModes) json.put(id.toString(), mode.id)
        settings.setString(KEY_CHAT_MODES, json.toString())
    }

    /** Forgets automatic translations (of one chat, or all) so a changed mode takes effect straight away. */
    private fun dropAutoTranslations(channelId: Long?) {
        synchronized(translations) {
            val iterator = translations.values.iterator()
            while (iterator.hasNext()) {
                val translation = iterator.next()
                if (translation.auto && (channelId == null || translation.channelId == channelId)) iterator.remove()
            }
        }
    }

    /** A readable name for a chat, for lists and toasts. */
    fun chatName(channelId: Long): String =
        try {
            val channel = StoreStream.getChannels().getChannel(channelId)
            if (channel == null) {
                "Unknown chat ($channelId)"
            } else {
                val name = ChannelUtils.d(channel, Utils.appContext, true)
                val type = channel.D()
                val guildName = if (type == 1 || type == 3) null else StoreStream.getGuilds().getGuild(channel.i())?.name
                if (guildName != null) "$name · $guildName" else name
            }
        } catch (e: Throwable) {
            "Chat $channelId"
        }

    /** Asks which mode to use. [current] null means "follows the default" and is only offered when [allowDefault]. */
    fun showModePicker(title: String, current: Mode?, allowDefault: Boolean, onPick: (Mode?) -> Unit) {
        val choices = ArrayList<Mode?>()
        if (allowDefault) choices.add(null)
        for (mode in Mode.values()) choices.add(mode)
        val defaultLabel = "Use default (${defaultMode.label})"
        val dialog = SelectDialog()
        dialog.title = title
        dialog.items = choices.map { mode ->
            val label = mode?.label ?: defaultLabel
            if (mode == current) "$label ✓" else label
        }.toTypedArray()
        dialog.onResultListener = { which -> onPick(choices[which]) }
        dialog.show(Utils.appActivity.supportFragmentManager, "TranslateMode")
    }

    private fun pickChatMode(channelId: Long) {
        val name = chatName(channelId)
        showModePicker(name, chatModes[channelId], true) { pick ->
            setChatMode(channelId, pick)
            Utils.showToast("Auto-translate for $name: ${modeFor(channelId).label}")
        }
    }

    // ---- Patches -----------------------------------------------------------------------------

    private inline fun safePatch(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            logger.error("Could not patch $what", e)
        }
    }

    override fun start(context: Context) {
        executor = Executors.newFixedThreadPool(2)
        loadChatModes()

        // Message rendering: decide per message whether to show (or fetch) a translation.
        safePatch("message rendering") {
            val processMessageText = WidgetChatListAdapterItemMessage::class.java.getDeclaredMethod(
                "processMessageText",
                SimpleDraweeSpanTextView::class.java,
                MessageEntry::class.java,
            )
            patcher.patch(
                processMessageText,
                PreHook { call ->
                    val item = call.thisObject as WidgetChatListAdapterItemMessage
                    adapterRef = WeakReference(item.adapter as WidgetChatListAdapter)
                    val message = (call.args[1] as MessageEntry).message
                    val content = message.content.orEmpty()
                    val translation = translations[message.id]?.takeIf { it.original == content && it.target == targetLanguage }
                    if (translation != null) {
                        rendering.set(translation.takeIf { it.shown })
                    } else {
                        rendering.set(null)
                        if (message.id !in failed &&
                            shouldAutoTranslate(content) &&
                            modeFor(message.channelId).applies(isOwn(message))
                        ) {
                            requestTranslation(message.id, message.channelId, content, show = true, manual = false)
                        }
                    }
                },
            )
            patcher.patch(processMessageText, Hook { rendering.remove() })
        }
        safePatch("message content") {
            patcher.patch(
                Message::class.java.getDeclaredMethod("getContent"),
                Hook { call ->
                    val translation = rendering.get() ?: return@Hook
                    if (call.result == translation.original) call.result = translation.text
                },
            )
        }
        safePatch("translated tag") {
            patcher.patch(
                SimpleDraweeSpanTextView::class.java.getDeclaredMethod(
                    "setDraweeSpanStringBuilder",
                    DraweeSpanStringBuilder::class.java,
                ),
                PreHook { call ->
                    val translation = rendering.get() ?: return@PreHook
                    val builder = call.args[0] as DraweeSpanStringBuilder
                    val view = call.thisObject as View
                    // Sized like Discord's "(edited)" tag, in the colour chosen in settings.
                    val start = builder.length
                    val source = translation.source?.let { "${Languages.displayCode(it)} → " }.orEmpty()
                    builder.append(" (translated $source${Languages.displayCode(translation.target)})")
                    builder.setSpan(RelativeSizeSpan(0.75f), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    val color = TagColors.find(tagColor).color ?: ColorCompat.getThemedColor(view.context, R.b.colorTextMuted)
                    builder.setSpan(ForegroundColorSpan(color), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                },
            )
        }

        // Long-press menu of a message.
        safePatch("message menu") {
            patcher.patch(
                WidgetChatListActions::class.java.getDeclaredMethod("configureUI", WidgetChatListActions.Model::class.java),
                Hook { call -> configureMenu(call.thisObject as WidgetChatListActions, call.args[0] as WidgetChatListActions.Model) },
            )
        }

        // Long-press menu of a channel or DM in the list.
        safePatch("channel menu") {
            patcher.patch(
                WidgetChannelsListItemChannelActions::class.java,
                "configureUI",
                arrayOf(WidgetChannelsListItemChannelActions.Model::class.java),
                Hook { frame ->
                    try {
                        val model = frame.args[0] as? WidgetChannelsListItemChannelActions.Model ?: return@Hook
                        configureChannelSheet(frame.thisObject as WidgetChannelsListItemChannelActions, model.channel)
                    } catch (e: Throwable) {
                        logger.error("Could not add the auto-translate entry to the channel menu", e)
                    }
                },
            )
        }

        safePatch("/translate command") { registerCommand() }
    }

    private fun registerCommand() {
        val options = listOf(
            Utils.createCommandOption(
                ApplicationCommandType.STRING,
                "language",
                "Language to translate into",
                required = true,
                choices = Languages.all.map { (code, name) -> Utils.createCommandChoice(name, code) },
            ),
            Utils.createCommandOption(ApplicationCommandType.STRING, "message", "Message to translate", required = true),
        )
        commands.registerCommand("translate", "Translate a message and send it", options) { ctx ->
            val target = ctx.getRequiredString("language")
            val content = ctx.getRequiredString("message")
            try {
                val result = Translator.translate(content, target, service)
                CommandsAPI.CommandResult(result.text, null, true)
            } catch (e: Throwable) {
                logger.error("Failed to translate outgoing message", e)
                val reason = (e as? Translator.TranslationException)?.message
                CommandsAPI.CommandResult(if (reason != null) "Translation failed: $reason" else "Translation failed", null, false)
            }
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        executor?.shutdownNow()
        executor = null
        translations.clear()
        pending.clear()
        failed.clear()
        refreshChat()
        adapterRef = null
    }

    // ---- Channel long-press menu -------------------------------------------------------------

    private fun configureChannelSheet(sheet: WidgetChannelsListItemChannelActions, channel: Channel) {
        val root = sheet.view ?: return
        val anchor = root.findViewById<View>(Utils.getResId("text_action_mark_as_read", "id")) ?: return
        val parent = anchor.parent as? ViewGroup ?: return
        // Discord reconfigures the same sheet when the channel model changes.
        val existing = parent.findViewWithTag<TextView>(SHEET_TAG)
        if (channel.D() !in CHAT_TYPES) {
            existing?.let(parent::removeView)
            return
        }
        val row = existing ?: cloneSheetRow(parent.context)?.also {
            val divider = parent.findViewById<View>(Utils.getResId("developer_divider", "id"))
            val index = divider?.let(parent::indexOfChild) ?: -1
            parent.addView(it, if (index >= 0) index else parent.childCount)
        } ?: return
        val channelId = channel.k()
        row.text = "Auto-translate: ${modeFor(channelId).label}"
        row.setOnClickListener {
            sheet.dismiss()
            pickChatMode(channelId)
        }
    }

    /** Inflates a real row from Discord's layout so text appearance, padding and touch feedback match. */
    private fun cloneSheetRow(context: Context): TextView? {
        val layoutId = Utils.getResId("widget_channels_list_item_actions", "layout")
        val rowId = Utils.getResId("text_action_mark_as_read", "id")
        if (layoutId == 0 || rowId == 0) return null
        val template = LayoutInflater.from(context).inflate(layoutId, null)
        val row = template.findViewById<TextView>(rowId) ?: return null
        (row.parent as? ViewGroup)?.removeView(row)
        row.id = View.generateViewId()
        row.tag = SHEET_TAG
        row.visibility = View.VISIBLE
        val tint = TextViewCompat.getCompoundDrawableTintList(row)
        val icon = iconOf(context, "ic_locale_24dp")
        if (icon != null) {
            row.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
            TextViewCompat.setCompoundDrawableTintList(row, tint)
        }
        return row
    }

    // ---- Message long-press menu -------------------------------------------------------------

    private fun configureMenu(actions: WidgetChatListActions, model: WidgetChatListActions.Model) {
        val layout = (actions.view as? NestedScrollView)?.getChildAt(0) as? LinearLayout ?: return
        val message = model.message
        val content = message.content.orEmpty()
        val channelId = message.channelId

        // All three are created together so they always sit in the same order, at the bottom.
        val translateButton = layout.findViewById<TextView>(translateButtonId) ?: createButton(layout, translateButtonId, "ic_locale_24dp")
        val replyButton = layout.findViewById<TextView>(replyButtonId) ?: createButton(layout, replyButtonId, "ic_reply_24dp", "ic_reply_white_24dp", "ic_locale_24dp")
        val chatButton = layout.findViewById<TextView>(chatButtonId) ?: createButton(layout, chatButtonId, "ic_locale_24dp")

        // Auto-translate for this chat. Shown on every message.
        try {
            chatButton.visibility = View.VISIBLE
            chatButton.text = "Auto-translate: ${modeFor(channelId).label}"
            chatButton.setOnClickListener {
                actions.dismiss()
                pickChatMode(channelId)
            }
        } catch (e: Throwable) {
            logger.error("Could not set up the auto-translate button", e)
        }

        if (!shouldTranslate(content)) {
            translateButton.visibility = View.GONE
            replyButton.visibility = View.GONE
            return
        }

        val translation = translations[message.id]?.takeIf { it.original == content && it.target == targetLanguage }
        val showing = translation?.shown == true
        translateButton.visibility = View.VISIBLE
        translateButton.text = if (showing) "Show original" else "Translate"
        translateButton.setOnClickListener {
            actions.dismiss()
            when {
                showing -> {
                    translation!!.shown = false
                    refreshMessage(message.id)
                }

                translation != null && !Languages.same(translation.source, translation.target) -> {
                    translation.shown = true
                    refreshMessage(message.id)
                }

                else -> {
                    Utils.showToast("Translating…")
                    requestTranslation(message.id, channelId, content, show = true, manual = true)
                }
            }
        }

        // Reply in the language the message was written in; only once it has been translated.
        val replyLanguage = translation?.source?.let { Languages.normalize(it) }
        if (translation != null && replyLanguage != null && !Languages.same(replyLanguage, translation.target)) {
            val guildId: Long? = model.guild?.id ?: message.guildId
            val messageId = message.id
            replyButton.visibility = View.VISIBLE
            replyButton.text = "Reply (translate to ${Languages.name(replyLanguage)})"
            replyButton.setOnClickListener {
                actions.dismiss()
                showReplyInput(messageId, channelId, guildId, replyLanguage, "")
            }
        } else {
            replyButton.visibility = View.GONE
        }
    }

    private fun createButton(layout: LinearLayout, buttonId: Int, vararg iconNames: String): TextView {
        val copy = layout.findViewById<TextView>(Utils.getResId("dialog_chat_actions_copy", "id"))
        return TextView(layout.context, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
            id = buttonId
            val icon = iconNames.firstNotNullOfOrNull { iconOf(context, it) }
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
            if (copy != null) {
                setTextColor(copy.textColors)
                TextViewCompat.setCompoundDrawableTintList(this, TextViewCompat.getCompoundDrawableTintList(copy))
            }
            layout.addView(this)
        }
    }

    private fun iconOf(context: Context, name: String) =
        Utils.getResId(name, "drawable").takeIf { it != 0 }?.let { ContextCompat.getDrawable(context, it)?.mutate() }

    // ---- Translated reply --------------------------------------------------------------------

    private fun multiLine() = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES

    private fun showReplyInput(messageId: Long, channelId: Long, guildId: Long?, language: String, prefill: String) {
        val preview = previewReplies
        val languageName = Languages.name(language)
        val dialog = InputDialog()
        dialog.setTitle("Reply in $languageName")
        dialog.setDescription("Type in any language. It is translated to $languageName before it is sent.")
        dialog.setPlaceholderText("Your reply")
        dialog.setInputType(multiLine())
        dialog.setOnDialogShownListener {
            dialog.getOKButton().text = if (preview) "Translate" else "Send"
            if (prefill.isNotEmpty()) {
                dialog.getInputLayout().editText?.apply {
                    setText(prefill)
                    setSelection(prefill.length)
                }
            }
        }
        dialog.setOnOkListener {
            val text = dialog.getInput().trim()
            if (text.isEmpty()) {
                Utils.showToast("Type something first")
            } else {
                dialog.dismiss()
                translateReply(text, language, messageId, channelId, guildId, preview)
            }
        }
        dialog.show(Utils.appActivity.supportFragmentManager, "TranslateReply")
    }

    private fun translateReply(text: String, language: String, messageId: Long, channelId: Long, guildId: Long?, preview: Boolean) {
        val pool = executor ?: return
        val currentService = service
        Utils.showToast("Translating…")
        pool.execute {
            try {
                val result = Translator.translate(text, language, currentService)
                if (preview) {
                    Utils.mainThread.post { showReplyPreview(text, result.text, language, messageId, channelId, guildId) }
                } else {
                    postReply(result.text, messageId, channelId, guildId)
                }
            } catch (e: Throwable) {
                logger.error("Failed to translate reply", e)
                val reason = (e as? Translator.TranslationException)?.message
                Utils.mainThread.post {
                    Utils.showToast(if (reason != null) "Translation failed: $reason" else "Translation failed")
                }
            }
        }
    }

    private fun showReplyPreview(original: String, translated: String, language: String, messageId: Long, channelId: Long, guildId: Long?) {
        val dialog = InputDialog()
        dialog.setTitle("Send this translation?")
        dialog.setDescription("You wrote:\n$original\n\nIn ${Languages.name(language)} (you can edit it):")
        dialog.setPlaceholderText("Translated reply")
        dialog.setInputType(multiLine())
        dialog.setOnDialogShownListener {
            dialog.getOKButton().text = "Send"
            dialog.getInputLayout().editText?.apply {
                setText(translated)
                setSelection(translated.length)
            }
        }
        dialog.setOnOkListener {
            val text = dialog.getInput().trim()
            if (text.isEmpty()) {
                Utils.showToast("The reply is empty")
            } else {
                dialog.dismiss()
                val pool = executor
                pool?.execute { postReply(text, messageId, channelId, guildId) }
            }
        }
        dialog.show(Utils.appActivity.supportFragmentManager, "TranslateReplyPreview")
    }

    /** Sends [text] as a reply to the message. Runs on a worker thread. */
    private fun postReply(text: String, messageId: Long, channelId: Long, guildId: Long?) {
        try {
            val reference = mutableMapOf<String, Any>(
                "message_id" to messageId.toString(),
                "channel_id" to channelId.toString(),
                "fail_if_not_exists" to false,
            )
            if (guildId != null && guildId != 0L) reference["guild_id"] = guildId.toString()
            val body = mapOf("content" to text, "tts" to false, "message_reference" to reference)
            Http.Request.newDiscordRNRequest("/channels/$channelId/messages", "POST").use { request ->
                request.executeWithJson(body).use { it.assertOk() }
            }
        } catch (e: Throwable) {
            logger.error("Failed to send translated reply", e)
            Utils.mainThread.post { Utils.showToast("Couldn't send the reply") }
        }
    }

    // ---- Translating -------------------------------------------------------------------------

    private fun requestTranslation(messageId: Long, channelId: Long, content: String, show: Boolean, manual: Boolean) {
        val pool = executor ?: return
        if (!pending.add(messageId)) return
        val target = targetLanguage
        val currentService = service
        pool.execute {
            try {
                val result = Translator.translate(content, target, currentService)
                val alreadyTarget = Languages.same(result.sourceLanguage, target) || result.text == content
                translations[messageId] = Translation(
                    content,
                    target,
                    result.text,
                    result.sourceLanguage,
                    channelId,
                    !manual,
                    show && !alreadyTarget,
                )
                Utils.mainThread.post {
                    if (alreadyTarget) {
                        if (manual) Utils.showToast("Message is already in ${Languages.name(target)}")
                    } else {
                        refreshMessage(messageId)
                    }
                }
            } catch (e: Throwable) {
                logger.error("Failed to translate message $messageId", e)
                if (!manual) failed += messageId
                if (manual) {
                    val reason = (e as? Translator.TranslationException)?.message
                    Utils.mainThread.post {
                        Utils.showToast(if (reason != null) "Translation failed: $reason" else "Translation failed")
                    }
                }
            } finally {
                pending.remove(messageId)
            }
        }
    }

    private fun refreshMessage(messageId: Long) {
        val adapter = adapterRef?.get() ?: return
        val index = adapter.internalData.indexOfFirst { (it as? MessageEntry)?.message?.id == messageId }
        if (index >= 0) adapter.notifyItemChanged(index)
    }

    private fun refreshChat() {
        Utils.mainThread.post { adapterRef?.get()?.notifyDataSetChanged() }
    }

    private fun isOwn(message: Message) = message.author?.id == StoreStream.getUsers().me.id

    private fun shouldTranslate(content: String) = content.any { Character.isLetter(it) }

    // Very short messages ("ok", "lol") are not worth a request when translating automatically.
    private fun shouldAutoTranslate(content: String) = content.count { Character.isLetter(it) } >= 3

    companion object {
        private const val KEY_LANGUAGE = "targetLanguage"
        private const val KEY_DEFAULT_MODE = "defaultMode"
        private const val KEY_CHAT_MODES = "chatModes"
        private const val KEY_PREVIEW = "previewReplies"
        private const val KEY_TAG_COLOR = "tagColor"
        private const val KEY_SERVICE = "service"
        private const val KEY_DEEPL_KEY = "deeplKey"
        private const val KEY_API_URL = "apiUrl"
        private const val KEY_API_KEY = "apiKey"

        const val SERVICE_GOOGLE = "google"
        const val SERVICE_DEEPL = "deepl"
        const val SERVICE_LIBRE = "libre"

        private const val MAX_CACHED = 1000
        private const val SHEET_TAG = "TranslateMessagesPlus.AutoTranslate"

        // Thread, announcement thread and private thread channels.
        private val THREAD_TYPES = setOf(10, 11, 12)

        // Text, voice (text chat), DM, group DM, announcement, threads and forum posts.
        private val CHAT_TYPES = setOf(0, 1, 2, 3, 5, 10, 11, 12, 15)
    }
}
