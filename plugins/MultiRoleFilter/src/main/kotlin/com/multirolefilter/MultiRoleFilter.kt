package com.multirolefilter

import android.content.Context
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.CommandsAPI
import com.aliucord.entities.Plugin
import com.discord.api.commands.ApplicationCommandType
import com.discord.models.commands.ApplicationCommandOption

@AliucordPlugin
class MultiRoleFilter : Plugin() {
    override fun start(context: Context) {
        val options = ArrayList<ApplicationCommandOption>()
        options.add(
            Utils.createCommandOption(
                ApplicationCommandType.ROLE,
                "role",
                "A role to look for (you can add more roles on the next screen)",
            ),
        )
        for (i in 2..COMMAND_ROLES) {
            options.add(Utils.createCommandOption(ApplicationCommandType.ROLE, "role$i", "Another role (optional)"))
        }
        options.add(
            Utils.createCommandOption(
                ApplicationCommandType.STRING,
                "match",
                "Show members who have all of the roles, or any of them",
                choices = listOf(
                    Utils.createCommandChoice("Has all of the roles", MATCH_ALL),
                    Utils.createCommandChoice("Has any of the roles", MATCH_ANY),
                ),
            ),
        )

        commands.registerCommand("rolefilter", "Find members by one or more roles", options) { ctx ->
            val guildId = ctx.currentChannel.guildId
            if (guildId == 0L) {
                return@registerCommand CommandsAPI.CommandResult("This command only works in a server.", null, false)
            }
            val roleIds = ArrayList<Long>()
            for (name in listOf("role") + (2..COMMAND_ROLES).map { "role$it" }) {
                val role = ctx.getRole(name) ?: continue
                if (!roleIds.contains(role.id)) roleIds.add(role.id)
            }
            val matchAll = ctx.getStringOrDefault("match", MATCH_ALL) != MATCH_ANY
            Utils.openPageWithProxy(Utils.appActivity, RoleFilterPage(guildId, ctx.channelId, roleIds, matchAll))
            CommandsAPI.CommandResult()
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        commands.unregisterAll()
    }

    companion object {
        /** Roles that can be filled in directly on the slash command. More can be added on the page. */
        const val COMMAND_ROLES = 5
        const val MATCH_ALL = "all"
        const val MATCH_ANY = "any"
    }
}
