# Athena Privacy Notes

Athena is designed as a local-first assistant.

- Conversations and saved memories are stored locally on the device running Athena.
- The bundled Android conversation engine does not require a Gemini or OpenAI account.
- The Windows companion keeps its local memory on that Windows computer.
- Microphone access is used for voice interaction and background wake listening when enabled.
- Android Accessibility is optional and is used only for user-requested deterministic device actions after the user enables it.
- The Windows companion accepts phone commands only from a paired device on the same local network.

Athena does not silently enable Android accessibility controls or claim that an action happened unless its command handler reports success.

Because Athena uses Android microphone foreground services for background wake, Android displays a persistent service notification while listening.
