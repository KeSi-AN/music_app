// Host test for separation.cpp: dumps model inputs for chunks 0 and 1, then reconstructs vocals
// from model outputs computed by Python (pred_k.f32), and writes the result for comparison.
#include <cstdio>
#include <vector>
#include "separation.h"
int main(int argc, char **argv) {
    riyaz::MdxJob job;
    if (!job.open(argv[1], atoi(argv[2]), 6144, 2048)) { printf("open failed\n"); return 1; }
    printf("chunks %d inputSize %d\n", job.chunks(), job.inputSize());
    std::vector<float> buf(job.inputSize());
    for (int k = 0; k < 2; ++k) {
        job.input(k, buf.data());
        char name[64]; snprintf(name, sizeof name, "in_%d.f32", k);
        FILE *f = fopen(name, "wb"); fwrite(buf.data(), 4, buf.size(), f); fclose(f);
    }
    if (argc > 3) {
        for (int k = 0; k < job.chunks(); ++k) {
            char name[64]; snprintf(name, sizeof name, "pred_%d.f32", k);
            FILE *f = fopen(name, "rb"); if (!f) { printf("missing %s\n", name); return 1; }
            fread(buf.data(), 4, buf.size(), f); fclose(f);
            job.output(k, buf.data());
        }
        FILE *f = fopen("vocals.f32", "wb"); fwrite(job.vocals().data(), 4, job.vocals().size(), f); fclose(f);
        job.writeBacking("backing.pcm", 1.035f);
    }
    printf("ok\n");
}
