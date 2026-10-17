package com.multirolefilter

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.aliucord.Http
import com.aliucord.Logger
import com.aliucord.Utils
import com.aliucord.fragments.SettingsPage
import com.aliucord.utils.DimenUtils.dpToPx
import com.aliucord.utils.RxUtils.await
import com.aliucord.wrappers.GuildRoleWrapper
import com.discord.api.role.GuildRole
import com.discord.models.user.User
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.images.MGImages
import com.discord.utilities.rest.RestAPI
import com.discord.widgets.user.usersheet.WidgetUserSheet
import com.facebook.drawee.view.SimpleDraweeView
import com.lytefast.flexinput.R
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Lists the members of a server who have all of (or any of) up to five chosen roles. */
@SuppressLint("SetTextI18n")
class RoleFilterPage(
    private val guildId: Long,
    private val channelId: Long,
    private val roleIds: List<Long>,
    private var matchAll: Boolean,
) : SettingsPage() {
    private val log = Logger("MultiRoleFilter")

    // Member ids per selected role, as returned by Discord.
    private val memberIdsByRole = LinkedHashMap<Long, Set<Long>>()
    private var loadError: String? = null

    // Roles loaded one by one from a member's profile, for members whose roles are not cached.
    private val profileRoles = ConcurrentHashMap<Long, List<Long>>()
    private var matched: List<Long> = emptyList()
    private var users: Map<Long, User> = emptyMap()
    private var shown = PAGE_SIZE
    private var loadingProfiles = false

    @Volatile
    private var destroyed = false

    private var statusView: TextView? = null
    private var allButton: TextView? = null
    private var anyButton: TextView? = null
    private var profilesButton: TextView? = null
    private var listContainer: LinearLayout? = null

    private val textNormal get() = ColorCompat.getThemedColor(requireContext(), R.b.colorTextNormal)
    private val textMuted get() = ColorCompat.getThemedColor(requireContext(), R.b.colorTextMuted)
    private val cardBase get() = ColorCompat.getThemedColor(requireContext(), R.b.colorBackgroundSecondary)

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("Role filter")
        setActionBarSubtitle("Members by role")
        val ctx = requireContext()

        // The chosen roles.
        val chips = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val roles = guildRoles()
        for (id in roleIds) {
            val role = roles[id]
            chips.addView(
                TextView(ctx).apply {
                    val color = role?.let { opaque(GuildRoleWrapper(it).color) } ?: 0
                    text = "●  ${role?.let { GuildRoleWrapper(it).name } ?: "Unknown role"}"
                    setTextColor(textNormal)
                    textSize = 15f
                    setPadding(dpToPx(4), dpToPx(2), dpToPx(4), dpToPx(2))
                    if (color != 0) {
                        val span = SpannableStringBuilder(text)
                        span.setSpan(ForegroundColorSpan(color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        text = span
                    }
                },
            )
        }
        addView(chips)

        // Match mode.
        val toggle = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dpToPx(10), 0, dpToPx(6))
        }
        allButton = segment(ctx, "Has all of them") { setMatch(true) }
        anyButton = segment(ctx, "Has any of them") { setMatch(false) }
        toggle.addView(allButton, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dpToPx(6) })
        toggle.addView(anyButton, LinearLayout.LayoutParams(0, -2, 1f))
        addView(toggle)
        styleSegments()

        statusView = TextView(ctx).apply {
            setTextColor(textMuted)
            textSize = 14f
            setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(8))
            text = "Loading members…"
        }
        addView(statusView!!)

        profilesButton = TextView(ctx).apply {
            gravity = Gravity.CENTER
            setTextColor(textNormal)
            textSize = 14f
            visibility = View.GONE
            background = pill(cardBase, ColorUtils.setAlphaComponent(textMuted, 90))
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
            setOnClickListener { loadMissingProfiles() }
        }
        addView(profilesButton!!, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpToPx(8) })

        listContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        addView(listContainer!!)

        loadRoleMembers()
    }

    override fun onDestroyView() {
        destroyed = true
        statusView = null
        allButton = null
        anyButton = null
        profilesButton = null
        listContainer = null
        super.onDestroyView()
    }

    // ---- Data --------------------------------------------------------------------------------

    private fun guildRoles(): Map<Long, GuildRole> = StoreStream.getGuilds().roles[guildId] ?: emptyMap()

    /** Fetches the member ids of every chosen role (once), then combines them. */
    private fun loadRoleMembers() {
        Utils.threadPool.execute {
            for (roleId in roleIds) {
                if (destroyed) return@execute
                val (ids, error) = RestAPI.getApi().getGuildRoleMemberIds(guildId, roleId).await()
                if (error != null || ids == null) {
                    log.error("Could not load members of role $roleId", error)
                    loadError = "Discord would not return the members of every role" +
                        (error?.message?.let { " ($it)" } ?: "") + ". You may need permission to manage roles."
                    break
                }
                memberIdsByRole[roleId] = ids.toSet()
            }
            recompute()
        }
    }

    /** Works out who matches the current mode and makes sure their user data is loaded. Runs on a worker thread. */
    private fun recompute() {
        val error = loadError
        val ids: List<Long> = if (error != null || memberIdsByRole.size < roleIds.size) {
            emptyList()
        } else {
            val result = LinkedHashSet<Long>(memberIdsByRole[roleIds[0]] ?: emptySet())
            for (roleId in roleIds.drop(1)) {
                val members = memberIdsByRole[roleId] ?: emptySet()
                if (matchAll) result.retainAll(members) else result.addAll(members)
            }
            result.toList()
        }
        val wanted = ids.take(MAX_RESOLVE)
        var found: Map<Long, User> = StoreStream.getUsers().getUsers(wanted, true)
        val missing = wanted.filter { found[it] == null }
        if (missing.isNotEmpty()) {
            StoreStream.getUsers().fetchUsers(missing)
            // The users arrive asynchronously; give them a few seconds.
            for (attempt in 1..8) {
                if (destroyed) return
                Thread.sleep(500)
                found = StoreStream.getUsers().getUsers(wanted, true)
                if (wanted.all { found[it] != null }) break
            }
        }
        val resolved = found
        val sorted = ids.sortedBy { displayName(it, resolved[it]).lowercase() }
        matched = sorted
        users = resolved
        shown = PAGE_SIZE
        Utils.mainThread.post { if (!destroyed && isAdded) render() }
    }

    private fun displayName(userId: Long, user: User?): String {
        val nick = StoreStream.getGuilds().getMember(guildId, userId)?.nick
        return nick ?: user?.username ?: userId.toString()
    }

    /** Role ids a member has: loaded from their profile, cached by Discord, or null when unknown. */
    private fun memberRoleIds(userId: Long): List<Long>? =
        profileRoles[userId] ?: StoreStream.getGuilds().getMember(guildId, userId)?.roles

    // ---- Drawing -----------------------------------------------------------------------------

    private fun setMatch(all: Boolean) {
        if (matchAll == all) return
        matchAll = all
        styleSegments()
        statusView?.text = "Loading members…"
        listContainer?.removeAllViews()
        Utils.threadPool.execute { recompute() }
    }

    private fun styleSegments() {
        val accent = ContextCompat.getColor(requireContext(), R.c.brand_new_330)
        for ((button, selected) in listOf(allButton to matchAll, anyButton to !matchAll)) {
            button ?: continue
            button.background = pill(
                if (selected) ColorUtils.setAlphaComponent(accent, 70) else cardBase,
                if (selected) accent else ColorUtils.setAlphaComponent(textMuted, 90),
            )
            button.setTextColor(if (selected) textNormal else textMuted)
            button.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun render() {
        val container = listContainer ?: return
        container.removeAllViews()
        val error = loadError
        if (error != null) {
            statusView?.text = error
            profilesButton?.visibility = View.GONE
            return
        }
        val total = matched.size
        val mode = if (matchAll) "all" else "any"
        statusView?.text = when {
            total == 0 -> "No members have $mode of these roles."
            total > MAX_RESOLVE -> "$total members match. Showing the first $MAX_RESOLVE."
            else -> "$total member${if (total == 1) "" else "s"} with $mode of these roles"
        }

        val visible = matched.take(minOf(shown, MAX_RESOLVE))
        for (userId in visible) container.addView(buildCard(userId))

        if (visible.size < minOf(total, MAX_RESOLVE)) {
            container.addView(
                TextView(requireContext()).apply {
                    text = "Show ${minOf(PAGE_SIZE, minOf(total, MAX_RESOLVE) - visible.size)} more"
                    gravity = Gravity.CENTER
                    setTextColor(textNormal)
                    background = pill(cardBase, ColorUtils.setAlphaComponent(textMuted, 90))
                    setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
                    setOnClickListener {
                        shown += PAGE_SIZE
                        render()
                    }
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpToPx(4) },
            )
        }

        val unknown = visible.count { memberRoleIds(it) == null }
        profilesButton?.apply {
            visibility = if (unknown > 0 && !loadingProfiles) View.VISIBLE else View.GONE
            text = "Load all roles for $unknown member${if (unknown == 1) "" else "s"}"
        }
    }

    private fun buildCard(userId: Long): View {
        val ctx = requireContext()
        val user = users[userId]
        val member = StoreStream.getGuilds().getMember(guildId, userId)
        val roles = guildRoles()
        val known = memberRoleIds(userId)

        // Roles to show: everything Discord has for them, plus the chosen roles we know they have.
        val ids = LinkedHashSet<Long>()
        known?.let { ids.addAll(it) }
        for ((roleId, members) in memberIdsByRole) if (members.contains(userId)) ids.add(roleId)
        val memberRoles = ids.mapNotNull { roles[it] }.map { GuildRoleWrapper(it) }.sortedByDescending { it.position }
        val mainColor = memberRoles.firstOrNull { it.color != 0 }?.let { opaque(it.color) }

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(14).toFloat()
                if (mainColor != null) {
                    setColor(ColorUtils.blendARGB(cardBase, mainColor, 0.16f))
                    setStroke(dpToPx(1), ColorUtils.setAlphaComponent(mainColor, 130))
                } else {
                    setColor(cardBase)
                    setStroke(dpToPx(1), ColorUtils.setAlphaComponent(textMuted, 60))
                }
            }
            isClickable = true
            setOnClickListener {
                WidgetUserSheet.show(userId, channelId, parentFragmentManager, guildId)
            }
            setOnLongClickListener {
                Utils.setClipboard("User ID", userId.toString())
                Utils.showToast("Copied ID to clipboard!")
                true
            }
        }

        val avatar = SimpleDraweeView(ctx)
        MGImages.setRoundingParams(avatar, 22f, false, null, null, 0f)
        MGImages.setImage(avatar, avatarUrl(userId, user, member))
        card.addView(avatar, LinearLayout.LayoutParams(dpToPx(44), dpToPx(44)).apply { marginEnd = dpToPx(12) })

        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val nick = member?.nick
        column.addView(
            TextView(ctx).apply {
                text = nick ?: user?.username ?: "User $userId"
                setTextColor(mainColor ?: textNormal)
                setTypeface(null, Typeface.BOLD)
                textSize = 16f
            },
        )
        if (nick != null && user != null) {
            column.addView(
                TextView(ctx).apply {
                    text = user.username
                    setTextColor(textMuted)
                    textSize = 12f
                },
            )
        }
        column.addView(
            TextView(ctx).apply {
                setTextColor(textMuted)
                textSize = 12f
                setPadding(0, dpToPx(4), 0, 0)
                text = roleLine(memberRoles, known != null)
            },
        )
        card.addView(column, LinearLayout.LayoutParams(0, -2, 1f))

        return LinearLayout(ctx).apply {
            addView(card, LinearLayout.LayoutParams(-1, -2))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpToPx(8) }
        }
    }

    /** "● Role  ● Role" with the chosen roles in bold, in the colour of each role. */
    private fun roleLine(roles: List<GuildRoleWrapper>, complete: Boolean): CharSequence {
        val sb = SpannableStringBuilder()
        for (role in roles) {
            val dotColor = if (role.color != 0) opaque(role.color) else textMuted
            sb.append("●")
            sb.setSpan(ForegroundColorSpan(dotColor), sb.length - 1, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(" ")
            val start = sb.length
            sb.append(role.name)
            if (roleIds.contains(role.id)) {
                sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(textNormal), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            sb.append("    ")
        }
        if (!complete) sb.append("(other roles not loaded)")
        return sb
    }

    private fun avatarUrl(userId: Long, user: User?, member: com.discord.models.member.GuildMember?): String =
        when {
            member != null && member.hasAvatar() ->
                "https://cdn.discordapp.com/guilds/$guildId/users/$userId/avatars/${member.avatarHash}.png?size=128"
            user?.avatar != null -> "https://cdn.discordapp.com/avatars/$userId/${user.avatar}.png?size=128"
            else -> "https://cdn.discordapp.com/embed/avatars/${(userId shr 22) % 6}.png"
        }

    private fun opaque(color: Int) = color or 0xFF000000.toInt()

    private fun pill(fill: Int, stroke: Int) =
        GradientDrawable().apply {
            cornerRadius = dpToPx(12).toFloat()
            setColor(fill)
            setStroke(dpToPx(1), stroke)
        }

    private fun segment(ctx: android.content.Context, label: String, onClick: () -> Unit) =
        TextView(ctx).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 14f
            setPadding(dpToPx(8), dpToPx(10), dpToPx(8), dpToPx(10))
            setOnClickListener { onClick() }
        }

    // ---- Roles of members Discord has not cached ---------------------------------------------

    /**
     * Discord only has the roles of some members. On request, loads the rest one at a time from their
     * profiles, slowly, so it never looks like a flood of requests.
     */
    private fun loadMissingProfiles() {
        if (loadingProfiles) return
        val todo = matched.take(minOf(shown, MAX_RESOLVE)).filter { memberRoleIds(it) == null }
        if (todo.isEmpty()) return
        loadingProfiles = true
        profilesButton?.visibility = View.GONE
        Utils.threadPool.execute {
            var done = 0
            for (userId in todo) {
                if (destroyed) return@execute
                try {
                    val path = "/users/$userId/profile?with_mutual_guilds=false&with_mutual_friends_count=false&guild_id=$guildId"
                    Http.Request.newDiscordRNRequest(path, "GET").use { request ->
                        request.execute().use { response ->
                            if (response.statusCode == 429) throw IllegalStateException("rate limited")
                            if (response.ok()) {
                                val array = JSONObject(response.text()).optJSONObject("guild_member")?.optJSONArray("roles")
                                val list = ArrayList<Long>()
                                if (array != null) for (i in 0 until array.length()) array.optString(i).toLongOrNull()?.let { list.add(it) }
                                profileRoles[userId] = list
                            }
                        }
                    }
                } catch (e: Throwable) {
                    log.error("Could not load the roles of $userId", e)
                    if (e.message == "rate limited") {
                        Utils.mainThread.post { Utils.showToast("Discord asked to slow down. Try again in a minute.") }
                        break
                    }
                }
                done++
                if (done % 5 == 0) Utils.mainThread.post { if (!destroyed && isAdded) render() }
                Thread.sleep(PROFILE_DELAY_MS)
            }
            loadingProfiles = false
            Utils.mainThread.post { if (!destroyed && isAdded) render() }
        }
    }

    companion object {
        private const val PAGE_SIZE = 40
        private const val MAX_RESOLVE = 500
        private const val PROFILE_DELAY_MS = 400L
    }
}
