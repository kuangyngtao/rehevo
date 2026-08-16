package interview.guide.modules.voiceinterview.service;

import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisAudioFormat;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisParam;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Qwen Audio streaming TTS service.
 *
 * Provides real-time text-to-speech synthesis using Alibaba Cloud DashScope's
 * qwen-audio-3.0-tts-flash model via DashScope's streaming TTS protocol.
 *
 * Key Features:
 * - WebSocket-based real-time TTS synthesis
 * - Synchronous API backed by streaming WebSocket audio
 * - Support for Chinese language with configurable voice, speech rate, and volume
 *
 * Configuration:
 * - Model: qwen-audio-3.0-tts-flash
 * - Voice: longanhuan_v3.6
 * - Audio format: PCM, 24kHz sample rate
 *
 * @see SpeechSynthesizer
 */
@Slf4j
@Service
public class QwenTtsService {

    // Runtime configuration values (loaded from VoiceInterviewProperties; setters kept for tests)
    private String model;

    private String apiKey;

    private String voice;

    private String format;

    private Integer sampleRate;

    private String mode;

    private String languageType;

    private Float speechRate;

    private Integer volume;

    public QwenTtsService(VoiceInterviewProperties voiceInterviewProperties) {
        applyTtsConfig(voiceInterviewProperties.getQwen().getTts());
    }

    public void reload(VoiceInterviewProperties voiceInterviewProperties) {
        applyTtsConfig(voiceInterviewProperties.getQwen().getTts());
        log.info("QwenTtsService reloaded: model={}, voice={}", model, voice);
    }

    private void applyTtsConfig(VoiceInterviewProperties.QwenTtsConfig tts) {
        this.model = tts.getModel();
        this.apiKey = tts.getApiKey();
        this.voice = tts.getVoice();
        this.format = tts.getFormat();
        this.sampleRate = tts.getSampleRate();
        this.mode = tts.getMode();
        this.languageType = tts.getLanguageType();
        this.speechRate = tts.getSpeechRate();
        this.volume = tts.getVolume();
    }

    /**
     * Initialize the TTS service.
     * This method is automatically called by Spring after the service is constructed
     * and all configuration values have been loaded from VoiceInterviewProperties.
     *
     * @throws IllegalStateException if apiKey is not configured
     */
    @PostConstruct
    public void init() {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("API key must be configured before initializing QwenTtsService");
        }
        log.info("QwenTtsService initialized with model: {}, voice: {}, sampleRate: {}Hz",
                 model, voice, sampleRate);
    }

    /**
     * Synthesize text to speech audio.
     *
     * This method synchronously converts text to PCM audio data using the DashScope
     * WebSocket-based TTS API. It establishes a WebSocket connection, sends the text for
     * synthesis, collects audio chunks, and returns the complete audio data.
     *
     * The method uses CountDownLatch to wait for synthesis completion with a 30-second
     * timeout to prevent indefinite blocking.
     *
     * @param text Text to synthesize (null, empty, or whitespace-only text returns empty array)
     * @return PCM audio data at configured sample rate, or empty array if synthesis fails
     */
    public byte[] synthesize(String text) {
        // Handle null, empty, or whitespace-only text
        if (text == null || text.trim().isEmpty()) {
            log.debug("Empty or null text provided, returning empty audio array");
            return new byte[0];
        }

        log.debug("Starting TTS synthesis for text: {} characters", text.length());

        try {
            SpeechSynthesisParam param = SpeechSynthesisParam.builder()
                    .model(model)
                    .apiKey(apiKey)
                    .voice(voice)
                    .format(getAudioFormat())
                    .speechRate(speechRate)
                    .volume(volume)
                    .languageHints(List.of(toLanguageHint(languageType)))
                    .build();
            SpeechSynthesizer synthesizer = new SpeechSynthesizer(param, null);
            ByteBuffer audioBuffer = synthesizer.call(text, 30_000L);
            byte[] audioData = new byte[audioBuffer.remaining()];
            audioBuffer.get(audioData);
            log.info("[TTS] Synthesis completed - model={}, voice={}, chars={}, bytes={}, firstPackageMs={}",
                    model, voice, text.length(), audioData.length, synthesizer.getFirstPackageDelay());
            return audioData;
        } catch (Exception e) {
            log.error("Failed to synthesize text", e);
            return new byte[0];
        }
    }

    /**
     * Get audio format for Qwen TTS Realtime.
     * Currently supports 24kHz PCM format.
     *
     * @return QwenTtsRealtimeAudioFormat enum value
     */
    private SpeechSynthesisAudioFormat getAudioFormat() {
        String normalizedFormat = format == null ? "pcm" : format.toLowerCase();
        if ("wav".equals(normalizedFormat)) {
            return switch (sampleRate) {
                case 8000 -> SpeechSynthesisAudioFormat.WAV_8000HZ_MONO_16BIT;
                case 16000 -> SpeechSynthesisAudioFormat.WAV_16000HZ_MONO_16BIT;
                case 48000 -> SpeechSynthesisAudioFormat.WAV_48000HZ_MONO_16BIT;
                default -> SpeechSynthesisAudioFormat.WAV_24000HZ_MONO_16BIT;
            };
        }
        return switch (sampleRate) {
            case 8000 -> SpeechSynthesisAudioFormat.PCM_8000HZ_MONO_16BIT;
            case 16000 -> SpeechSynthesisAudioFormat.PCM_16000HZ_MONO_16BIT;
            case 48000 -> SpeechSynthesisAudioFormat.PCM_48000HZ_MONO_16BIT;
            default -> SpeechSynthesisAudioFormat.PCM_24000HZ_MONO_16BIT;
        };
    }

    private String toLanguageHint(String configuredLanguage) {
        if (configuredLanguage == null || configuredLanguage.isBlank()) {
            return "zh";
        }
        return configuredLanguage.toLowerCase().startsWith("chinese") ? "zh" : configuredLanguage.toLowerCase();
    }

    /**
     * Destroy the service and cleanup resources.
     *
     * This method is called automatically when the Spring container shuts down.
     * Currently, no persistent resources need cleanup as each synthesis creates
     * its own temporary connection.
     */
    @PreDestroy
    public void destroy() {
        log.info("QwenTtsService destroyed successfully");
    }

    // Setter methods for configuration (used by Spring @Value injection or tests)

    public void setModel(String model) {
        this.model = model;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public void setVoice(String voice) {
        this.voice = voice;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public void setSampleRate(Integer sampleRate) {
        this.sampleRate = sampleRate;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public void setLanguageType(String languageType) {
        this.languageType = languageType;
    }

    public void setSpeechRate(Float speechRate) {
        this.speechRate = speechRate;
    }

    public void setVolume(Integer volume) {
        this.volume = volume;
    }
}
