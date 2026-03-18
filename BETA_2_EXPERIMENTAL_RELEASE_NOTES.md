# TapInsight Beta 2 Experimental

This build was prepared from a scrubbed temporary export of the working repo and rebuilt outside the source tree.

## Included updates

- LearnLM tutoring mode for AR learning workflows
  - Uses Gemini/LearnLM-style tutoring for step-by-step teaching in the chat panel.
  - Supports lesson continuation from saved on-device lesson history.
  - Saves lesson context and reference images in app-private storage for LearnLM use.
  - Good for academia, vocational tasks, gardening, cooking, electronics, and guided problem solving.
  - Example prompts:
    - `learnlm help me solve this triangle`
    - `learnlm help me understand DC circuits`
    - `learnlm walk me through pruning tomatoes`

- Better handling of multiple mapped locations
  - Multi-address/place responses can open a dedicated multi-pin map flow instead of collapsing into a single destination.
  - Intended for chats like several restaurants, several addresses, or grouped destination results.
  - Example prompts:
    - `give me the addresses of several organic restaurants near me`
    - `show me several coffee shops near me`

- TapBrowser audio visualizer
  - Adds an audio visualizer experience inside the masked/eyeball mode.
  - Useful for radio/music playback on-glasses while keeping the display minimal.

- Camera and chat card presentation cleanup
  - Camera framing and chat-card presentation were adjusted so responses look cleaner and less visually cramped.

## Experimental status

This is an experimental release. Expect iteration, especially around advanced multimodal flows, live tutoring continuity, and map presentation edge cases.

## Release assets

- APK: `TapInsight-Beta2-Experimental.apk`
- Source archive: `TapInsight-Beta2-Experimental-source.tar.gz`
