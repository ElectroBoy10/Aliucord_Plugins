version = "1.0.1"
description = "Translate messages from the message menu or automatically in the chats you pick, and reply in the other person's language. Based on TranslateMessages by yutaplug; modified by ElectroBoy10 with Claude Code."

aliucord {
    changelog.set(
        """
        # 1.0.1
        * Auto-translate moved out of the message menu: set it in a channel's settings page, or long-press a channel or DM.
        * Fixed the per-chat picker and the Reply (translate) box not opening from long-press menus.
        * Added an About section in settings crediting the original author.

        # 1.0.0
        * Based on TranslateMessages 1.0.2 by yutaplug.
        * Auto-translate is now set per chat: Off, Everyone, Others only, or My messages only.
        * New "Reply (translate)" button on translated messages: type in any language and it is sent in the other person's language, with an optional preview.
        * The "(translated ...)" tag has its own colour, which you can change in settings.
        * Threads follow their channel's setting. Translations are cached in memory only.
        """.trimIndent(),
    )
}
