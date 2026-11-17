# Device language

Conversate follows the **active system language on the Rokid glasses**, not the browser language or the factory locale. Change the language in Rokid settings; there is no separate Conversate language preference.

The lens interface, companion website, new AI summaries, generated titles, cues and term definitions use that language. Existing transcripts, saved notes, previous summaries, source quotations and spoken term spellings retain their original text. The speech recognizer remains Rokid's built-in caption service: select the recognition language in the Rokid caption/phone settings where necessary.

Korean and English interface messages are built in. For other languages, the connected Rokid AI translates application-owned interface messages in batches and saves them locally. **English is shown while translations are being prepared or when AI is unavailable.** The Settings page shows the detected language and preparation status. Previously cached translations can be reused without generating them again. Translation coverage and quality depend on the Rokid language model; not every language has been manually reviewed.

UI translation sends only application messages, never meeting recordings or user notes. Meeting AI tasks separately use the actual transcript as before. No external AI provider or API key is added.

The companion login page uses the glasses language too. Reload the page after installing a new version and use the current PIN shown on the glasses. An app service restart generates a new PIN.

Validation: JVM locale/cache/placeholder and prompt tests; desktop and mobile companion tests for English, live Japanese label updates, and unchanged original transcripts; real Rokid AI translation checks for Spanish and Japanese. Actual global device-language switching during a meeting and comprehensive human translation review remain unverified.
