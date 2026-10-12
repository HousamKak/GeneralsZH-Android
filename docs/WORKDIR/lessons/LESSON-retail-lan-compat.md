# Retail LAN compatibility: the 30 Hz engine against original Zero Hour 1.04

Goal: a phone on the 30 Hz engine plays a LAN match against a PC running the original retail
Zero Hour 1.04 (no GeneralsOnline). Built under `SAGE_RETAIL_RULES` (top-level CMakeLists.txt),
which only the 30 Hz engine (`libmain.so`) is compiled with. The 60 Hz cross-play engine keeps the
GeneralsOnline rules and is never affected by anything below.

## What a retail LAN match checks

Nothing but the simulation. Retail 1.04's `LANAPI::handleRequestJoin` has the INI/EXE checksum
comparison commented out (EA's own source), TheSuperHackers keep it off for Zero Hour, and the
phone's empty serial passes the duplicate-serial check. So the exe checksum per store (CD, Steam,
EA app) does not matter on LAN; the per-frame logic checksum does.

## The reference: a VC6 build, not a modern one

TheSuperHackers' CI checks retail replays with VC6 builds only. A modern MSVC build is not retail
compatible: retail's floating point is x87 at 24-bit precision with VC6's code generation. The
working reference is TheSuperHackers' tree built with VC6 exactly as their CI does it (portable VC6
from itsmattkc/MSVC600 at the pinned commit and SHA-256, `cmake --preset vc6`), run as
`generalszh.exe -headless -replay <file>` in a retail game folder **without** a
`GeneralsOnlineGameData` folder inside it (the recursive archive scan would mount the patch). It
reproduces the recorded checksums of TheSuperHackers' retail replay set
(github.com/TheSuperHackers/GeneralsReplays, GeneralsZH/1.04).

Add a probe to the reference that writes, at each checkpoint, what the phone's net trace writes
(`crc parts`, `crc obj`, the matrix words, and at one chosen frame the whole checksum word
stream with section marks). Then a mismatch is a word-by-word diff, not a hunt.

## The loop that worked

1. Replay check on the phone at 30 Hz with `gx_net_trace.txt` (the phone dumps its whole word
   stream at the first mismatch).
2. Reference at the same frame (`CRCREF_FULL=<frame>`): diff section by section. Every case so far
   was inside the objects section, one or a few objects.
3. Per-object words: which word, by how much. A difference of `0x80` in a high byte is the sign of
   a zero; a difference of 1 in the last place is rounding.
4. Read retail's machine code for the function (`dumpbin /DISASM` on the reference's `.obj`): it
   says exactly what VC6 computed, in what order, and what it kept in a register.
5. If a value comes from a function, measure the function: a VC6 harness linked against the
   reference's own object file, under `setFPMode()`, over millions of inputs, then fit an exact
   emulation and check it on all of them.
6. Fix under `SAGE_RETAIL_RULES`, build, rerun every replay.

## Classes found (each fixed under SAGE_RETAIL_RULES)

| Class | Where | Retail does |
|---|---|---|
| Constant folding signs | `Thing::setOrientation`, the terrain normal in `getHeightMapHeight` | VC6 folded `0*a` to `+0` and `0-a` to `-a` in inlined cross products; IEEE gives `-0` where retail has `+0` and the reverse. Values equal, bits not, and the checksum hashes bits |
| Multi-step transcendentals at 24 bits | `ACos`/`ASin`, `WWMath::Acos/Asin` | `atan2(sqrtf((1-x)(1+x)), x)`; measured exact on 5.36M inputs. `sin`, `cos`, `tan`, `atan`, `atan2`, `sqrt`, `log`, `fmod` match `(float)f((double)x)` exactly; `exp` and `pow` do not but the simulation does not use them |
| Unrounded transcendental results | `Sin`/`Cos`/`Tan`/`ACos`/`ASin` return `X87Real` (`Lib/X87Real.h`) | retail's `Sin` is `fld; fsin; ret`: the result is never rounded before the caller multiplies it. `X87Real` rounds once per operation as 24-bit x87 does. Checked on 686 infantry moves: all reproduced |
| fpatan kept in a register | five `atan2` sites in `Locomotor` | `fpatan; fst desiredAngle; fsub angle`: the turn is `(float)(atan2 - angle)` |
| Reassociated additions | terrain height | `(a + b) + p3`, not `p3 + a + b` |
| Double constants | `(1.0 - fy)` in the terrain normal | rounded to float at every step under 24-bit precision |
| GeneralsOnline-measured rules | research on a foundation, start positions, logic RNG draws for particles, circle fill, turret alignment, AI group cleanup, instant death order, minefields, pack time, Aurora bomb, tunnels, Helix add-ons, team owner fallback, dying riders | the port matched these to the GeneralsOnline client; retail behaves as TheSuperHackers keep it under `RETAIL_COMPATIBLE_CRC` |
| STL algorithm | `PartitionSolver` | STLport 4.5.3 `std::sort` (`Common/RetailSort.h`), unstable above 16 elements; checked identical to VC6 + STLport |
| Division by a compile-time constant | everywhere (`-freciprocal-math` on the retail engine) | VC6 `/O2` multiplies by the rounded reciprocal: INI `"120%"` is 1.19999993. clang matches bit for bit, but also merges 3+ divisions by one variable and hoists loop-invariant divisors, which VC6 never did: those sites use `GX_VC6_DIVISIONS` / `gxVc6Div` (`Lib/X87Real.h`), found by diffing reciprocal `fdiv`s per function in the disassembly. Moved five replays 100 to 2100 frames further |
| Uninitialised variables | `AIPlayer::onUnitProduced` `supplyTruck` | VC6 left it `true`; TheSuperHackers keep `true` only `#if _MSC_VER < 1300`. With `false` the AI chose its next building the frame its dozer appeared. Grep the reference for `_MSC_VER < 1300` in simulation code: each is a retail behaviour the retail engine must also take |
| Float to int | `fast_float2long_round`, `realToIntTruncRef` | `fistp` rounds halves to even; `__ftol` converts to 64 bits and keeps the low dword, so NaN gives 0 (the GeneralsOnline client gives `0x80000000`) |
| Rotations | `Matrix3D::Rotate_*`, `Pre_Rotate_*`, `In_Place_Pre_Rotate_*`, `Vector3::Rotate_Z` (`gxRotSin/Cos` in `wwmath.h`) | inlined `fsin`/`fcos` stay in registers. Whether VC6 spills them depends on the function: compile the reference header with the reference flags (`/O2 /Ob2`, `/FAs`) and read each site. `Matrix3x3::Set(axis, angle)` rounds the sine but not the cosine and reorders `x*y*(1-c)` to `((1-c)*x)*y`; the simulation does not call it |

Ruled out by measurement: INI number parsing (VC6 `sscanf("%f")` is correctly rounded on all
2081 numbers in the retail INI files), the per-store exe checksum (not checked on LAN).

## Traps

- A replay check that "fails at frame 100" right after a phone disconnect is not a result: the
  script may have pulled a log from another session. Check `lastUpdateTime` and the log's
  `replay check ... started` line.
- The 30 Hz phone engine must not be the GeneralsOnline rules one: check `[GX-BUILD] sim flags:
  crc=1 ...` and `[GX-CRC] retail rules` in the log.
- The audit of our simulation against TheSuperHackers' tree found most GeneralsOnline-only
  changes in one pass; do that before chasing replays one divergence at a time.
- A divergence where the AI acts one frame early or late: trace the AI's decision per build list
  entry in both engines (`aibb` lines, `gxAibb` in `AISkirmishPlayer.cpp`) and the production
  completion frame. Same production frame with a different decision frame means the AI's own
  state differs, not the world.
- Test builds go out with `release=false`. `release=true` publishes the APK as the site's
  download and announces it to installed apps.
