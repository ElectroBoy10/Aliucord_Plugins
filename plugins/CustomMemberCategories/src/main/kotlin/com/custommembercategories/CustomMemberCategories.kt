package com.custommembercategories

import android.content.Context
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.CommandsAPI
import com.aliucord.entities.Plugin
import com.aliucord.patcher.*
import com.discord.stores.StoreStream
import com.discord.widgets.channels.memberlist.adapter.ChannelMembersListAdapter
import com.discord.widgets.channels.memberlist.adapter.ChannelMembersListViewHolderHeader
import de.robv.android.xposed.XC_MethodHook
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

@AliucordPlugin
class CustomMemberCategories : Plugin() {
    private val cache = ConcurrentHashMap<Long, MutableList<Category>>()

    // key of a header we made -> (category name, member count), used to draw the header text.
    private val headerInfo = ConcurrentHashMap<String, Pair<String, Int>>()

    private var adapterRef: WeakReference<ChannelMembersListAdapter>? = null
    private var setDataMethod: Method? = null

    @Volatile
    private var lastOriginal: List<*>? = null
    private var loggedTemplate = false

    // ---- storage (per server) -------------------------------------------------------------

    fun categories(guildId: Long): MutableList<Category> = cache.getOrPut(guildId) {
        Category.parseList(settings.getString("categories_$guildId", "[]") ?: "[]")
    }

    /** Saves the categories of a server to disk and redraws the member list. */
    fun save(guildId: Long) {
        settings.setString("categories_$guildId", Category.toJsonString(categories(guildId)))
        refresh()
    }

    // ---- lifecycle ------------------------------------------------------------------------

    override fun start(context: Context) {
        hookAdapter()
        hookHeader()
        commands.registerCommand("categories", "Manage your custom member list categories for this server") {
            val guildId = it.currentChannel.guildId
            if (guildId <= 0L) {
                return@registerCommand CommandsAPI.CommandResult("Run this in a server channel.", null, false)
            }
            Utils.openPageWithProxy(Utils.appActivity, CategoriesPage(this, guildId))
            CommandsAPI.CommandResult()
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        commands.unregisterAll()
        headerInfo.clear()
    }

    // ---- hooks ----------------------------------------------------------------------------

    private fun hookAdapter() {
        val methods = generateSequence<Class<*>>(ChannelMembersListAdapter::class.java) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .filter {
                it.name == "setData" &&
                    it.parameterTypes.isNotEmpty() &&
                    List::class.java.isAssignableFrom(it.parameterTypes[0])
            }
            .toList()
        if (methods.isEmpty()) {
            logger.error("No setData method found on the member list adapter, categories cannot be drawn", null)
            return
        }
        for (method in methods) {
            logger.info("Hooking ${method.declaringClass.name}.${method.name}(${method.parameterTypes.joinToString { it.simpleName }})")
            patcher.patch(method, PreHook { call -> onSetData(call, method) })
        }
    }

    private fun hookHeader() {
        patcher.after<ChannelMembersListViewHolderHeader>(
            "bind",
            ChannelMembersListAdapter.Item.Header::class.java,
        ) { (_, header: ChannelMembersListAdapter.Item.Header) ->
            val key = keyOf(header) ?: return@after
            val info = headerInfo[key] ?: return@after
            val textView = itemView.findViewById<TextView>(
                Utils.getResId("channel_members_list_item_header_text", "id"),
            ) ?: return@after
            textView.text = "${info.first} — ${info.second}"
        }
    }

    private fun onSetData(call: XC_MethodHook.MethodHookParam, method: Method) {
        val adapter = call.thisObject as? ChannelMembersListAdapter ?: return
        val original = call.args[0] as? List<*> ?: return
        if (method.parameterCount == 1) {
            adapterRef = WeakReference(adapter)
            setDataMethod = method
        }
        if (original.any { isOurHeader(it) }) return
        lastOriginal = original
        try {
            val transformed = transform(original) ?: return
            call.args[0] = transformed
        } catch (t: Throwable) {
            logger.error("Could not build the custom categories", t)
        }
    }

    /** Feeds the last list Discord gave the adapter through the hook again, so edits show at once. */
    private fun refresh() {
        val adapter = adapterRef?.get() ?: return
        val method = setDataMethod ?: return
        val original = lastOriginal ?: return
        Utils.appActivity.runOnUiThread {
            try {
                method.isAccessible = true
                method.invoke(adapter, original)
            } catch (t: Throwable) {
                logger.error("Could not refresh the member list", t)
            }
        }
    }

    // ---- building the list ----------------------------------------------------------------

    private fun transform(list: List<*>): List<Any?>? {
        val guildId = StoreStream.getGuildSelected().selectedGuildId
        if (guildId <= 0L) return null
        val categories = categories(guildId).filter { it.roleIds.isNotEmpty() }
        if (categories.isEmpty()) return null

        val template = list.firstOrNull { it is ChannelMembersListAdapter.Item.Header } ?: return null
        logTemplateOnce(template)

        val members = list.filterIsInstance<ChannelMembersListAdapter.Item.Member>()
        val moved = HashSet<Long>()
        val top = ArrayList<Any?>()
        for (category in categories) {
            val matching = members.filter {
                it.userId !in moved && isOnline(it) && matches(category, guildId, it.userId)
            }
            if (matching.isEmpty()) continue
            val header = makeHeader(template, category, matching.size) ?: return null
            matching.forEach { moved.add(it.userId) }
            top.add(header)
            top.addAll(matching)
        }
        if (top.isEmpty()) return null

        val result = ArrayList<Any?>(list.size + 1)
        result.addAll(top)
        for (item in list) {
            if (item is ChannelMembersListAdapter.Item.Member && item.userId in moved) continue
            result.add(item)
        }
        return result
    }

    private fun isOnline(member: ChannelMembersListAdapter.Item.Member): Boolean {
        val status = member.presence?.status ?: return false
        val name = status.name
        return name != "OFFLINE" && name != "INVISIBLE"
    }

    private fun matches(category: Category, guildId: Long, userId: Long): Boolean {
        val guildMember = StoreStream.getGuilds().getMember(guildId, userId) ?: return false
        val roles = guildMember.roles ?: return false
        return if (category.matchAll) {
            category.roleIds.all { it in roles }
        } else {
            category.roleIds.any { it in roles }
        }
    }

    /**
     * Makes a header row by copying a header Discord already made and swapping in our own key.
     * The visible text is set afterwards when the row is drawn (see hookHeader).
     */
    private fun makeHeader(template: Any, category: Category, count: Int): Any? {
        val key = KEY_PREFIX + category.id
        val cls = template.javaClass
        val fields = cls.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) }
            .onEach { it.isAccessible = true }
        val types = fields.map { it.type }
        val constructor: Constructor<*> = cls.declaredConstructors.firstOrNull {
            !it.isSynthetic && it.parameterTypes.toList() == types
        } ?: run {
            logger.error("Header constructor does not match its fields: ${cls.declaredConstructors.joinToString { c -> c.parameterTypes.joinToString { p -> p.simpleName } }}", null)
            return null
        }
        constructor.isAccessible = true

        val templateKey = keyOf(template)
        val args = fields.map { field ->
            val value = field.get(template)
            if (field.type == String::class.java) {
                if (value == templateKey) key else category.name
            } else {
                value
            }
        }
        val header = constructor.newInstance(*args.toTypedArray())
        headerInfo[key] = category.name to count
        return header
    }

    private fun isOurHeader(item: Any?): Boolean =
        item is ChannelMembersListAdapter.Item.Header && keyOf(item)?.startsWith(KEY_PREFIX) == true

    private fun keyOf(item: Any): String? = try {
        item.javaClass.getMethod("getKey").invoke(item) as? String
    } catch (_: Throwable) {
        null
    }

    private fun logTemplateOnce(template: Any) {
        if (loggedTemplate) return
        loggedTemplate = true
        try {
            val fields = template.javaClass.declaredFields
                .filter { !Modifier.isStatic(it.modifiers) }
                .joinToString { f ->
                    f.isAccessible = true
                    "${f.name}:${f.type.simpleName}=${f.get(template)}"
                }
            logger.info("Header template ${template.javaClass.name} key=${keyOf(template)} fields: $fields")
        } catch (t: Throwable) {
            logger.error("Could not describe the header template", t)
        }
    }

    private companion object {
        const val KEY_PREFIX = "custom-category-"
    }
}
