# TapInsight Beta 2 Experimental

TapInsight Beta 2 Experimental builds on the Beta 1 foundation with new multimodal learning, better grouped map handling, and UI polish aimed at day-to-day glasses use.

## What's new

### LearnLM tutoring mode
Use the glasses as a personal tutor in the chat panel.

Examples:
- `learnlm help me solve this triangle`
- `learnlm help me understand transistors`
- `learnlm teach me how to braise vegetables`
- `learnlm walk me through diagnosing this circuit`

Highlights:
- tutoring-first responses in chat
- lesson continuity for follow-up learning
- saved LearnLM lesson memory and reference images in app-private storage
- tuned for academic study, vocational skills, gardening, cooking, and electronics

### Better multiple-location mapping
Grouped destination replies now have better handling for multi-address workflows.

Examples:
- `give me the addresses of several organic restaurants near me`
- `show me several cafes near me`

Highlights:
- improved multiple-location handoff
- dedicated multi-pin map flow for grouped destinations
- better fit for multi-place Gemini responses

### TapBrowser visualizer
Masked/eyeball mode now supports a visualizer-oriented media experience for audio playback.

### Camera/chat card cleanup
Camera framing and chat-card presentation were adjusted to make the conversation layout cleaner on-glasses.

## Release assets
- `TapInsight-Beta2-Experimental.apk`
- `TapInsight-Beta2-Experimental-source.tar.gz`

## Security / packaging note
This release was prepared from a scrubbed temporary export of the working repository. Local SDK paths, private-IP defaults, and obvious secret patterns were scanned and removed from the publishable package before rebuilding.
