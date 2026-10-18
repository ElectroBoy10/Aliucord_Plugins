package com.custommembercategories

import android.annotation.SuppressLint
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.fragments.ConfirmDialog
import com.aliucord.fragments.InputDialog
import com.aliucord.fragments.SettingsPage
import com.aliucord.wrappers.GuildRoleWrapper
import com.discord.stores.StoreStream
import com.discord.views.CheckedSetting
import com.lytefast.flexinput.R

private fun serverName(guildId: Long): String =
    StoreStream.getGuilds().getGuild(guildId)?.name ?: "This server"

/** A tappable settings row with a title and a smaller line under it. */
private fun settingsRow(ctx: android.content.Context, title: String, subtitle: String, onClick: () -> Unit): View =
    LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        isFocusable = true
        setOnClickListener { onClick() }
        addView(
            TextView(ctx, null, 0, R.i.UiKit_Settings_Item_Label).apply { text = title },
            LinearLayout.LayoutParams(-1, -2),
        )
        addView(
            TextView(ctx, null, 0, R.i.UiKit_Settings_Item_SubText).apply { text = subtitle },
            LinearLayout.LayoutParams(-1, -2),
        )
    }

/** Lists the categories of one server. Opened with the /categories command. */
class CategoriesPage(
    private val plugin: CustomMemberCategories,
    private val guildId: Long,
) : SettingsPage() {
    private var container: LinearLayout? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("Member categories [Beta]")
        setActionBarSubtitle(serverName(guildId))
        container = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        linearLayout.addView(container, LinearLayout.LayoutParams(-1, -2))
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroyView() {
        container = null
        super.onDestroyView()
    }

    @SuppressLint("SetTextI18n")
    private fun render() {
        val box = container ?: return
        box.removeAllViews()
        val categories = plugin.categories(guildId)

        box.addView(
            settingsRow(requireContext(), "Add category", "Only you see these. They sit at the top of this server's member list, in the order below. Use the arrows to reorder.") { askName() },
            LinearLayout.LayoutParams(-1, -2),
        )
        for ((index, category) in categories.withIndex()) {
            val mode = if (category.matchAll) "all of" else "any of"
            val summary = when (category.roleIds.size) {
                0 -> "No roles picked yet"
                else -> "${if (category.includeOffline) "Online and offline" else "Online"} members with $mode ${category.roleIds.size} role(s)"
            }
            box.addView(categoryRow(category, index, categories.size - 1, summary), LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun askName() {
        val dialog = InputDialog()
        dialog.setTitle("New category")
        dialog.setDescription("Name shown above the members in the list")
        dialog.setPlaceholderText("Category name")
        dialog.setOnOkListener {
            val name = dialog.getInput().trim()
            if (name.isEmpty()) {
                Utils.showToast("Type a name first")
            } else {
                dialog.dismiss()
                val category = Category(id = java.lang.Long.toString(System.nanoTime(), 36), name = name)
                plugin.categories(guildId).add(category)
                plugin.save(guildId)
                Utils.openPageWithProxy(requireContext(), CategoryPage(plugin, guildId, category.id))
            }
        }
        dialog.show(parentFragmentManager, "CustomCategoryName")
    }

    private fun categoryRow(category: Category, index: Int, last: Int, summary: String): View {
        val ctx = requireContext()
        val pad = (14 * ctx.resources.displayMetrics.density).toInt()

        fun arrow(symbol: String, enabled: Boolean, delta: Int) =
            TextView(ctx, null, 0, R.i.UiKit_Settings_Item_Label).apply {
                text = symbol
                gravity = Gravity.CENTER
                setPadding(pad, 0, pad, 0)
                alpha = if (enabled) 1f else 0.25f
                if (enabled) {
                    isFocusable = true
                    setOnClickListener {
                        plugin.moveCategory(guildId, category.id, delta)
                        render()
                    }
                }
            }

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                settingsRow(ctx, category.name, summary) {
                    Utils.openPageWithProxy(requireContext(), CategoryPage(plugin, guildId, category.id))
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            addView(arrow("\u25B2", index > 0, -1), LinearLayout.LayoutParams(-2, -1))
            addView(arrow("\u25BC", index < last, 1), LinearLayout.LayoutParams(-2, -1))
        }
    }
}

/** Edits one category: its name, its roles, and whether a member needs all of them. */
class CategoryPage(
    private val plugin: CustomMemberCategories,
    private val guildId: Long,
    private val categoryId: String,
) : SettingsPage() {
    private fun category(): Category? = plugin.categories(guildId).firstOrNull { it.id == categoryId }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val category = category()
        if (category == null) {
            setActionBarTitle("Category [Beta]")
            return
        }
        setActionBarTitle(category.name)
        setActionBarSubtitle("${serverName(guildId)} [Beta]")
        val ctx = requireContext()

        linearLayout.addView(
            Utils.createCheckedSetting(
                ctx,
                CheckedSetting.ViewType.SWITCH,
                "Need every picked role",
                "Off: a member with any picked role is shown. On: a member needs all of them.",
            ).apply {
                isChecked = category.matchAll
                setOnCheckedListener {
                    category.matchAll = it
                    plugin.save(guildId)
                }
            },
            LinearLayout.LayoutParams(-1, -2),
        )

        linearLayout.addView(
            Utils.createCheckedSetting(
                ctx,
                CheckedSetting.ViewType.SWITCH,
                "Include offline members",
                "Off: only online members. On: offline members are shown too, if Discord has loaded them into the member list.",
            ).apply {
                isChecked = category.includeOffline
                setOnCheckedListener {
                    category.includeOffline = it
                    plugin.save(guildId)
                }
            },
            LinearLayout.LayoutParams(-1, -2),
        )

        addHeader(ctx, "Roles")
        val roles = StoreStream.getGuilds().roles[guildId]
            ?.values
            ?.map { GuildRoleWrapper(it) }
            ?.filter { it.id != guildId } // skip @everyone
            ?.sortedByDescending { it.position }
            .orEmpty()
        if (roles.isEmpty()) {
            linearLayout.addView(
                TextView(ctx, null, 0, R.i.UiKit_Settings_Item_SubText).apply {
                    text = "No roles found for this server."
                },
                LinearLayout.LayoutParams(-1, -2),
            )
        }
        for (role in roles) {
            linearLayout.addView(
                Utils.createCheckedSetting(ctx, CheckedSetting.ViewType.CHECK, role.name, null).apply {
                    isChecked = role.id in category.roleIds
                    setOnCheckedListener { checked ->
                        if (checked) {
                            if (role.id !in category.roleIds) category.roleIds.add(role.id)
                        } else {
                            category.roleIds.remove(role.id)
                        }
                        plugin.save(guildId)
                    }
                },
                LinearLayout.LayoutParams(-1, -2),
            )
        }

        addDivider(ctx)
        linearLayout.addView(
            TextView(ctx, null, 0, R.i.UiKit_Settings_Item_Label).apply {
                text = "Rename category"
                isFocusable = true
                setOnClickListener { askRename(category) }
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        linearLayout.addView(
            TextView(ctx, null, 0, R.i.UiKit_Settings_Item_Label).apply {
                text = "Delete category"
                isFocusable = true
                setOnClickListener { askDelete(category) }
            },
            LinearLayout.LayoutParams(-1, -2),
        )
    }

    private fun askRename(category: Category) {
        val dialog = InputDialog()
        dialog.setTitle("Rename category")
        dialog.setPlaceholderText("Category name")
        dialog.setOnOkListener {
            val name = dialog.getInput().trim()
            if (name.isEmpty()) {
                Utils.showToast("Type a name first")
            } else {
                dialog.dismiss()
                category.name = name
                plugin.save(guildId)
                setActionBarTitle(name)
            }
        }
        dialog.show(parentFragmentManager, "CustomCategoryRename")
    }

    private fun askDelete(category: Category) {
        val dialog = ConfirmDialog()
        dialog.setTitle("Delete category")
        dialog.setDescription("Delete \"${category.name}\"? This only removes it from your app.")
        dialog.setOnOkListener {
            dialog.dismiss()
            plugin.categories(guildId).remove(category)
            plugin.save(guildId)
            requireActivity().onBackPressed()
        }
        dialog.show(parentFragmentManager, "CustomCategoryDelete")
    }
}

private class GuildRow(val id: Long, val name: String, val count: Int)

/** The plugin's main settings page: pick a server to manage its categories, plus a few options. */
class MainSettingsPage(
    private val plugin: CustomMemberCategories,
) : SettingsPage() {
    private var container: LinearLayout? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("Member categories [Beta]")
        setActionBarSubtitle("Settings")
        val ctx = requireContext()

        addHeader(ctx, "Servers")
        linearLayout.addView(
            TextView(ctx, null, 0, R.i.UiKit_Settings_Item_SubText).apply {
                text = "Pick a server to add, edit or reorder its categories. They are saved on this device only, " +
                    "and you can also open a server's categories with /categories."
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        linearLayout.addView(container, LinearLayout.LayoutParams(-1, -2))

        addDivider(ctx)
        addHeader(ctx, "Troubleshooting")
        linearLayout.addView(
            Utils.createCheckedSetting(
                ctx,
                CheckedSetting.ViewType.SWITCH,
                "Debug logging",
                "Writes what the plugin sees on every member list update to Aliucord's log. Turn on when reporting a bug.",
            ).apply {
                isChecked = plugin.debugLogging
                setOnCheckedListener { plugin.debugLogging = it }
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroyView() {
        container = null
        super.onDestroyView()
    }

    private fun render() {
        val box = container ?: return
        box.removeAllViews()
        val ctx = requireContext()
        val guilds = StoreStream.getGuilds().guilds.values
            .map { GuildRow(it.id, it.name.toString(), plugin.categories(it.id).size) }
            .sortedWith(
                Comparator { a, b ->
                    if ((a.count > 0) != (b.count > 0)) {
                        if (a.count > 0) -1 else 1
                    } else {
                        a.name.compareTo(b.name, ignoreCase = true)
                    }
                },
            )
        if (guilds.isEmpty()) {
            box.addView(
                TextView(ctx, null, 0, R.i.UiKit_Settings_Item_SubText).apply { text = "No servers found." },
                LinearLayout.LayoutParams(-1, -2),
            )
            return
        }
        for (guild in guilds) {
            val id = guild.id
            val count = guild.count
            val summary = when (count) {
                0 -> "No categories yet"
                1 -> "1 category"
                else -> "$count categories"
            }
            box.addView(
                settingsRow(ctx, guild.name, summary) {
                    Utils.openPageWithProxy(requireContext(), CategoriesPage(plugin, id))
                },
                LinearLayout.LayoutParams(-1, -2),
            )
        }
    }
}
