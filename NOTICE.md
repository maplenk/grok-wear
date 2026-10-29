# Notices

## Trademarks

Grok, Grok Bot and xAI are trademarks of xAI. Cursor is a trademark of its owner. Android and Wear OS are trademarks of Google LLC. Galaxy Watch is a trademark of Samsung Electronics Co., Ltd. Other names may be trademarks of their respective owners.

Grok Wear is an independent, unofficial project. It is not affiliated with, endorsed by or sponsored by xAI, Cursor, Google or Samsung. These names are used only to describe what the app works with. The project does not use any xAI logo, and its app icon is an original design.

## Bot avatar design data (not covered by the MIT License)

`app/src/main/assets/bot-avatar.json` contains Bot avatar outlines, eye expressions, colors and animation timing taken from the official Grok Bot desktop application, plus the pumpkin outline and stem from the official Grok Bot Android app. **This design data belongs to xAI.** It is not covered by this repository's MIT License, and no license to it is granted here.

It is included only so the watch can show each Bot with the avatar shape and color that its owner chose in Grok Bot. It will be removed promptly if xAI asks. To report a concern, open an issue or contact the maintainers privately as described in [SECURITY.md](SECURITY.md).

## Wake word model (not covered by the MIT License)

`app/src/main/assets/wakeword/hello_grok.tflite` is the “Hello Grok” detector, trained for Grok Wear with [microWakeWord](https://github.com/OHF-Voice/micro-wake-word) (Apache License 2.0). **The model is licensed for non-commercial use only**, because some of the audio it learned from is. It is not covered by this repository's MIT License. Grok Wear is free and has no ads or paid features; anyone reusing the model must keep to the same non-commercial terms.

What it was trained on:

| Data | Used for | License |
|---|---|---|
| Synthetic “Hello Grok” and look-alike phrases from Piper's [LibriTTS-R](https://www.openslr.org/141/) generator | What the phrase sounds like | CC BY 4.0 (LibriTTS-R, Koizumi et al.) |
| [microWakeWord negative features](https://huggingface.co/datasets/kahrendt/microwakeword): CHiME-6, DiPCo, FMA, FSD50K, WHAM!, LibriSpeech, VOiCES | Speech and sound that must not trigger it | CC BY-NC 4.0 |
| [Free Music Archive](https://github.com/mdeff/fma) (extra-small set) and one part of [AudioSet](https://research.google.com/audioset/) | Background music and noise mixed into the samples | Each track and clip keeps its own license, some non-commercial |
| [MIT environmental impulse responses](https://mcdermottlab.mit.edu/Reverb/IR_Survey.html) | Room echo | As published by the McDermott Lab, MIT |

## Third-party software

The app depends on open-source libraries under their own licenses; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
