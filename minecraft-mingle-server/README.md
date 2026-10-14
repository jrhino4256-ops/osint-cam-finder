# Minecraft Mingle Server

A self-contained Minecraft (Paper 1.21.11) server that runs **Mingle**, the
carousel game from *Squid Game*, built from scratch: a custom plugin that
generates its own void world, arena and lobby. There are no maps to download
and no other plugins needed.

## How a game plays

1. Players wait on a glass lobby floating above the arena. When `min-players`
   are online, a countdown starts.
2. **Spin:** everyone is put on the carousel. The coloured wedges rotate,
   a waltz plays on note blocks and the floor carries you around.
3. **Number:** the music stops and a number is called (a big red title plus
   that many bell rings). Some of the 16 rooms around the walls open their
   doors.
4. **Find:** get into an open room with **exactly** that many players. When a
   room hits the number, its door turns red and locks everyone inside.
   You can punch people to shove them out (punches do no damage).
5. **Resolve:** anyone not in a locked room is struck by (harmless) lightning
   and becomes a spectator.
6. Repeat until `winners` players remain, then fireworks play and everyone
   goes back to the lobby for the next game.

Only `(alive - 1) / number` rooms open each round, so there is never room for
everybody. At least one player goes out every round and every game ends.

## Run it

Requirements: Java 21+, Maven, curl and python3. Alternatively, use Docker.

```bash
EULA=true ./start.sh          # build plugin, download Paper, start on :25565
```

`EULA=true` means you accept the [Minecraft EULA](https://aka.ms/MinecraftEULA).
Other options: `MEMORY=4G`, `MC_VERSION=1.21.11`.

Or with Docker:

```bash
docker compose up --build     # EULA=true is set in docker-compose.yml
```

Then connect to `localhost:25565` with a 1.21.11 client. Run `op <name>` in the
console to get admin commands.

## Commands

| Command | Who | What |
|---|---|---|
| `/mingle join` | everyone | Queue for the next game (default for everyone) |
| `/mingle leave` | everyone | Spectate instead of playing |
| `/mingle status` | everyone | Current state, round, players alive |
| `/mingle start` | `mingle.admin` (ops) | Skip the countdown (needs 2+ players) |
| `/mingle stop` | `mingle.admin` | Abort the game and return everyone to the lobby |
| `/mingle rebuild` | `mingle.admin` | Regenerate the arena |
| `/mingle reload` | `mingle.admin` | Reload `config.yml` and rebuild the arena |

`/mg` is an alias.

## Configuration

`server/plugins/Mingle/config.yml` is created on first start. It controls the
carousel radius, number of rooms, room size, phase timings, the range of
numbers that can be called, and how many winners end the game. After editing
it, run `/mingle reload`.

## Layout

```
minecraft-mingle-server/
├── start.sh / build.sh          build + download Paper + launch
├── Dockerfile, docker-compose.yml
├── server/                      server.properties, bukkit.yml (everything else is generated)
└── plugin/                      Maven project for the Mingle plugin
    └── src/main/java/dev/mingle/
        ├── MinglePlugin.java    bootstrap, world setup, /mingle command
        ├── Arena.java           builds arena, rooms, lobby; spins the carousel; doors
        ├── Room.java            one lockable room
        ├── Game.java            state machine: lobby → spin → find → resolve → winner
        ├── GameListener.java    no damage/hunger/griefing, shoving, join/quit
        └── VoidGenerator.java   empty world generator
```

## Testing

The server was started headless and played by 5
[mineflayer](https://github.com/PrismarineJS/mineflayer) bots that read the
called number, found the open doors and grouped up. Rooms locked at the exact
count, players outside were eliminated, a winner was declared after 3 rounds,
and the next game started on its own. The server log had no errors.
