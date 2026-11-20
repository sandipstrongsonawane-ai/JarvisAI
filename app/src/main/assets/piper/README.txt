JARVIS optional Piper voice engine
==================================

Piper is optional and currently configured for English only.

Expected files in this folder:
- en_GB-northern_english_male-medium.onnx
- en_GB-northern_english_male-medium.onnx.json
- espeak-ng-data/ (from the matching Piper release)

Native library:
- app/src/main/jniLibs/arm64-v8a/libpiper_jni.so

The app verifies that the ONNX model, JSON config, native library, and native
initialization are all successful before marking Piper ready.

Hindi and Marathi replies are routed to Android system TTS using the detected
reply language (including common Roman Marathi/Hinglish markers). The English
Piper model is never used to pronounce Hindi or Marathi text. If Piper is absent
or fails, Android system TTS remains the fallback.

No voice model is downloaded at runtime. To enable Piper, place the matching
licensed model files and espeak-ng-data in this directory before building.
