# Auto Cart (Fabric, Minecraft 26.1.2, client-side)

Press **X** once. The mod runs the whole sequence by itself:

1. place a rail in front of you
2. place a TNT minecart on it
3. step behind the minecart
4. ignite it with flint and steel
5. switch to the charged crossbow
6. aim at the minecart and fire

Press **X** again at any time to cancel safely (inventory is restored). Only one sequence can run at once.
Status is shown above the hotbar: `Auto Cart: ON - <current action>` / `Auto Cart: OFF`; failures are also printed to chat.

## Requirements in your inventory
Rail, TNT Minecart, Flint and Steel, and a **charged** Crossbow (anywhere in the inventory; they are moved into the hotbar with normal swap clicks and moved back afterwards).

## Build

Needs **JDK 25**.

Local (once, to create the wrapper jar, then normal wrapper use):
```
gradle wrapper --gradle-version 9.3.1
./gradlew build          # Windows: gradlew.bat build
```
Output: `build/libs/auto-cart-0.1.0.jar` (use the one without `-sources`).

GitHub: push the project; `.github/workflows/build.yml` installs JDK 25 + Gradle 9.3.1 and uploads the jar as a build artifact. It does not need the wrapper jar.

Install: put the jar in `mods/` with Fabric Loader >= 0.19.1 and Fabric API 0.145.4+26.1.2.

## How it stays honest with the server
* Placement / ignition / shot use vanilla methods (`useItemOn`, `interactAt`/`interact`, `useItem`) so the usual packets with sequence ids are sent. Inventory moves are vanilla `SWAP` clicks.
* Every action is followed by a verify stage that waits `verifyDelayTicks` + your ping, then checks real state: rail block present, new TNT minecart entity spawned, cart primed, crossbow no longer charged. Any failure aborts and restores the inventory; nothing continues blindly. There are no automatic retries.
* Aiming: the crossbow shot sets the player rotation only for the duration of the use call (the use packet carries the rotation) and restores it immediately, so the camera does not move. Reach and line-of-sight are checked client-side before ignition and firing.
* "Move behind": the player is already on the rail's axis in the normal case (a lone rail is placed along your facing direction). If not, you are walked to `standDistance` behind the cart by setting horizontal velocity (normal movement packets, no camera change); if blocked for ~10 ticks the sequence aborts.

## Config: `config/autocart.json` (created on first launch)
`stepDelayTicks`, `verifyDelayTicks`, `verifyTimeoutTicks`, `stageTimeoutTicks`, `fireDelayTicks`, `railDistanceMin/Max`, `standDistance` (max ~2.9; entity reach is 3), `walkSpeed`, `restoreInventory`.

## Code map (`src/main/java/dev/autocart`)
* `AutoCartMod` - entrypoint, X key, tick hook
* `AutoCartController` - state machine (PREPARE -> PLACE_RAIL -> VERIFY_RAIL -> PLACE_CART -> VERIFY_CART -> MOVE_BEHIND -> IGNITE -> VERIFY_IGNITE -> FIRE -> VERIFY_FIRE)
* `InventoryManager` - hotbar swaps and restore
* `RailPlacement` - finding a valid rail spot
* `AutoCartConfig` - JSON config
* `McCompat` - **all version-sensitive Minecraft calls**; if a 26.1.x name differs, fix it here

## Known limits
* The TNT minecart explodes about 2-3 blocks from you; expect to take damage.
* If the fuse state cannot be read via reflection (`isPrimed`), ignition cannot be verified and a warning is logged.
* Not yet compiled or run in-game by the author of this scaffold (see notes in the chat). Fix compile errors in `McCompat` first.
