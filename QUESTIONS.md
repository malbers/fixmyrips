# Questions for Michael (cloud session, MP3 artist chooser)

Nothing here blocked the work. Each item is a behaviour choice I made, or left as it was, that you may want to change.
The PR description has the same list.

1. **A new name Latin-1 can't hold.** If a TPE1/TPE2 frame is Latin-1 and the chosen spelling has characters Latin-1
   lacks (e.g. Japanese), the frame is rewritten as UTF-16 with a byte order mark (little-endian), which is valid in v2.3
   and v2.4. Otherwise the frame keeps its own encoding. Alternatives: refuse that album, or use UTF-8 in v2.4 tags.
   OK as it is?
2. **ID3v2.2 (old iTunes) artist tags** show as "MP3, can't change yet". Track and disc fixes already handle v2.2;
   adding TP1/TP2 would be a small follow-up. Do you want it in 1.0.3?
3. **Track fixes now leave unchanged frames alone.** Before, every MP3 write re-created TRCK and TPOS as Latin-1 even
   when their value didn't change. Now a frame is rewritten only when its value changes, so an artist fix doesn't touch
   TRCK. Side effect: a compressed or encrypted TPOS no longer blocks a track-only fix that doesn't change TPOS.
   OK?
4. **Results screen row.** When no artist group in the scan can be changed, the "Artist names" row on the results
   screen shows "MP3, can't change yet" in grey instead of "Choose", but it still opens the list so you can see which
   artists. Groups in that list that can't change are grey and not tappable. OK?
5. **Wording for other formats.** "MP3, can't change yet" also shows when an unchangeable album is another format
   (m4a, wav and so on). 1.0.2 already did this in the chooser's radio labels. Should non-MP3 albums say just
   "can't change yet"?
6. **What counts as unchangeable** (album shows "can't change yet"): no ID3v2 tag, ID3v2.2, unsynchronised tag,
   extended header, footer, more than one TPE1 or TPE2 frame, a compressed, encrypted or read-only TPE1/TPE2, or an
   unknown text encoding byte. OK?
7. **ID3v1 tail.** The ID3v1 tag at the end of the file (it has its own artist field) is never touched. A player that
   prefers v1 over v2 would still show the old spelling. The Eversolo reads v2 as far as I know. OK?
8. **Several artists in one frame** (v2.4 "A" NUL "B") never matches a plain name, so they are never changed. OK?

## Michael's answers (round 2, 2026-11-05)

- F1 Latin-1 to UTF-16 with BOM: **OK.**
- F2 ID3v2.2: **yes, it was small.** Done: TP1/TP2 are read and rewritten (6-byte v2.2 frame header, encodings 0 and 1
  only; any other encoding byte is refused as unknown). v2.2 albums are changeable in the chooser.
- F3 unchanged frames left alone: **OK, it's safer.**
- F4 subtitle when nothing can change: **reworded.** The results row reads "· these files can't be changed yet", and the
  Artist names screen's intro says there is nothing to choose instead of "Tap one to choose".
- F5 label: **"can't change yet"** (no "MP3"), in the list, the chooser button and the per-spelling note. README updated.
- F6 list of unchangeable cases: **OK.**
- F7 ID3v1 tail untouched: **OK; the Eversolo reads v2.**
- F8 several artists in one frame never changed: **OK.**

Test gate after round 2 (plain Java, JDK 21 `--release 17`, Hetzner): **OK (157 tests)**: 153 plus four v2.2 tests
(TP1 rewrite with every other frame byte-identical and undo to the original bytes; new TP2 plus a UTF-16 upgrade;
v2.2 album changeable in the chooser; an encoding v2.2 lacks is refused). The "can't change" chooser fixture now uses
a v2.3 tag with a compressed TPE1, since v2.2 is no longer unchangeable. `MainActivity` is outside this gate; the
Gradle build on desktop compiles it.
