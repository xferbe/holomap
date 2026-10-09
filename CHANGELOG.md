# Changelog

## 0.2.1

- Below the ground, dioramas now show a few layers of the real soil (dirt, sand, gravel) and then stone, netherrack
  or end stone, like the world itself. Before, the last block was repeated down to the base, so hills looked like
  tall blocks of dirt.
- Fixed holes in dioramas where the ground has air right below it: a house floor on a slope, sand over a cave. You
  could see through them to the flat map underneath.

## 0.2.0

- Fixed fog on dioramas: the edges of every map faded into the sky color, and maps placed side by side showed a pale
  seam between them. Fog now uses the real distance from the camera.
- The mod is now called HoloMap and has an icon.
- Removed the player arrows from the dioramas. The server no longer sends every player's position four times a
  second, and the client no longer draws them.
- Terrain is sent to each player from a queue, nearest chunks first, at a set number of chunks per second
  (`chunksPerSecond`, 40 by default). The server reads only what changed instead of scanning every map's area.
- The autosave only rewrites the dimensions that changed.
- A version check: when the server runs another HoloMap version, you get a chat message and the dioramas use only
  the terrain your game has loaded, instead of reading packets they do not understand.
- Rebuilds have a shared budget: at most one every 5 ticks across all maps, nearest first. The world changes by
  itself all the time (grass turning to dirt, kelp growing), and each change used to rebuild every map around it.
- A cap on video memory for dioramas (`gpuMemoryMb`, 256 by default). Over it, the least recently seen ones are
  dropped first.
- A margin around each level of detail distance, so a diorama no longer flickers between two levels when you stand
  right at the limit.
- Dioramas get darker at night along with the world. Torchlight still lights them.
- Water depth: the floor of rivers and the sea gets darker the deeper it is.
- `config/holomap.json`: vertical scale (to make flat terrain easier to read), level of detail distances, video
  memory cap and chunks per second.
- `/holomapstats` (client) and `/holomap info` (server) show what the dioramas cost right now.
- Unit tests for the terrain format, the packets, the config and the level of detail, and a load test with a 3×3 wall
  of maps in the game test.

## 0.1.0

- First version: maps in item frames become live 3D terrain dioramas.
