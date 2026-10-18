# TranslateMessagesPlus

An [Aliucord](https://github.com/Aliucord/Aliucord) plugin for translating Discord messages from their message menu, or automatically in chats you choose. It can also translate replies into the other person's language before sending.

## Features

- Translate an individual message and show the original again.
- Use `/translate` for quick translations.
- Automatically translate messages per chat. Choose **Off**, **Everyone**, **Others only**, or **My messages only**.
- Set auto-translate from a channel's settings page or by long-pressing a channel or DM.
- Reply from a translated message using **Reply (translate)**, with an optional preview.
- Change the colour of the `(translated …)` label in the plugin settings.
- Thread messages follow their parent channel's translation setting.

Translations are kept in memory only while Aliucord is running.

**Privacy:** automatic translation sends eligible message text to the translation service you select. Google Translate is used by default; you can instead choose DeepL or your own LibreTranslate server.

## Install

1. Open this repository's [`builds`](../../tree/builds) branch after a successful GitHub build.
2. Download `TranslateMessagesPlus.zip`.
3. Copy it into Aliucord's `plugins` folder and restart Aliucord.

## Build locally

You need Git, Android Studio, and Java JDK 21 or newer.

- Linux/macOS: `./gradlew :TranslateMessagesPlus:make`
- Windows: `.\gradlew.bat :TranslateMessagesPlus:make`

The zip is written to `plugins/TranslateMessagesPlus/build/outputs/TranslateMessagesPlus.zip`.

## Credits and licensing

TranslateMessagesPlus is based on [TranslateMessages v1.0.2](https://github.com/yutaplug/yutaplugins/tree/main/plugins/TranslateMessages) by [yutaplug](https://github.com/yutaplug). The original project has no license file, so its code remains the original author's property. This modified version is shared for personal use with attribution.

The added features include per-chat auto-translation, translated replies, and a configurable translated-message label colour.

## Repository structure

This repository uses the official [Aliucord plugins template](https://github.com/Aliucord/plugins-template). `main` contains the source; `builds` is maintained automatically by the deployment workflow and holds the installable zip plus its updater metadata.
