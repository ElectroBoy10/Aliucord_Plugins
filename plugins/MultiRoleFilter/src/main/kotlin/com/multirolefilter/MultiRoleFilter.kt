package com.multirolefilter

import android.content.Context
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.CommandsAPI
import com.aliucord.entities.Plugin
import com.discord.models.commands.ApplicationCommandOption
import com.discord.api.commands.ApplicationCommandType

@AliucordPlugin
class MultiRoleFilter : Plugin() {
    override fun start(context: Context) {
        val options = ArrayList<ApplicationCommandOption>()
        options.add(Utils.createCommandOption(ApplicationCommandType.ROLE, "role1", "A role to filter by", required = true))
        for (i in 2..MAX_ROLES) {
            options.add(Utils.createCommandOption(ApplicationCommandType.ROLE, "role$i", "Another role to filter by (optional)"))
        }
        options.add(
            Utils.createCommandOption(
                ApplicationCommandType.STRING,
                "match",
                "Members must have all of the roles or any of them (default: all)",
                choices = listOf(
                    Utils.createCommandChoice("All of the roles", MATCH_ALL),
                    Utils.createCommandChoice("Any of the roles", MATCH_ANY),
                ),
            ),
        )

        commands.registerCommand("rolefilter", "Find members who have several roles at once", options) { ctx ->
            val guildId = ctx.currentChannel.guildId
            val roleIds = ArrayList<Long>()
            for (i in 1..MAX_ROLES) {
                val role = ctx.getRole("role$i") ?: continue
                if (!roleIds.contains(role.id)) roleIds.add(role.id)
            }
            if (guildId == 0L) {
                CommandsAPI.CommandResult("Run this command in a server channel.", null, false)
            } else if (roleIds.isEmpty()) {
                CommandsAPI.CommandResult("Pick at least one role.", null, false)
            } else {
                val matchAll = ctx.getStringOrDefault("match", MATCH_ALL) != MATCH_ANY
                Utils.openPageWithProxy(Utils.appActivity, RoleFilterPage(guildId, ctx.channelId, roleIds, matchAll))
                CommandsAPI.CommandResult()
            }
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        commands.unregisterAll()
    }

    companion object {
        const val MAX_ROLES = 5
        const val MATCH_ALL = "all"
        const val MATCH_ANY = "any"
    }
}
