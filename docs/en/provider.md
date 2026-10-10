# API Providers

Open **Settings → Providers**, then select a provider to edit its endpoint, protocol, credentials, or local models.

## Built-in Providers

Agora includes configurations for OpenAI, Anthropic, Google Gemini, DeepSeek, DashScope/Qwen, OpenRouter, OpenCode Go, OpenCode Zen, Groq, Ollama, and Local models. Provider catalogs and endpoint behavior can change independently of the app.

For a remote provider, the Base URL field shows its effective built-in default when no override is stored. Providers without a built-in endpoint may show a placeholder instead. A blank override resolves back to the provider default where one exists. Base URL edits save automatically after a 500 ms pause; there is no separate Save action.

## Custom Providers

A custom endpoint can use an OpenAI-compatible, Google, or Anthropic protocol. Configure its Base URL and protocol to match the server. Model synchronization follows the selected protocol, and models can still be added manually when discovery is unavailable.

**Responses API** switches from Chat Completions to the provider's `/responses` endpoint. It is available for the built-in OpenAI provider and custom providers using the OpenAI-compatible protocol.

## OpenCode Zen (anonymous)

**OpenCode Zen** reaches OpenCode's free model lane at `https://opencode.ai/zen/v1` and needs no account: it is ready to use as soon as it is selected, with no API key. Only models priced at zero by OpenCode's model metadata are offered — `/v1/models` publishes no price, and a paid id answers `401` without a real Zen API key, so it is kept out of the catalog rather than failing on first use. The id is not treated as a signal: a zero-cost model such as `big-pickle` carries no `-free` hint in its name.

Agora presents the request as an OpenCode CLI session, which the lane requires: a canonical session id in the correlation headers and an agent-shaped streaming body. The tool names that gate adds exist only to satisfy that check — they are never executed, and a real Agora tool is always offered first when tools are enabled.

## API Keys

A provider can store multiple named API keys. Select the radio button beside one key to make it active for that provider. Keys can be added, edited, or deleted independently.

API keys are stored in preferences rather than the Room conversation database. `SecretCrypto` normally applies an Android Keystore AES-256-GCM envelope, but legacy plaintext remains readable and encryption failure falls back to plaintext rather than losing the value. Requests send the active credential only to the configured destination when needed. Because the Base URL determines the server contacted, verify custom endpoints carefully.

## Anthropic Cache

For the built-in Anthropic provider and custom providers using the Anthropic protocol, **Advanced** appears below API Keys. **Cache** is on by default. While enabled, **Cache Duration** offers **5m** and **1h**, with **1h** selected by default. Each provider saves changes immediately.

Turning Cache off omits Agora's top-level `cache_control` request marker and retains the selected duration for later. It does not disable caching added by a relay. Match the duration to your relay policy, or turn off Agora's marker when the relay manages caching. Changing a custom provider to another protocol hides these controls without erasing their values.

## Local Models

The **Local** provider imports chat models from GGUF files. Each entry has a model ID and alias, context size, temperature, Top P, and maximum output-token setting. An optional vision projector (`.mmproj`) adds vision support and is shown on the model row.

Use **Model Idle Retention** under Advanced to choose how long an unused local model stays loaded before Agora unloads it. Deleting a local entry removes the app-owned model and projector copies. When an edit replaces or removes a projector, Agora removes the old projector copy.

Optional exports can include provider secrets, but those secrets are unencrypted inside the archive. See [Models](models.md), [Local Models](local-model.md), [Import & Export](import-export.md), and [Privacy & Security](privacy.md).
