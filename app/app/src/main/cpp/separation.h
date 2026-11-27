#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace riyaz {

// MDX-Net vocal separation, signal side (the model itself runs in Kotlin via ONNX Runtime).
// Port of spike/mdx_portable.py `vocals_trimmed`: chunks of hop*(dimT-1) samples advance by
// chunk - nFft; each chunk -> STFT (periodic Hann, centred, reflect pad) cut to dimF bins with the
// first 3 zeroed -> model -> iSTFT -> keep the middle. Instrumental = mix - vocals * compensate.
class MdxJob {
public:
    static constexpr int kSampleRate = 44100;   // what the MDX models expect
    static constexpr int kHop = 1024;
    static constexpr int kDimT = 256;

    // Reads the song (16-bit stereo PCM at songRate). Returns false if unreadable.
    bool open(const std::string &pcmPath, int songRate, int nFft, int dimF);

    int chunks() const { return nChunks_; }
    int inputSize() const { return 4 * dimF_ * kDimT; }

    // Model input for chunk k: (4, dimF, dimT) = [L.re, L.im, R.re, R.im].
    void input(int k, float *out);
    // Model output for chunk k (same layout): the vocal spectrogram.
    void output(int k, const float *pred);

    // Writes the instrumental as 16-bit stereo PCM at the song's rate, same length as the song.
    bool writeBacking(const std::string &outPath, float compensate);

    // test hook: the 44.1 kHz mix and vocals (interleaved stereo float)
    const std::vector<float> &mix() const { return mix_; }
    const std::vector<float> &vocals() const { return voc_; }

private:
    int songRate_ = 0, nFft_ = 0, dimF_ = 0, chunk_ = 0, trim_ = 0, gen_ = 0, nChunks_ = 0;
    int64_t songFrames_ = 0, frames_ = 0;       // song length at song rate / at 44.1 kHz
    std::vector<float> mix_;                    // interleaved stereo @44.1k
    std::vector<float> voc_;                    // interleaved stereo @44.1k
    std::vector<float> window_;
};

}  // namespace riyaz
