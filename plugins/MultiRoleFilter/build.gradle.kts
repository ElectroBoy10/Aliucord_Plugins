import com.android.build.gradle.LibraryExtension

version = "2.0.0" // Plugin version. Increment this to trigger an update
description = "Find server members by one or more roles with /rolefilter. Pick roles on the page, then see who has all of them or any of them. Based on RoleMembers by nyakowint."

configure<LibraryExtension> {
    namespace = "com.multirolefilter"
}

aliucord {
    changelog.set(
        """
        # 2.0.0
        * Redesigned page: add and remove roles right on the page, switch between "all of the roles" and "any of the roles", and search the results.
        * Fixed the member list loading forever. Problems now show a clear message with a Try again button instead.
        * If Discord won't send a role's full member list, the page falls back to the members already loaded on your device and says so.
        * Clearer wording throughout, a "Copy this list of names" button, and long-press a member to copy their ID.

        # 1.0.0
        * New /rolefilter command: pick 1 to 5 roles and see the members who have all of them, or any of them.
        * Each member is a card in the colour of their main role, with their other roles listed. Tap a card to open the profile.
        * Based on RoleMembers by nyakowint (GLWTS license).
        """.trimIndent(),
    )

    // Publish this plugin to the builds branch. Set to false if the plugin is unfinished.
    deploy.set(true)
}
