import com.android.build.gradle.LibraryExtension

version = "1.0.1" // Plugin version. Increment this to trigger an update
description = "[Beta] Local-only custom categories at the top of the server member list, filled by the roles you pick."

configure<LibraryExtension> {
    namespace = "com.custommembercategories"
}

aliucord {
    // Publish this plugin to the builds branch. Set to false if the plugin is unfinished.
    deploy.set(true)
}
