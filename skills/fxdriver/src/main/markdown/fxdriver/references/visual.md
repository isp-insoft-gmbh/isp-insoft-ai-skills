# visual artifacts

fxdriver visual primitives are inputs for agent judgment, not a built-in quality
policy.

## Screenshot

```sh
java -jar @fxdriver.skill.jar@ screenshot <port> target/ui.png
```

Output shape:

```json
{
  "screenshot": {
    "jsonrpc": "2.0",
    "result": {
      "path": "...",
      "bytes": 12345,
      "activeWindow": { "title": "Main" },
      "visibleTextSample": ["Search", "Save"]
    }
  },
  "summary": {
    "path": "...",
    "bytes": 12345,
    "width": 1256,
    "height": 1408,
    "avgColor": { "r": 241, "g": 239, "b": 232, "a": 255 },
    "ahash": "feffdfdf7f7f7f3b"
  }
}
```

Read the image when making visual/layout/color claims. Screenshots are optimized
lossless PNGs by default (max PNG compression where available, RGB when fully
opaque). To avoid context bloat, prefer the smallest screenshot that can answer
the question once node/rect screenshots exist; keep full-size PNGs on disk for
audit/diff truth.

## Image summary

```sh
java -jar @fxdriver.skill.jar@ image-summary target/ui.png
```

Use for quick sanity:

- dimensions non-zero;
- file size plausible;
- hash changed/didn't change;
- average color roughly expected.

Do not use average color/hash as proof of layout quality.

## Image diff

```sh
java -jar @fxdriver.skill.jar@ image-diff target/before.png target/after.png target/diff.png
```

Output fields:

- `changedPixels`
- `changedPercent`
- `bounds`
- `sizeMismatch`
- `diff` path if requested

Use diff to find if something changed and where. Then inspect `after.png` and/or
`diff.png`. Diffs are meaningful only when images share compatible source rect,
scale, and dimensions.

## Context-efficient visual workflow

Use this progressive order:

1. `snapshot --summary` for windows/titles/key controls.
2. Full `snapshot` for structure/bounds/semantics.
3. `image-summary` for dimensions/hash sanity.
4. Read full screenshot only when layout/color/visual quality matters.
5. Prefer cropped/node/rect screenshots when they answer the question.
6. If downscaled/cropped image is unclear, request a larger crop or full PNG.

Screenshot target examples:

```json
{"target":"node","selector":"#details","path":"target/details.png"}
{"target":"rect","window":"w1","x":0,"y":0,"w":640,"h":360,"path":"target/crop.png"}
```

Do not use downscaled/cropped images as sole proof for tiny text, contrast, or
pixel-perfect diffs. JPEG/WebP output is intentionally not planned for now; UI
screenshots stay PNG.

## Common visual workflow

```sh
java -jar @fxdriver.skill.jar@ screenshot <port> target/before.png
# make app/code change; rebuild/relaunch/act
java -jar @fxdriver.skill.jar@ screenshot <port> target/after.png
java -jar @fxdriver.skill.jar@ image-diff target/before.png target/after.png target/diff.png
```

Checklist for human-readable UI:

- text contrast readable;
- icons visible on button backgrounds;
- no important text clipped/truncated unexpectedly;
- no controls off-screen;
- toolbars wrap or scroll instead of overflowing;
- panels have reasonable spacing;
- selected/disabled/focus states are distinguishable;
- screenshots match JSON state.

fxdriver should expose primitives for this; agent/skill decides if pass/fail.
