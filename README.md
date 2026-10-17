# Claude's Aliucord plugins

An [Aliucord](https://github.com/Aliucord/Aliucord) plugins monorepo, built from the official
[plugins template](https://github.com/Aliucord/plugins-template). The code in this repository was written
by Claude (Anthropic) with Claude Code.

## Plugins

### TranslateMessagesPlus

Translates Discord messages, either one at a time or automatically in the chats you choose, and can send your reply in
the other person's language.

#### Credits

- **Original author: [yutaplug](https://github.com/yutaplug).** This plugin is a modified copy of
  [TranslateMessages](https://github.com/yutaplug/yutaplugins/tree/main/plugins/TranslateMessages) (v1.0.2) from
  `yutaplug/yutaplugins`. Most of the translation engine (Google, DeepL and LibreTranslate), the language list, the
  `/translate` command, the Translate / Show original button and the settings layout come from that plugin.
- **Added in this version:** per-chat auto-translate (Off, Everyone, Others only, My messages only), set from a
  channel's settings page or by long-pressing a channel or DM, "Reply (translate)" on translated messages, and a
  configurable colour for the "(translated …)" tag.
- **Written with Claude Code** (Claude by Anthropic).

#### License note

The original `yutaplug/yutaplugins` repository does not include a license file, so its code stays the property of
its author. This modified version is shared for personal use with credit. If the original author would like it
changed or taken down, please open an issue.

### MultiRoleFilter

Finds server members by several roles at once. `/rolefilter` takes 1 to 5 roles and shows the members who have all of
them, or any of them. Each member is a card in the colour of their main role, with their other roles listed, and
tapping a card opens their profile.

#### Credits

- Based on the idea and the member-list approach of
  [RoleMembers](https://github.com/nyakowint/AliuPlugins/tree/main/RoleMembers) by nyakowint, which is released under
  the GLWTS license.
- Written with Claude Code (Claude by Anthropic).

## Installing

Every push to `main` builds all plugins and publishes them to the `builds` branch, together with `updater.json`.
To use a plugin, download its zip from the `builds` branch, copy it into Aliucord's `plugins` folder and restart
Aliucord.

## Development

Prerequisites: Git, Android Studio, and Java JDK 21 or newer. See the
[template README](https://github.com/Aliucord/plugins-template#readme) for the full setup guide.

Project layout (same as the template):

- `plugins/build.gradle.kts` holds the config shared by every plugin (SDK versions, author, repository).
- `plugins/<PluginName>/build.gradle.kts` holds the plugin's version, description, changelog, package namespace and
  `deploy` flag.
- `plugins/<PluginName>/src/main/kotlin/...` holds the plugin's code.

To add another plugin, create a new folder under `plugins/` with its own `build.gradle.kts` and `src`; it is picked up
automatically.

Build from the command line:

- Linux & macOS: `./gradlew :TranslateMessagesPlus:make` (or `:MultiRoleFilter:make`). Use
  `./gradlew TranslateMessagesPlus:deployWithAdb` to deploy straight to a connected device.
- Windows: `.\gradlew.bat :TranslateMessagesPlus:make` and `.\gradlew.bat TranslateMessagesPlus:deployWithAdb`.

The built plugin zip is placed at `plugins/<PluginName>/build/outputs/<PluginName>.zip`.

### GitHub setup

- A `builds` branch must exist (it does). The `Deploy` workflow pushes the plugin zips and `updater.json` there.
- To speed up CI builds, add a repository secret named `GRADLE_CACHE_ENCRYPTION_KEY` (generate one with
  `openssl rand -base64 16`) under Settings > Secrets and variables > Actions.
