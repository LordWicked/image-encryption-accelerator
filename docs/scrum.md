# Scrum plan

How we develop the image encryption accelerator described in [project.md](project.md).

- **MVP demo:** Tuesday Oct 20, 2026
- **Final hand-in:** Tuesday Nov 24, 2026

## How we work

- **Backlog:** GitHub Issues on a GitHub Projects board. Sprints are the project's Sprint field; the MVP and the hand-in are milestones. Step-by-step setup and a worked example in [github-scrum.md](github-scrum.md).
- **Sprint length:** 1 week, Tuesday to Tuesday.
- **Definition of Done:**
  - the story has a chiseltest (or Scala) test that proves it works;
  - `sbt test` passes in CI;
  - a teammate reviewed and merged the PR;
  - docs are updated if the interface or parameters changed.
- **Ceremonies:**
  - *Planning* at the start of the sprint: pick stories, one owner each.
  - *Standup*: async in the group chat, 2 to 3 times a week (done, next, blocked).
  - *Review*: demo of passing tests or a simulation run.
  - *Retro*: 10 minutes, one thing to keep and one to change.

The course asks "How did you incrementally develop it?", so we keep a short note of each sprint review. They become material for the final presentation.

## Timeline

| Sprint | Dates | Goal |
|---|---|---|
| 0 | Sep 29 – Oct 6 | Setup: board, CI, protected `main`, Ascon spike, Sprint 1 planned |
| 1 | Oct 6 – Oct 13 | Building blocks: software reference, permutation, block packing |
| 2 | Oct 13 – Oct 20 | Full encryption core in the pipeline. **MVP demo** |
| 3 | Oct 20 – Oct 27 | Decryption on the FPGA |
| 4 | Oct 27 – Nov 3 | Rounds-per-cycle parameter and first synthesis |
| 5 | Nov 3 – Nov 10 | Load the image over UART on the Nexys A7 |
| 6 | Nov 10 – Nov 17 | Color images, or catch up |
| 7 | Nov 17 – Nov 24 | No new features: docs, final presentation, buffer. **Hand-in** |

## MVP scope

Simulation only, 128x128 grayscale image. To keep Sprint 2 feasible:

- fixed key and nonce;
- no associated data;
- Ascon-AEAD128 as standardized in NIST SP 800-232 (not the older Ascon-128a, whose outputs differ).

## Sprint 0 (Sep 29 – Oct 6): setup

Details and the issues to create are in [github-scrum.md](github-scrum.md#5-worked-example-sprint-0-tue-sep-29--tue-oct-6).

- [ ] **Board.** GitHub Project with Sprint and Estimate fields, milestones and labels.
- [ ] **CI.** GitHub Actions runs `sbt test` on every push and PR.
  - *Done when:* a PR shows a green check.
- [ ] **Protected `main`.** A PR, 1 approval and green CI are required to merge.
- [ ] **Ascon spike.** Notes on Ascon-AEAD128 and the NIST test vectors in the repo.
- [ ] **Backlog.** Sprint 1 and 2 stories written as issues.

## Sprint 1 (Oct 6 – Oct 13): building blocks

Three tracks in parallel. The fourth person starts the encryption core's control logic early, which takes pressure off Sprint 2.

- [ ] **Software Ascon reference.** Ascon-AEAD128 encrypt and decrypt in Scala, used as the golden model in tests.
  - *Done when:* it matches the official NIST test vectors.
- [ ] **Ascon permutation in Chisel.** The round function (constant addition, S-box layer, linear layer) on the 320-bit state.
  - *Done when:* a chiseltest matches the software reference round by round and for the full 8 and 12 rounds.
- [ ] **Block packing.** Groups the 8-bit pixel stream into 16-byte blocks and unpacks them back, keeping the `DecoupledIO` handshake.
  - *Done when:* a chiseltest streams bytes through pack then unpack and gets them back unchanged.

## Sprint 2 (Oct 13 – Oct 20): MVP

- [ ] **Encryption core.** Control logic for initialization, plaintext blocks and finalization (tag), built on the permutation and block packing. Replaces the pass-through in `PassThroughEngine` with the same stream interface.
  - *Done when:* a chiseltest encrypts one short message and matches the reference.
- [ ] **Image test.** `ImagePipelineSpec` streams the 128x128 grayscale image through the core.
  - *Done when:* the ciphertext and tag match the software reference byte for byte.
- [ ] **Round trip.** Software decryption of the hardware output.
  - *Done when:* it recovers the original image exactly, and the saved encrypted image looks like noise.

**Risk:** if the permutation is not passing its tests by Oct 13, the MVP date is at risk.

## After the MVP (Sprints 3 – 7)

- [ ] **Sprint 3: Decryption on the FPGA.** Reuses the same permutation. Hardware encrypt then decrypt returns the original image.
- [ ] **Sprint 4: Rounds per cycle.** A Chisel parameter for how many permutation rounds run per clock cycle. Synthesize for the Nexys A7 and record area and timing per setting.
- [ ] **Sprint 5: UART loading.** Send the image from the PC to the board over the USB-UART bridge and read the encrypted image back.
- [ ] **Sprint 6: Color images.** Wider datapath or three channels. If earlier sprints slipped, this sprint absorbs the overflow and color is cut.
- [ ] **Sprint 7: Wrap-up.** Documentation, final presentation, buffer. No new features.
