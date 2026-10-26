# Installation

[Documentation index](../README.md) · [Project README](../../README.md)

1. Install **[Fabric Loader](https://fabricmc.net/use/installer/) 0.19.3 or newer**
   for Minecraft 26.1.2.
2. Install **[Meteor Client](https://meteorclient.com/)** build 42 or newer. Both
   need **Java 25**.
3. Download the JAR for your Minecraft version from
   **[Releases](https://github.com/Boyonbike/Comet-Meteor-Addon/releases)**, or
   [build it from source](building.md#build-and-tests).
4. Put the JAR in your `.minecraft/mods` folder and start the game.
5. Open Meteor's GUI and select the **Comet** category. **Highway Fly** coordinates flight with independent **Anti Portal**, **Auto Set Home** and **Auto Lobby** helpers.

The Fabric mod ID is now `comet` (previously `tnt-coating`). Remove any older Comet JAR before installing
a new one; two copies cannot load together. **xaero-waypoints** also needs
[Xaero's Minimap](https://modrinth.com/mod/xaeros-minimap) and **baritone-elytra**
needs Meteor's [Baritone](https://meteorclient.com/) build; every other module
works with Meteor alone.
