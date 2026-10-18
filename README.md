# Aliucord plugins

A small collection of [Aliucord](https://github.com/Aliucord/Aliucord) plugins by MrBros.

| Plugin | What it does |
| --- | --- |
| [TranslateMessagesPlus](#translatemessagesplus) | Translate Discord messages from their menu, or automatically in chats you choose. |
| [CustomMemberCategories](#custommembercategories-beta) **[Beta]** | Your own role-based categories at the top of a server's member list. |

## TranslateMessagesPlus

An Aliucord plugin for translating Discord messages from their message menu, or automatically in chats you choose. It can also translate replies into the other person's language before sending.

### Features

- Translate an individual message and show the original again.
- Use `/translate` for quick translations.
- Automatically translate messages per chat. Choose **Off**, **Everyone**, **Others only**, or **My messages only**.
- Set auto-translate from a channel's settings page or by long-pressing a channel or DM.
- Reply from a translated message using **Reply (translate)**, with an optional preview.
- Change the colour of the `(translated …)` label in the plugin settings.
- Thread messages follow their parent channel's translation setting.

Translations are kept in memory only while Aliucord is running.

**Privacy:** automatic translation sends eligible message text to the translation service you select. Google Translate is used by default; you can instead choose DeepL or your own LibreTranslate server.

## CustomMemberCategories [Beta]

Add your own categories to the top of a server's member list. Each category is filled by the roles you pick, and shows the members who have them (online members by default, offline ones too if you turn that on).

### Features

- Create as many categories as you like per server, each with its own name and roles (a category header shows the name and member count).
- Choose whether a member needs **any** of the picked roles or **all** of them.
- Optionally include **offline** members in a category (off by default).
- Members are **copied** into your categories. They also stay in their normal place in Discord's list, so Discord's own headers and counts stay correct.
- Reorder categories with the ▲ ▼ arrows. The order in the list is the order at the top of the member list.
- Everything is local-only: only you see your categories, and nothing is sent to Discord or anyone else.

### Using it

- Open the plugin's settings (Aliucord > Plugins > CustomMemberCategories) to pick a server and manage its categories, or run `/categories` in any channel of that server.
- Tap **Add category**, name it, then tick the roles for it.
- Use the arrows on the categories list to change their order.

### Known limits

- Only members Discord has already loaded into the member list can appear. In large servers that means the part of the list you have scrolled to, which matters most for offline members because Discord lists them last.
- It is a Beta: if something looks wrong, turn on **Debug logging** in the plugin settings and send the Aliucord log with your report.

## Install

1. Open this repository's [`builds`](../../tree/builds) branch after a successful GitHub build.
2. Download the plugin you want (`TranslateMessagesPlus.zip` or `CustomMemberCategories.zip`).
3. Copy it into Aliucord's `plugins` folder and restart Aliucord.

## Build locally

You need Git, Android Studio, and Java JDK 21 or newer.

- Linux/macOS: `./gradlew :TranslateMessagesPlus:make`
- Windows: `.\gradlew.bat :TranslateMessagesPlus:make`

Use `CustomMemberCategories` instead of `TranslateMessagesPlus` to build the other plugin. The zip is written to `plugins/<PluginName>/build/outputs/<PluginName>.zip`.

## Credits and licensing

TranslateMessagesPlus is based on [TranslateMessages v1.0.2](https://github.com/yutaplug/yutaplugins/tree/main/plugins/TranslateMessages) by [yutaplug](https://github.com/yutaplug). The original project has no license file, so its code remains the original author's property. I have received premmision from the original owner to use/modify/publish my own version of his plugin [here](https://github.com/yutaplug/yutaplugins/issues/4)

The added features include per-chat auto-translation, translated replies, and a configurable translated-message label colour.

CustomMemberCategories is original work.

## Repository structure

This repository uses the official [Aliucord plugins template](https://github.com/Aliucord/plugins-template). `main` contains the source; `builds` is maintained automatically by the deployment workflow and holds the installable zip plus its updater metadata.
