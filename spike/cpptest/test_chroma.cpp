// Host check for the play-along chroma: reads 16 kHz mono float32, writes one CSV line per result.
#include <cstdio>
#include <vector>
#include "chroma.h"

int main(int argc, char **argv) {
    if (argc < 3) { std::fprintf(stderr, "usage: test_chroma in.f32 out.csv\n"); return 1; }
    FILE *f = std::fopen(argv[1], "rb");
    if (!f) return 1;
    std::vector<float> x;
    float buf[4096];
    size_t n;
    while ((n = std::fread(buf, sizeof(float), 4096, f)) > 0) x.insert(x.end(), buf, buf + n);
    std::fclose(f);
    riyaz::ChromaAnalyser a;
    riyaz::ChromaAnalyser::Result r;
    FILE *o = std::fopen(argv[2], "w");
    long pos = 0;
    for (size_t i = 0; i + 256 <= x.size(); i += 256) {
        pos += 256;
        if (a.push(x.data() + i, 256, r)) {
            std::fprintf(o, "%.3f", pos / 16000.0);
            for (float c : r.chroma) std::fprintf(o, ",%.4f", c);
            std::fprintf(o, ",%.5f\n", r.rms);
        }
    }
    std::fclose(o);
    return 0;
}
