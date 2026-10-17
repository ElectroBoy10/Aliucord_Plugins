# Claude playground

A playground for [Claude Code](https://claude.com/product/claude-code). The code in this repository was written
by Claude (Anthropic) at the request of ElectroBoy10.

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
- **Written with Claude Code** (Claude by Anthropic), directed by ElectroBoy10.

### License note

The original `yutaplug/yutaplugins` repository does not include a license file, so its code stays the property of
its author. This modified version is shared for personal use with credit. If the original author would like it
changed or taken down, please open an issue.

### Building

Pushing to `main` builds the plugin with GitHub Actions. The zip is attached to the `latest` release and also kept
on the `builds` branch. To use it, copy the zip into Aliucord's `plugins` folder and restart Aliucord.
