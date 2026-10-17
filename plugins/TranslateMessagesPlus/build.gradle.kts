version = "1.0.0"
description = "Translate messages from the message menu or automatically in the chats you pick, and reply in the other person's language. Based on TranslateMessages by yutaplug."

aliucord {
    changelog.set(
        """
        # 1.0.0
        * Based on TranslateMessages 1.0.2 by yutaplug.
        * Auto-translate is now set per chat: Off, Everyone, Others only, or My messages only. Long-press a channel or DM, or use the message menu.
        * New "Reply (translate)" button on translated messages: type in any language and it is sent in the other person's language, with an optional preview.
        * The "(translated ...)" tag has its own colour, which you can change in settings.
        * Threads follow their channel's setting. Translations are cached in memory only.
        """.trimIndent(),
    )
}
