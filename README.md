# Replay

A [Stein Loader](https://github.com/Stein-Project) mod for Minecraft 1.8.9: see how you died. The game's death
screen gives way to a replay of the last seconds of the fight.

- **Death in slow motion**: when you die the screen turns red, the game keeps going in slow motion for a few seconds
  just as it was, then fades out and the replay comes in.
- **The replay is the respawn screen**: it loops until you respawn, with the **Respawn** and **Leave server**
  buttons in the top right corner.
- **Drone camera**: behind you and from above, always looking the same way, gliding after you only when you leave
  the center of the frame. It sees through blocks like spectator mode.
- **Through your killer's eyes**: in the slow motion of the final blow the camera goes into their view, with their
  hand, item, swing, crosshair, hearts and armor. A card shows their head, name, held item and armor.
- **The fight as it was**: players, mobs, items, XP, arrows, potions, pearls and fishing lines, the particles and
  sounds at their moment, the time of day and the rain. The world as it is now is hidden while the replay plays.
- **Saved replays**: every death is kept in `steinloader/replays` (the newest 50). Watch them again outside a server
  from Stein Loader > Replay > Configure: space pauses, the arrows seek 5 s and change the speed, clicking the bar
  jumps, Esc goes back.

It is visual only: it shows what your game already received from the server and sends nothing but the respawn
request. Set it up on the Panel (Right Shift) > Replay: on or off, length (5 to 30 s) and whether to save replays.
The options are kept in `config/steinreplay.json`.

<!-- Screenshot: docs/replay.png (the death replay with the killer card) -->

## Download

The [Stein Client](https://github.com/Stein-Project/stein-client/releases/latest) installs and updates it for you.
By hand: download `Replay.steinmod` from the
[latest release](https://github.com/Stein-Project/replay/releases/latest), put it in `.minecraft/mods` and play
with the **Stein Loader** profile.

## Building

You need **JDK 25 or newer**.
The [Stein SDK](https://github.com/Stein-Project/stein-sdk) Gradle plugin prepares Minecraft 1.8.9 for compiling the
first time, from your own installation or from Mojang.

```sh
./gradlew build            # build/libs/Replay.steinmod (also checks the formatting)
./gradlew spotlessApply    # format the code (google-java-format, AOSP style) and add the licence header
./gradlew installMod       # build and copy into .minecraft/mods
```

Before a Stein SDK release (developing the SDK and the mod together), build against a local SDK: in a clone of
[sdk](https://github.com/Stein-Project/sdk) run `./gradlew build publishPlugin`, then here
`./gradlew build -Pstein.sdkDir=<path to that clone>` (the plugin comes from its `build/maven-repo`, the SDK from
its `build/sdk/stein-sdk.jar`).

## Contributing

Bug reports, ideas and pull requests are welcome. Read the
[contributing guide](https://github.com/Stein-Project/.github/blob/main/CONTRIBUTING.md) first; your first pull
request asks you to sign the [CLA](https://github.com/Stein-Project/.github/blob/main/CLA.md). For a change to this
mod, also check that:

- an existing `config/steinreplay.json` still loads (the JSON keys `enabled`, `seconds` and `save` are what players
  already have);
- replays saved by older versions still open: the `.steinreplay` layout is in `ReplayFile`, and a change to it needs
  a new version number that still reads the old ones;
- everything is drawn through the Stein Loader API (`Render`, `WorldRender`...), never with OpenGL directly: with
  Stein GFX the Loader leaves out mods that call old OpenGL;
- the text is translated in `en_US`, `pt_BR` and `es_ES` (`src/main/resources/assets/steinreplay/lang`).

Releases are made by the maintainer: a `v<version>` tag, with `changelog/<version>.en_US.md` (and optionally `.pt_BR.md`)
written first (see `.github/workflows/release.yml`).

## Licence

Replay is licensed under the [Mozilla Public License 2.0](LICENSE). Third-party notices are in [NOTICE](NOTICE).
The Stein name and logo are not covered by the licence: see the
[trademark policy](https://github.com/Stein-Project/.github/blob/main/TRADEMARK.md).

Stein is not an official Minecraft product. It is not approved by or associated with Mojang or Microsoft.

Created by [Breno Xavier](https://github.com/brenoxavier).
