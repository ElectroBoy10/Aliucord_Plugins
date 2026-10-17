package com.multirolefilter

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.aliucord.Logger
import com.aliucord.Utils
import com.aliucord.fragments.SettingsPage
import com.aliucord.utils.DimenUtils.dpToPx
import com.aliucord.utils.RxUtils
import com.aliucord.utils.RxUtils.subscribe
import com.aliucord.wrappers.GuildRoleWrapper
import com.discord.api.role.GuildRole
import com.discord.models.member.GuildMember
import com.discord.models.user.User
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.images.MGImages
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.ObservableExtensionsKt
import com.discord.widgets.user.usersheet.WidgetUserSheet
import com.facebook.drawee.view.SimpleDraweeView
import com.lytefast.flexinput.R
import rx.Subscription
import rx.functions.Action0
import rx.functions.Action1

/**
 * "Members by role": pick roles, choose whether members need all of them or any of them, and browse the result.
 *
 * Every step reports what it is doing on screen, so the page can never sit on a loading message forever:
 * a request that fails or takes too long turns into a message with a retry button.
 */
@SuppressLint("SetTextI18n")
class RoleFilterPage(
    private val guildId: Long,
    private val channelId: Long,
    initialRoles: List<Long>,
    private var matchAll: Boolean,
) : SettingsPage() {
    private val log = Logger("MultiRoleFilter")

    private val selected = LinkedHashSet<Long>(initialRoles)

    // Member ids per role, as sent by Discord.
    private val idsByRole = HashMap<Long, Set<Long>>()
    private val loading = HashSet<Long>()

    // Roles Discord would not list in full, with the reason. These fall back to members already on this device.
    private val failures = LinkedHashMap<Long, String>()
    private val timeouts = HashMap<Long, Runnable>()
    private val subscriptions = ArrayList<Subscription>()

    private var matched: List<Long> = emptyList()
    private var shown = PAGE_SIZE
    private var query = ""
    private var destroyed = false

    private val requestedUsers = HashSet<Long>()
    private var userPoll: Runnable? = null

    private var rolesBox: LinearLayout? = null
    private var modeBox: LinearLayout? = null
    private var modeHint: TextView? = null
    private var allButton: TextView? = null
    private var anyButton: TextView? = null
    private var searchField: EditText? = null
    private var statusView: TextView? = null
    private var noticeView: TextView? = null
    private var retryButton: TextView? = null
    private var copyButton: TextView? = null
    private var listBox: LinearLayout? = null

    private val textNormal get() = ColorCompat.getThemedColor(requireContext(), R.b.colorTextNormal)
    private val textMuted get() = ColorCompat.getThemedColor(requireContext(), R.b.colorTextMuted)
    private val cardBase get() = ColorCompat.getThemedColor(requireContext(), R.b.colorBackgroundSecondary)
    private val accent get() = ContextCompat.getColor(requireContext(), R.c.brand_new_330)

    // ---- Page --------------------------------------------------------------------------------

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("Members by role")
        setActionBarSubtitle(StoreStream.getGuilds().getGuild(guildId)?.name ?: "Server")
        val ctx = requireContext()

        addView(label(ctx, "ROLES"))
        rolesBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        addView(rolesBox)
        linearLayout.addView(
            button(ctx, "+  Add or remove roles") { openPicker() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpToPx(6) },
        )

        modeBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dpToPx(18), 0, 0)
        }
        modeBox!!.addView(label(ctx, "SHOW MEMBERS WHO HAVE"))
        val toggle = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        allButton = segment(ctx, "All of the roles") { setMode(true) }
        anyButton = segment(ctx, "Any of the roles") { setMode(false) }
        toggle.addView(allButton, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dpToPx(6) })
        toggle.addView(anyButton, LinearLayout.LayoutParams(0, -2, 1f))
        modeBox!!.addView(toggle)
        modeHint = TextView(ctx).apply {
            setTextColor(textMuted)
            textSize = 13f
            setPadding(dpToPx(2), dpToPx(6), dpToPx(2), 0)
        }
        modeBox!!.addView(modeHint)
        addView(modeBox)

        addView(
            TextView(ctx).apply {
                setTextColor(textNormal)
                setTypeface(null, Typeface.BOLD)
                textSize = 16f
                setPadding(dpToPx(2), dpToPx(22), dpToPx(2), dpToPx(4))
                statusView = this
            },
        )
        noticeView = TextView(ctx).apply {
            setTextColor(textMuted)
            textSize = 13f
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
            background = pill(cardBase, ColorUtils.setAlphaComponent(textMuted, 70))
            visibility = View.GONE
        }
        linearLayout.addView(noticeView, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpToPx(8) })

        retryButton = button(ctx, "Try again") { retryFailed() }.apply { visibility = View.GONE }
        linearLayout.addView(retryButton, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpToPx(8) })

        searchField = EditText(ctx).apply {
            hint = "Search these members"
            setHintTextColor(textMuted)
            setTextColor(textNormal)
            textSize = 15f
            setSingleLine(true)
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
            background = pill(cardBase, ColorUtils.setAlphaComponent(textMuted, 70))
            visibility = View.GONE
            addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                    override fun afterTextChanged(s: Editable?) {
                        query = s?.toString()?.trim()?.lowercase() ?: ""
                        shown = PAGE_SIZE
                        drawList()
                    }
                },
            )
        }
        linearLayout.addView(searchField, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpToPx(8) })

        listBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        addView(listBox)

        copyButton = button(ctx, "Copy this list of names") { copyNames() }.apply { visibility = View.GONE }
        linearLayout.addView(copyButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpToPx(8) })

        refreshAll()
    }

    override fun onDestroyView() {
        destroyed = true
        for (task in timeouts.values) Utils.mainThread.removeCallbacks(task)
        timeouts.clear()
        userPoll?.let { Utils.mainThread.removeCallbacks(it) }
        for (sub in subscriptions) runCatching { sub.unsubscribe() }
        subscriptions.clear()
        rolesBox = null
        modeBox = null
        modeHint = null
        allButton = null
        anyButton = null
        searchField = null
        statusView = null
        noticeView = null
        retryButton = null
        copyButton = null
        listBox = null
        super.onDestroyView()
    }

    private val alive get() = !destroyed && isAdded && listBox != null

    // ---- Roles -------------------------------------------------------------------------------

    private fun guildRoles(): Map<Long, GuildRole> = StoreStream.getGuilds().roles[guildId] ?: emptyMap()

    private fun roleName(id: Long) = guildRoles()[id]?.let { GuildRoleWrapper(it).name } ?: "Unknown role"

    private fun openPicker() {
        val all = guildRoles().values
            .map { GuildRoleWrapper(it) }
            .filter { it.id != guildId } // @everyone
            .sortedByDescending { it.position }
        RolePickerSheet(all, selected, MAX_ROLES) { picked ->
            selected.clear()
            selected.addAll(picked)
            shown = PAGE_SIZE
            refreshAll()
        }.show(parentFragmentManager, "multirolefilter-roles")
    }

    private fun removeRole(id: Long) {
        selected.remove(id)
        refreshAll()
    }

    private fun setMode(all: Boolean) {
        if (matchAll == all) return
        matchAll = all
        shown = PAGE_SIZE
        drawMode()
        recompute()
    }

    /** Everything that depends on the chosen roles. */
    private fun refreshAll() {
        if (!alive) return
        drawRoles()
        drawMode()
        for (id in selected) loadRole(id)
        recompute()
    }

    // ---- Loading -----------------------------------------------------------------------------

    private fun loadRole(roleId: Long) {
        if (idsByRole.containsKey(roleId) || loading.contains(roleId) || failures.containsKey(roleId)) return
        loading.add(roleId)

        val timeout = Runnable { finishRole(roleId, null, "Discord took too long to answer.") }
        timeouts[roleId] = timeout
        Utils.mainThread.postDelayed(timeout, REQUEST_TIMEOUT_MS)

        try {
            val memberIds = RestAPI.getApi().getGuildRoleMemberIds(guildId, roleId)
            val request = ObservableExtensionsKt.computationLatest(memberIds)
            val subscriber = RxUtils.createActionSubscriber<Any?>(
                onNext = Action1<Any?> { value -> Utils.mainThread.post { finishRole(roleId, parseIds(value), null) } },
                onError = Action1<Throwable> { t ->
                    log.error("Could not load the members of role $roleId", t)
                    Utils.mainThread.post { finishRole(roleId, null, describe(t)) }
                },
                onCompleted = Action0 { Utils.mainThread.post { finishRole(roleId, emptySet(), null) } },
            )
            subscriptions.add(request.subscribe(subscriber))
        } catch (t: Throwable) {
            log.error("Could not start loading role $roleId", t)
            finishRole(roleId, null, describe(t))
        }
    }

    /**
     * Called on the main thread with either the member ids or a failure reason.
     * Only the first call per role counts.
     */
    private fun finishRole(roleId: Long, ids: Set<Long>?, error: String?) {
        if (!loading.remove(roleId)) return
        timeouts.remove(roleId)?.let { Utils.mainThread.removeCallbacks(it) }
        if (ids != null) idsByRole[roleId] = ids else failures[roleId] = error ?: "Unknown error."
        if (alive) recompute()
    }

    private fun retryFailed() {
        val again = failures.keys.toList()
        failures.clear()
        for (id in again) if (selected.contains(id)) loadRole(id)
        recompute()
    }

    private fun parseIds(value: Any?): Set<Long> {
        val out = LinkedHashSet<Long>()
        (value as? Iterable<*>)?.forEach { item ->
            when (item) {
                is Number -> out.add(item.toLong())
                else -> item?.toString()?.toLongOrNull()?.let { out.add(it) }
            }
        }
        return out
    }

    private fun describe(t: Throwable): String {
        val text = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
        return text.take(160)
    }

    /** Members Discord has already given this device, for roles it would not list in full. */
    private fun cachedIds(roleId: Long): Set<Long> {
        val members = StoreStream.getGuilds().members[guildId] ?: return emptySet()
        val out = LinkedHashSet<Long>()
        for (member in members.values) if (member.roles.contains(roleId)) out.add(member.userId)
        return out
    }

    private fun idsFor(roleId: Long): Set<Long> =
        idsByRole[roleId] ?: if (failures.containsKey(roleId)) cachedIds(roleId) else emptySet()

    // ---- Result ------------------------------------------------------------------------------

    private fun recompute() {
        if (!alive) return
        if (selected.isEmpty() || loading.isNotEmpty()) {
            matched = emptyList()
        } else {
            val roles = selected.toList()
            val result = LinkedHashSet<Long>(idsFor(roles[0]))
            for (id in roles.drop(1)) {
                if (matchAll) result.retainAll(idsFor(id)) else result.addAll(idsFor(id))
            }
            matched = result.toList()
            requestUsers(matched)
        }
        render()
    }

    /** Asks Discord for the profiles of members it has not shown us yet, then waits for them to arrive. */
    private fun requestUsers(ids: List<Long>) {
        val wanted = ids.take(RESOLVE_CAP)
        val known = StoreStream.getUsers().users
        val missing = wanted.filter { known[it] == null && requestedUsers.add(it) }
        if (missing.isNotEmpty()) {
            Utils.threadPool.execute {
                try {
                    for (chunk in missing.chunked(100)) {
                        if (destroyed) return@execute
                        StoreStream.getUsers().fetchUsers(chunk)
                        Thread.sleep(200)
                    }
                } catch (t: Throwable) {
                    log.error("Could not request member profiles", t)
                }
            }
        }
        pollUsers(wanted, 0, -1)
    }

    private fun pollUsers(wanted: List<Long>, attempt: Int, lastCount: Int) {
        userPoll?.let { Utils.mainThread.removeCallbacks(it) }
        val task = Runnable {
            if (!alive) return@Runnable
            val users = StoreStream.getUsers().users
            val count = wanted.count { users[it] != null }
            if (count != lastCount) drawList()
            if (count < wanted.size && attempt < POLL_ATTEMPTS) pollUsers(wanted, attempt + 1, count)
        }
        userPoll = task
        Utils.mainThread.postDelayed(task, if (attempt == 0) 400L else POLL_DELAY_MS)
    }

    // ---- Drawing -----------------------------------------------------------------------------

    private fun drawRoles() {
        val box = rolesBox ?: return
        val ctx = requireContext()
        box.removeAllViews()
        if (selected.isEmpty()) {
            box.addView(
                TextView(ctx).apply {
                    text = "No roles chosen yet. Tap the button below to pick one or more roles."
                    setTextColor(textMuted)
                    textSize = 14f
                    setPadding(dpToPx(2), dpToPx(4), dpToPx(2), dpToPx(4))
                },
            )
            return
        }
        val roles = guildRoles()
        for (id in selected) {
            val role = roles[id]?.let { GuildRoleWrapper(it) }
            val count = idsByRole[id]?.size
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpToPx(12), dpToPx(8), dpToPx(4), dpToPx(8))
                background = pill(cardBase, ColorUtils.setAlphaComponent(textMuted, 70))
            }
            row.addView(
                TextView(ctx).apply {
                    text = "●"
                    textSize = 16f
                    setTextColor(if (role != null && role.color != 0) opaque(role.color) else textMuted)
                },
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dpToPx(10) },
            )
            row.addView(
                TextView(ctx).apply {
                    val suffix = when {
                        count != null -> "  ·  $count ${if (count == 1) "member" else "members"}"
                        failures.containsKey(id) -> "  ·  partial list"
                        else -> "  ·  loading…"
                    }
                    val sb = SpannableStringBuilder((role?.name ?: "Unknown role") + suffix)
                    val nameEnd = (role?.name ?: "Unknown role").length
                    sb.setSpan(ForegroundColorSpan(textMuted), nameEnd, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    text = sb
                    textSize = 15f
                    setTextColor(textNormal)
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            row.addView(
                TextView(ctx).apply {
                    text = "✕"
                    textSize = 16f
                    setTextColor(textMuted)
                    setPadding(dpToPx(14), dpToPx(6), dpToPx(14), dpToPx(6))
                    setOnClickListener { removeRole(id) }
                },
            )
            box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpToPx(6) })
        }
    }

    private fun drawMode() {
        val box = modeBox ?: return
        box.visibility = if (selected.size >= 2) View.VISIBLE else View.GONE
        modeHint?.text = if (matchAll) {
            "Only members who have every one of the chosen roles."
        } else {
            "Members who have at least one of the chosen roles."
        }
        for ((button, on) in listOf(allButton to matchAll, anyButton to !matchAll)) {
            button ?: continue
            button.background = pill(
                if (on) ColorUtils.setAlphaComponent(accent, 70) else cardBase,
                if (on) accent else ColorUtils.setAlphaComponent(textMuted, 90),
            )
            button.setTextColor(if (on) textNormal else textMuted)
            button.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    /** Status line, notices and the list. */
    private fun render() {
        if (!alive) return
        val n = selected.size
        val status = statusView ?: return
        val notice = noticeView ?: return
        val retry = retryButton ?: return

        // What went wrong, if anything.
        if (failures.isNotEmpty() && loading.isEmpty()) {
            val names = failures.keys.joinToString(", ") { roleName(it) }
            val reason = failures.values.first()
            notice.text = "Discord didn't send the full member list for $names ($reason). " +
                "Showing only the members already loaded on your device, so some people may be missing."
            notice.visibility = View.VISIBLE
            retry.visibility = View.VISIBLE
        } else {
            notice.visibility = View.GONE
            retry.visibility = View.GONE
        }

        status.text = when {
            n == 0 -> ""
            loading.isNotEmpty() -> "Loading members… (${n - loading.size} of $n roles done)"
            matched.isEmpty() && matchAll && n > 1 && selected.any { idsFor(it).isNotEmpty() } ->
                "Nobody has all of these roles.\nTry “Any of the roles” to see members who have at least one."
            matched.isEmpty() -> if (n == 1) "Nobody has this role." else "Nobody has any of these roles."
            else -> {
                val count = matched.size
                val people = "$count ${if (count == 1) "member" else "members"}"
                when {
                    n == 1 -> "$people with this role"
                    matchAll -> "$people with all $n roles"
                    else -> "$people with at least one of these $n roles"
                } + if (count > RESOLVE_CAP) "\nShowing names for the first $RESOLVE_CAP." else ""
            }
        }
        searchField?.visibility = if (matched.size > SEARCH_FROM) View.VISIBLE else View.GONE
        copyButton?.visibility = if (matched.isNotEmpty()) View.VISIBLE else View.GONE
        drawList()
    }

    private fun drawList() {
        val box = listBox ?: return
        if (!alive) return
        val ctx = requireContext()
        box.removeAllViews()
        if (matched.isEmpty()) return

        val users = StoreStream.getUsers().users
        val sorted = matched.sortedWith(
            compareBy({ users[it] == null }, { displayName(it, users[it]).lowercase() }),
        )
        val filtered = if (query.isEmpty()) {
            sorted
        } else {
            sorted.filter {
                displayName(it, users[it]).lowercase().contains(query) ||
                    users[it]?.username?.lowercase()?.contains(query) == true ||
                    it.toString() == query
            }
        }

        if (filtered.isEmpty()) {
            box.addView(
                TextView(ctx).apply {
                    text = "No one matches “$query”."
                    setTextColor(textMuted)
                    textSize = 14f
                    setPadding(dpToPx(2), dpToPx(8), dpToPx(2), dpToPx(8))
                },
            )
            return
        }

        val visible = filtered.take(shown)
        for (id in visible) box.addView(buildCard(id, users[id]))
        if (filtered.size > visible.size) {
            val left = filtered.size - visible.size
            box.addView(
                button(ctx, "Show ${minOf(PAGE_SIZE, left)} more  ($left left)") {
                    shown += PAGE_SIZE
                    drawList()
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpToPx(2) },
            )
        }
    }

    private fun displayName(userId: Long, user: User?): String =
        StoreStream.getGuilds().getMember(guildId, userId)?.nick ?: user?.username ?: "User $userId"

    private fun copyNames() {
        val users = StoreStream.getUsers().users
        val text = matched
            .sortedBy { displayName(it, users[it]).lowercase() }
            .joinToString("\n") { id ->
                val user = users[id]
                if (user != null) "${displayName(id, user)} (${user.username})" else id.toString()
            }
        Utils.setClipboard("Members", text)
        Utils.showToast("Copied ${matched.size} ${if (matched.size == 1) "member" else "members"}")
    }

    private fun buildCard(userId: Long, user: User?): View {
        val ctx = requireContext()
        val member = StoreStream.getGuilds().getMember(guildId, userId)
        val roles = guildRoles()

        // The chosen roles this member has, highest first.
        val theirs = selected
            .filter { idsFor(it).contains(userId) }
            .mapNotNull { roles[it] }
            .map { GuildRoleWrapper(it) }
            .sortedByDescending { it.position }
        val mainColor = theirs.firstOrNull { it.color != 0 }?.let { opaque(it.color) }

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(14).toFloat()
                if (mainColor != null) {
                    setColor(ColorUtils.blendARGB(cardBase, mainColor, 0.14f))
                    setStroke(dpToPx(1), ColorUtils.setAlphaComponent(mainColor, 120))
                } else {
                    setColor(cardBase)
                    setStroke(dpToPx(1), ColorUtils.setAlphaComponent(textMuted, 60))
                }
            }
            isClickable = true
            setOnClickListener { WidgetUserSheet.show(userId, channelId, parentFragmentManager, guildId) }
            setOnLongClickListener {
                Utils.setClipboard("User ID", userId.toString())
                Utils.showToast("Copied user ID")
                true
            }
        }

        val avatar = SimpleDraweeView(ctx)
        MGImages.setRoundingParams(avatar, 20f, false, null, null, 0f)
        MGImages.setImage(avatar, avatarUrl(userId, user, member))
        card.addView(avatar, LinearLayout.LayoutParams(dpToPx(40), dpToPx(40)).apply { marginEnd = dpToPx(12) })

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
        // With "any", say which of the chosen roles each member has. With "all" it would be the same for everyone.
        if (!matchAll && selected.size > 1 && theirs.isNotEmpty()) {
            column.addView(
                TextView(ctx).apply {
                    text = roleLine(theirs)
                    textSize = 12f
                    setTextColor(textMuted)
                    setPadding(0, dpToPx(3), 0, 0)
                },
            )
        }
        card.addView(column, LinearLayout.LayoutParams(0, -2, 1f))

        return LinearLayout(ctx).apply {
            addView(card, LinearLayout.LayoutParams(-1, -2))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpToPx(8) }
        }
    }

    private fun roleLine(roles: List<GuildRoleWrapper>): CharSequence {
        val sb = SpannableStringBuilder()
        for ((index, role) in roles.withIndex()) {
            if (index > 0) sb.append("   ")
            sb.append("●")
            sb.setSpan(
                ForegroundColorSpan(if (role.color != 0) opaque(role.color) else textMuted),
                sb.length - 1,
                sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            sb.append(" ").append(role.name)
        }
        return sb
    }

    private fun avatarUrl(userId: Long, user: User?, member: GuildMember?): String =
        when {
            member != null && member.hasAvatar() ->
                "https://cdn.discordapp.com/guilds/$guildId/users/$userId/avatars/${member.avatarHash}.png?size=128"
            user?.avatar != null -> "https://cdn.discordapp.com/avatars/$userId/${user.avatar}.png?size=128"
            else -> "https://cdn.discordapp.com/embed/avatars/${(userId shr 22) % 6}.png"
        }

    // ---- Small view helpers ------------------------------------------------------------------

    private fun opaque(color: Int) = color or 0xFF000000.toInt()

    private fun pill(fill: Int, stroke: Int) =
        GradientDrawable().apply {
            cornerRadius = dpToPx(12).toFloat()
            setColor(fill)
            setStroke(dpToPx(1), stroke)
        }

    private fun label(ctx: Context, text: String) =
        TextView(ctx).apply {
            this.text = text
            setTextColor(textMuted)
            setTypeface(null, Typeface.BOLD)
            textSize = 12f
            setPadding(dpToPx(2), 0, 0, dpToPx(6))
        }

    private fun button(ctx: Context, label: String, onClick: () -> Unit) =
        TextView(ctx).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(textNormal)
            background = pill(cardBase, ColorUtils.setAlphaComponent(textMuted, 90))
            setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
            setOnClickListener { onClick() }
        }

    private fun segment(ctx: Context, label: String, onClick: () -> Unit) =
        TextView(ctx).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 14f
            setPadding(dpToPx(8), dpToPx(12), dpToPx(8), dpToPx(12))
            setOnClickListener { onClick() }
        }

    companion object {
        private const val MAX_ROLES = 10
        private const val PAGE_SIZE = 40
        private const val SEARCH_FROM = 8
        private const val RESOLVE_CAP = 600
        private const val REQUEST_TIMEOUT_MS = 20_000L
        private const val POLL_DELAY_MS = 900L
        private const val POLL_ATTEMPTS = 12
    }
}
