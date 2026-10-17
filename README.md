# Claude playground

A playground for [Claude Code](https://claude.com/product/claude-code). The code in this repository was written
by Claude (Anthropic).

## TranslateMessagesPlus

An [Aliucord](https://github.com/Aliucord/Aliucord) plugin that translates Discord messages, either one at a time
or automatically in the chats you choose, and can send your reply in the other person's language.

### Credits

- **Original author: [yutaplug](https://github.com/yutaplug).** This plugin is a modified copy of
  [TranslateMessages](https://github.com/yutaplug/yutaplugins/tree/main/plugins/TranslateMessages) (v1.0.2) from
  `yutaplug/yutaplugins`. Most of the translation engine (Google, DeepL and LibreTranslate), the language list, the
  `/translate` command, the Translate / Show original button and the settings layout come from that plugin.
- **Added in this version:** per-chat auto-translate (Off, Everyone, Others only, My messages only), set from a
  channel's settings page or by long-pressing a channel or DM, "Reply (translate)" on translated messages, and a
  configurable colour for the "(translated …)" tag.
- **Written with Claude Code** (Claude by Anthropic).

### License note

The original `yutaplug/yutaplugins` repository does not include a license file, so its code stays the property of
its author. This modified version is shared for personal use with credit. If the original author would like it
changed or taken down, please open an issue.

## MultiRoleFilter

An [Aliucord](https://github.com/Aliucord/Aliucord) plugin that finds server members by several roles at once.
`/rolefilter` takes 1 to 5 roles and shows the members who have all of them, or any of them. Each member is a card
in the colour of their main role, with their other roles listed, and tapping a card opens their profile.

### Credits

- Based on the idea and the member-list approach of [RoleMembers](https://github.com/nyakowint/AliuPlugins/tree/main/RoleMembers)
  by nyakowint, which is released under the GLWTS license.
- Written with Claude Code (Claude by Anthropic).

### Building

Pushing to `main` builds every plugin with GitHub Actions. The zip is attached to the `latest` release and also kept
on the `builds` branch. To use it, copy the zip into Aliucord's `plugins` folder and restart Aliucord.
