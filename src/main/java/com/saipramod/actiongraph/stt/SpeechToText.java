package com.saipramod.actiongraph.stt;

/**
 * Audio -> "Speaker: text" transcript. Diarised STT (speaker labels) is what makes owner
 * attribution possible, so implementations must return one speaker turn per line.
 *
 * <p>Not bundled: plug in a provider (AWS Transcribe with speaker labels, Deepgram, AssemblyAI,
 * or OpenAI Whisper + a diarisation step) behind this interface. The upload endpoint returns
 * 501 until one is configured.
 */
public interface SpeechToText {

    String transcribe(byte[] audio, String filename);

    boolean available();

    /** Default bean: reports that no STT provider is configured. */
    class NotConfigured implements SpeechToText {
        @Override
        public String transcribe(byte[] audio, String filename) {
            throw new UnsupportedOperationException("No speech-to-text provider configured; upload a text transcript instead");
        }

        @Override
        public boolean available() {
            return false;
        }
    }
}
