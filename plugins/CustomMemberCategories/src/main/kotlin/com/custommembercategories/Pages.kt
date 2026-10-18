package com.custommembercategories

import android.annotation.SuppressLint
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
            row("Add category", "Only you see these. They sit at the top of this server's member list.") { askName() },
            LinearLayout.LayoutParams(-1, -2),
        )
        for (category in categories) {
            val mode = if (category.matchAll) "all of" else "any of"
            val summary = when (category.roleIds.size) {
                0 -> "No roles picked yet"
                else -> "Online members with $mode ${category.roleIds.size} role(s)"
            }
            box.addView(
                row(category.name, summary) {
                    Utils.openPageWithProxy(requireContext(), CategoryPage(plugin, guildId, category.id))
                },
                LinearLayout.LayoutParams(-1, -2),
            )
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

    private fun row(title: String, subtitle: String, onClick: () -> Unit): View {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
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
