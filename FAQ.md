# FAQ

Questions that come up frequently, mostly collected from real [issue reports](https://github.com/RisorseArtificiali/anti-vocale/issues).

## Models and their limits

### How long can an audio file be?

There is no app-level limit. Each model has its own **per-segment** limit, and the app transparently splits longer audio into chunks and concatenates the transcripts:

| Model | Per-segment limit | Long audio |
|---|---|---|
| Whisper | 30 s | chunked by the app, any length |
| Qwen3-ASR | 30 s | chunked by the app, any length |
| Parakeet TDT | 6:40 (400 s, NVIDIA hard cap) | chunked by the app ([#50](https://github.com/RisorseArtificiali/anti-vocale/issues/50)) |
| Gemma (LLM) | 30 s | currently one segment |
| Nemotron 3.5 (streaming) | unbounded (streaming) | n/a |
| GigaAM v3 | unbounded | n/a |

The Parakeet limit is not arbitrary: the model's attention has a hard 5000-frame cap baked into NVIDIA's checkpoint, and 5000 frames at 12.5 frames/s is exactly 400 seconds. Inputs beyond it fail natively ([#44](https://github.com/RisorseArtificiali/anti-vocale/issues/44)); the app-side chunking that removes this limitation is tracked in [#50](https://github.com/RisorseArtificiali/anti-vocale/issues/50).

"Unbounded" in the table always means *the app does the splitting for you in software*: no model itself handles arbitrary length.

### Can I control where the chunk boundaries fall?

Yes, via **Settings → Strip Silence (VAD)**. With VAD on, boundaries fall on detected silence gaps (segments are merged up to ~28 s, WhisperX-style, no overlap so no repeated words). With VAD off, the app makes blind fixed-duration cuts.

### Why keep models with tight limits like Gemma's 30 seconds?

Because no model is universally better: different models win on different languages, accents, and recording conditions. We keep the choice with the user, and the [performance stats](#where-do-i-see-which-model-was-used-and-how-long-it-took) give you the data to compare on your own device.

Also, Gemma is not just another transcriber: it is a full LLM, the only model in the app capable of generative post-processing (summarization, restructuring, formatting). The 30-second cap limits the audio input, not that capability. The prompt driving it is customizable in **Settings → Transcription → Default Transcription Prompt**, with ready-made examples.

## Queue and concurrent requests

### What happens if I share a second audio while one is transcribing?

Nothing is lost. Requests are processed strictly in order: the second item is queued and starts automatically when the first finishes. Duplicate shares of the same item are deduplicated.

You get feedback from several places:

- a toast saying **"Added to queue"** when you share during an active transcription
- the foreground notification shows the queue position (**"Processing 2 of 3…"**) with the queued count
- each completed item gets its own result notification
- the **Logs** tab shows one entry per transcription with its state

### Where is the queue list?

The Logs tab *is* the list: every transcription appears there with a status (pending/done/error), timestamp, and processing time. The pending state currently lumps together "queued" and "actively processing"; splitting those into distinct labels is tracked in [#51](https://github.com/RisorseArtificiali/anti-vocale/issues/51).

## Results and metadata

### Where do I see which model was used and how long it took?

In the **Logs** tab: each entry shows "Processed in Xs" under the transcript, along with the timestamp and the audio duration. The model name is being added there, and a settings toggle will optionally surface a details row (model, time, task id) on result entries as well ([#45](https://github.com/RisorseArtificiali/anti-vocale/issues/45)).

### How do I delete or manage log entries?

Swipe an entry to delete it. A standard long-press context menu is being added alongside the gesture ([#52](https://github.com/RisorseArtificiali/anti-vocale/issues/52)), with options like delete, re-transcribe, and copy.
