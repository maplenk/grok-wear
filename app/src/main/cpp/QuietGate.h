// Lets the wake word engine skip quiet audio, where its frontend and model would otherwise run all the time.
// The gate keeps the last 1.5 s of quiet audio and, when sound starts, hands that over first: the model
// looks back about 1.4 s, so it hears the whole phrase and its state is as if it had heard everything.
// Quiet means within about 8 dB of the background, tracked as the lowest recent level.
#ifndef QUIET_GATE_H
#define QUIET_GATE_H

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <vector>

class QuietGate {
public:
    explicit QuietGate(int sampleRate)
        : frame_(static_cast<size_t>(std::max(1, sampleRate / 100))),
          ring_(static_cast<size_t>(std::max(1, sampleRate)) * PREROLL_MS / 1000) {}

    /**
     * Passes the samples to process(const int16_t*, size_t) -> bool while there's sound, preceded by the
     * quiet audio kept since the engine last heard any. Returns true as soon as process does.
     */
    template <typename Process>
    bool feed(const int16_t* samples, size_t count, Process&& process) {
        size_t runStart = 0;
        bool running = false;
        for (size_t at = 0; at < count; at += frame_) {
            size_t n = std::min(frame_, count - at);
            total_ += n;
            if (loud(samples + at, n)) hangover_ = HANGOVER_FRAMES;
            if (hangover_ > 0) {
                --hangover_;
                if (!running) {
                    if (flush(process)) return true;
                    runStart = at;
                    running = true;
                }
            } else {
                if (running) {
                    running = false;
                    processed_ += at - runStart;
                    if (process(samples + runStart, at - runStart)) return true;
                }
                keep(samples + at, n);
            }
        }
        if (!running) return false;
        processed_ += count - runStart;
        return process(samples + runStart, count - runStart);
    }

    /** Share of the audio the engine heard since the last call, in percent; for logs. */
    int takeHeardPercent() {
        int percent = total_ == 0 ? 0 : static_cast<int>(processed_ * 100 / total_);
        processed_ = 0;
        total_ = 0;
        return percent;
    }

    void reset() {
        start_ = 0;
        kept_ = 0;
        hangover_ = 0;
        floor_ = INITIAL_FLOOR;
    }

private:
    static constexpr size_t PREROLL_MS = 1500;
    // Stays open 1 s after the last loud frame, so the pauses inside a phrase don't cut it
    static constexpr int HANGOVER_FRAMES = 100;
    // Mean square of a 10 ms frame: sound must be 6x (about 8 dB) above the background...
    static constexpr float ONSET_RATIO = 6.0f;
    // ...and above an RMS of 10, where Android's voice recognition level puts quiet speech near 40 dB SPL
    static constexpr float MIN_ENERGY = 100.0f;
    // The background estimate follows quieter frames at once and rises about 0.9 dB a second
    static constexpr float FLOOR_RISE = 1.002f;
    static constexpr float INITIAL_FLOOR = 1e12f;

    bool loud(const int16_t* samples, size_t n) {
        int64_t sum = 0;
        for (size_t i = 0; i < n; i++) sum += static_cast<int32_t>(samples[i]) * samples[i];
        float energy = static_cast<float>(sum) / static_cast<float>(n);
        floor_ = energy < floor_ ? energy : floor_ * FLOOR_RISE + 1.0f;
        return energy > std::max(floor_ * ONSET_RATIO, MIN_ENERGY);
    }

    void keep(const int16_t* samples, size_t n) {
        size_t capacity = ring_.size();
        for (size_t i = 0; i < n; i++) {
            if (kept_ < capacity) {
                ring_[(start_ + kept_) % capacity] = samples[i];
                kept_++;
            } else {
                ring_[start_] = samples[i];
                start_ = (start_ + 1) % capacity;
            }
        }
    }

    // Hands over the kept audio, oldest first, and empties the ring
    template <typename Process>
    bool flush(Process& process) {
        if (kept_ == 0) return false;
        size_t capacity = ring_.size();
        size_t first = std::min(kept_, capacity - start_);
        size_t second = kept_ - first;
        size_t from = start_;
        processed_ += kept_;
        start_ = 0;
        kept_ = 0;
        if (process(ring_.data() + from, first)) return true;
        return second > 0 && process(ring_.data(), second);
    }

    size_t frame_;
    std::vector<int16_t> ring_;
    size_t start_ = 0;
    size_t kept_ = 0;
    int hangover_ = 0;
    float floor_ = INITIAL_FLOOR;
    uint64_t processed_ = 0;
    uint64_t total_ = 0;
};

#endif // QUIET_GATE_H
