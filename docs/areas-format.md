# Areas file

The 405 built-in areas are in `src/main/resources/com/goldmanmode/areas.json`. To test your own without rebuilding, export them from the drawing page in the same format and save the file as

```
~/.runelite/goldman-mode/areas.json
```

then toggle the plugin off and on. If that file exists it is used instead of the built-in list. Area ids are what unlocks are stored against, so renaming or redrawing an area keeps its unlock state.

## Format

```
[ { "id": "...", "n": "Lumbridge", "c": 3, "t": "notes",
    "parts": [ { "p": [[x,y], ...],            // outer outline, OSRS world tiles
                 "h": [ [[x,y], ...], ... ] }  // optional holes
             ] } ]
```

Several `parts` make one area out of separate pieces. Where areas overlap, the smaller one owns the overlapping tiles. Anything not inside an area (dungeons, underground, unmapped places) is never locked, and areas apply to every plane.
