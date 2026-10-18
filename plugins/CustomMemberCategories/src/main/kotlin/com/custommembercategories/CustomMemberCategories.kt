package com.custommembercategories

import android.content.Context
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.CommandsAPI
import com.aliucord.entities.Plugin
import com.aliucord.patcher.*
import com.discord.stores.StoreStream
import com.discord.widgets.channels.memberlist.WidgetChannelMembersListViewModel
import com.discord.widgets.channels.memberlist.adapter.ChannelMembersListAdapter
import com.discord.widgets.channels.memberlist.adapter.ChannelMembersListViewHolderHeader
import de.robv.android.xposed.XC_MethodHook
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

@AliucordPlugin
class CustomMemberCategories : Plugin() {
    private val cache = ConcurrentHashMap<Long, MutableList<Category>>()
    private val headerInfo = ConcurrentHashMap<String, Pair<String, Int>>()

    private var adapterRef: WeakReference<ChannelMembersListAdapter>? = null
    private var setDataMethod: Method? = null

    @Volatile
    private var lastListId: String? = null
    @Volatile
    private var lastOriginal: WidgetChannelMembersListViewModel.MemberList? = null

    fun categories(guildId: Long): MutableList<Category> = cache.getOrPut(guildId) {
        Category.parseList(settings.getString("categories_$guildId", "[]") ?: "[]")
    }

    fun save(guildId: Long) {
        settings.setString("categories_$guildId", Category.toJsonString(categories(guildId)))
        refresh()
    }

    override fun start(context: Context) {
        hookAdapter()
        hookHeader()
        commands.registerCommand("categories", "[Beta] Manage your custom member list categories for this server") {
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

    private fun hookAdapter() {
        val methods = generateSequence<Class<*>>(ChannelMembersListAdapter::class.java) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .filter {
                it.name == "setData" &&
                    it.parameterTypes.size == 3 &&
                    it.parameterTypes[0] == String::class.java &&
                    WidgetChannelMembersListViewModel.MemberList::class.java
                        .isAssignableFrom(it.parameterTypes[1]) &&
                    (it.parameterTypes[2] == Boolean::class.javaPrimitiveType ||
                        it.parameterTypes[2] == Boolean::class.java)
            }
            .toList()
        if (methods.isEmpty()) {
            logger.error("No compatible setData(String, MemberList, boolean) method found on the member list adapter", null)
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
        val listId = call.args[0] as? String ?: return
        val original = call.args[1] as? WidgetChannelMembersListViewModel.MemberList ?: return

        adapterRef = WeakReference(adapter)
        setDataMethod = method
        lastListId = listId
        lastOriginal = original

        try {
            val transformed = transform(original) ?: return
            call.args[1] = transformed
            call.args[2] = true
        } catch (t: Throwable) {
            logger.error("Could not build the custom categories", t)
        }
    }

    private fun refresh() {
        val adapter = adapterRef?.get() ?: return
        val method = setDataMethod ?: return
        val original = lastOriginal ?: return
        val listId = lastListId ?: return
        Utils.appActivity.runOnUiThread {
            try {
                method.isAccessible = true
                method.invoke(adapter, listId, original, true)
            } catch (t: Throwable) {
                logger.error("Could not refresh the member list", t)
            }
        }
    }

    private fun transform(original: WidgetChannelMembersListViewModel.MemberList): WidgetChannelMembersListViewModel.MemberList? {
        val list = List(original.size) { original.get(it) }
        val guildId = StoreStream.getGuildSelected().selectedGuildId
        if (guildId <= 0L) return null
        val categories = categories(guildId).filter { it.roleIds.isNotEmpty() }
        if (categories.isEmpty()) return null

        val members = list.filterIsInstance<ChannelMembersListAdapter.Item.Member>()
        val moved = HashSet<Long>()
        val top = ArrayList<ChannelMembersListAdapter.Item>()
        for (category in categories) {
            val matching = members.filter {
                it.userId !in moved && isOnline(it) && matches(category, guildId, it.userId)
            }
            if (matching.isEmpty()) continue
            matching.forEach { moved.add(it.userId) }
            top.add(makeHeader(category, matching.size))
            top.addAll(matching)
        }
        if (top.isEmpty()) return null

        val result = ArrayList<ChannelMembersListAdapter.Item>(list.size + top.size)
        result.addAll(top)
        for (item in list) {
            if (item is ChannelMembersListAdapter.Item.Member && item.userId in moved) continue
            result.add(item)
        }
        return CustomMemberList(original.listId, result)
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

    private fun makeHeader(category: Category, count: Int): ChannelMembersListAdapter.Item.Header {
        val key = KEY_PREFIX + category.id
        headerInfo[key] = category.name to count
        return ChannelMembersListAdapter.Item.Header(
            key,
            ChannelMembersListAdapter.Item.Header.Type.ONLINE,
            count,
        )
    }

    private fun keyOf(item: Any): String? = try {
        item.javaClass.getMethod("getKey").invoke(item) as? String
    } catch (_: Throwable) {
        try {
            item.javaClass.getMethod("getRowId").invoke(item) as? String
        } catch (_: Throwable) {
            null
        }
    }

    private class CustomMemberList(
        private val sourceListId: String,
        private val rows: List<ChannelMembersListAdapter.Item>,
    ) : WidgetChannelMembersListViewModel.MemberList {
        override fun get(index: Int): ChannelMembersListAdapter.Item = rows[index]

        override fun getHeaderPositionForItem(itemPosition: Int): Int? {
            // Plain while loop on purpose: `downTo` needs a newer Kotlin stdlib than Discord ships (crashes at runtime).
            if (itemPosition < 0 || itemPosition >= rows.size) return null
            var index = itemPosition
            while (index >= 0) {
                val item = rows[index]
                if (item is ChannelMembersListAdapter.Item.Header ||
                    item is ChannelMembersListAdapter.Item.RoleHeader) {
                    return index
                }
                index--
            }
            return null
        }

        override fun getListId(): String = sourceListId

        override fun getSize(): Int = rows.size
    }

    private companion object {
        const val KEY_PREFIX = "custom-category-"
    }
}
