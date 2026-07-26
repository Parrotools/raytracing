# 06 — PBR Material System

## Model

Each material is a 48-byte std430 record (three vec4s) in the materials SSBO:

| field | packing | meaning |
|---|---|---|
| `albedo.rgb`, `roughness` | vec4 | linear base color; GGX perceptual roughness |
| `emission.rgb`, `metallic` | vec4 | radiance (linear HDR, pre-scaled by intensity) |
| `ior`, `transmission`, `flags`, `sssApprox` | vec4 | refraction index; 0–1 transmission; bitflags; wrap-lighting strength |

Flag bits: `1 = NON_OCCLUDING` (skipped in voxelization, light entry kept),
`2 = FLUID`, `4 = SSS_WRAP` (leaves/plants use wrap diffuse
$\langle n\cdot l\rangle \to \frac{n\cdot l + w}{1+w}$ as a cheap subsurface
approximation).

Voxels store a 16-bit material id → the table; id 0 is air.

## Sources, in priority order

1. **Curated table** — `assets/raytracing/materials.json`, ~70 entries covering the
   required set: stone family, dirt, grass, sand, water, glass (tinted variants),
   all ores (emissive-free but characteristic albedo + metallic sheen for exposed
   metal ores), wood/logs/planks/leaves, iron/gold/copper/diamond/emerald/netherite
   blocks (true metals: albedo = F0), lava & magma (strong emission), glowstone,
   sea lantern, shroomlight, torches/lanterns (non-occluding light sources), ice,
   snow, obsidian, amethyst, terracotta, concrete, wool, nether/end stones.
2. **Rule matching** — the JSON's `rules` section matches block-id substrings
   (`"*_ore"`, `"*_log"`, `"*_leaves"`, `"*glass*"`, `"*_wool"` …) so modded and
   unlisted vanilla blocks inherit sensible families.
3. **MapColor fallback** — any remaining block derives albedo from
   `BlockState.getMapColor(...).col` (sRGB→linear) with roughness 0.85, plus
   emission scaled from `getLightEmission()/15` when the block glows. Nothing
   renders black or matte-wrong.

User overrides: `config/raytracing/materials.json` merges over the built-in table.

## Representative values (linear sRGB)

| block | albedo | rough | metal | emission | ior/trans | notes |
|---|---|---|---|---|---|---|
| stone | 0.35 0.35 0.36 | .85 | 0 | — | — | |
| grass top | 0.13 0.42 0.11 | .80 | 0 | — | — | SSS wrap 0.25 |
| water | 0.02 0.08 0.12 | .02 | 0 | — | 1.33 / 0.92 | tinted transmission |
| glass | 0.95 0.95 0.95 | .01 | 0 | — | 1.50 / 0.95 | |
| iron block | 0.77 0.78 0.78 | .28 | 1 | — | — | F0 = albedo |
| gold block | 1.00 0.71 0.29 | .22 | 1 | — | — | |
| lava | 0.85 0.30 0.05 | .9 | 0 | 12 4.2 0.8 | — | dominant GI source |
| glowstone | 0.95 0.80 0.45 | .7 | 0 | 9 7 3.5 | — | |
| torch (non-occl.) | — | — | — | 10 6.5 3 | — | point-light entry only |
| leaves | 0.10 0.30 0.08 | .75 | 0 | — | — | SSS wrap 0.35, non-occl. |
| diamond ore | 0.42 0.44 0.45 | .55 | .15 | faint .1 .3 .3 | — | subtle sparkle via low rough patches (roadmap: per-face) |

Full table in `src/main/resources/assets/raytracing/materials.json`.

## BlockState → id resolution

`Materials` keeps an `IdentityHashMap<BlockState, Character>` cache (BlockStates are
interned singletons). Resolution: exact block-id match → rule match → MapColor
synthesis (deduplicated, so two gray blocks share one synthesized material). The
table is uploaded once at level join and re-uploaded only when overrides reload.
Waterlogged non-air blocks keep the solid's material; the standalone water block maps
to the water material (flowing water: same, full-cube approximation, see roadmap).
