# ImageGen asset prompts — 2026-08-11

이번 앱 이미지 교체는 ImageGen 스킬의 **기본 내장 도구 모드**로 진행했다. 입력·참조 이미지는 사용하지 않았고, 각 에셋은 서로 다른 호출로 생성했다.

## 공통 프롬프트

- Use case: `stylized-concept` (`ic_launcher_archive`만 `logo-brand`)
- Style/medium: premium minimal editorial 3D-paper illustration, tactile layered paper and softly embossed copper metal, clean geometric shapes, sophisticated Korean productivity app aesthetic
- Scene/backdrop: edge-to-edge deep archive-black charcoal backdrop, no phone mockup or outer frame
- Palette: `#151614`, `#292A26`, `#C07148`, `#7E8C78`, `#A9AAA3`, `#EEE8DC` only
- Constraints: no text, letters, numbers, UI labels, microphone, cloud, robot, human, external logos, watermark, fake writing, or sharp ECG zigzag
- Avoid: neon cyberpunk, blue/purple gradients, photorealism, busy particles, glassmorphism

## 개별 프롬프트 세트

### `art_onboarding_record.webp`

Create a continuous copper voice ribbon flowing left to right through four subtle 20-minute chunk boundaries. Completed sections become softly sealed rounded capsules with small abstract verification dots. Use a wide landscape composition with generous safe margins and simple silhouettes legible at 350dp.

### `art_onboarding_archive.webp`

Create a copper voice ribbon transforming left to right into three ordered archive objects: an audio capsule, a warm-paper transcript card represented by abstract relief lines, and a smaller optional summary card behind a consent ring. Settle the objects into a quiet three-slot archive drawer.

### `art_recording_ready.webp`

Create a calm ready state where a copper voice ribbon rests above continuous solid charcoal paper layers, with one warm-paper start bead and three subtle copper chunk markers. Keep the lower 35 percent dark and quiet for timer and status overlays. Do not use holes, openings, sockets, trays, cavities, cutouts, tunnels, or deep negative-space shapes.

### `art_recording_active.webp`

Create an active state where a smooth medium-amplitude copper voice ribbon continues across one fully solid raised charcoal chunk tile as a shallow embossed inlay. Add restrained concentric copper rings and a closed recording bead. Keep the lower 35 percent dark for overlays. Do not use a hollow capsule, opening, socket, cavity, cutout, tunnel, empty frame, or black void.

### `art_recording_saved.webp`

Create a saved state where the completed copper voice ribbon is neatly coiled inside three sealed warm-paper chunk capsules in a charcoal tray. Use small moss-green circular seals and copper closure lines without literal checkboxes. Keep the lower 35 percent dark for overlays.

### `art_library_empty.webp`

Create a quiet empty archive drawer with three clearly empty rounded slots. A copper voice ribbon approaches from the left and stops before the first slot, with one warm-paper bead waiting to be filed. Make it inviting and distinct from the onboarding archive transformation.

### `ic_launcher_archive.webp`

Design one centered abstract launcher mark where a copper voice thread folds into a rounded archive slot and ends in a recording bead. Let the silhouette subtly suggest an uppercase A without becoming a literal letter. Keep the full mark in the central 58 percent safe zone for circle and squircle crops, readable at 48px.

## 생성 원본과 프로젝트 적용본

| 프로젝트 적용본 | ImageGen 생성 원본 |
|---|---|
| `app/src/main/res/drawable-nodpi/art_onboarding_record.webp` | `/Users/iriver/.codex/generated_images/019fef3e-09ca-7532-8cb5-bf43668f9ef3/exec-0d6395a9-0985-4e34-9060-9c97bf8b0b49.png` |
| `app/src/main/res/drawable-nodpi/art_onboarding_archive.webp` | `/Users/iriver/.codex/generated_images/019fef3e-09ca-7532-8cb5-bf43668f9ef3/exec-1e239139-d83f-4a09-904b-bf91ce01affd.png` |
| `app/src/main/res/drawable-nodpi/art_recording_ready.webp` | `/Users/iriver/.codex/generated_images/019fef3e-09ca-7532-8cb5-bf43668f9ef3/exec-0e531fd1-10af-476c-bf48-ba9e48b507ba.png` |
| `app/src/main/res/drawable-nodpi/art_recording_active.webp` | `/Users/iriver/.codex/generated_images/019fef3e-09ca-7532-8cb5-bf43668f9ef3/exec-b32d0095-933f-4622-8fb2-860144b7e1f9.png` |
| `app/src/main/res/drawable-nodpi/art_recording_saved.webp` | `/Users/iriver/.codex/generated_images/019fef3e-09ca-7532-8cb5-bf43668f9ef3/exec-e48bbc7d-9144-47ca-9eed-5059115a1933.png` |
| `app/src/main/res/drawable-nodpi/art_library_empty.webp` | `/Users/iriver/.codex/generated_images/019fef3e-09ca-7532-8cb5-bf43668f9ef3/exec-e7870c20-72d6-496d-9adc-f70936c45b1b.png` |
| `app/src/main/res/drawable-nodpi/ic_launcher_archive.webp` | `/Users/iriver/.codex/generated_images/019fef3e-09ca-7532-8cb5-bf43668f9ef3/exec-c0658bd1-8c07-45b7-b034-589fefea8101.png` |

프로젝트 적용본은 화면 용도에 맞게 768~1152px WebP로 축소·압축했으며, ImageGen 생성 원본은 변경하지 않았다.
