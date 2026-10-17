import com.android.build.gradle.LibraryExtension

version = "1.0.0" // Plugin version. Increment this to trigger an update
description = "Find server members by several roles at once with /rolefilter: members with all of the roles, or any of them. Based on RoleMembers by nyakowint."

configure<LibraryExtension> {
    namespace = "com.multirolefilter"
}

aliucord {
    changelog.set(
        """
        # 1.0.0
        * New /rolefilter command: pick 1 to 5 roles and see the members who have all of them, or any of them.
        * Each member is a card in the colour of their main role, with their other roles listed. Tap a card to open the profile.
        * Based on RoleMembers by nyakowint (GLWTS license).
        """.trimIndent(),
    )

    // Publish this plugin to the builds branch. Set to false if the plugin is unfinished.
    deploy.set(true)
}
