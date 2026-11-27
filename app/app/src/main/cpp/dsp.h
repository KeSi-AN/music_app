#pragma once

#include <vector>

namespace riyaz::dsp {

// Band-limited resampling (Kaiser-windowed sinc, 32 taps, any ratio). Output length = len * out/in.
std::vector<float> resample(const std::vector<float> &in, int srIn, int srOut);

}  // namespace riyaz::dsp
