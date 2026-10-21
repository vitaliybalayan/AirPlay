#include "alac_wrapper.h"

#include <new>
#include <stdint.h>

#include "ALACBitUtilities.h"
#include "ALACDecoder.h"

struct alac_wrapper_s {
    ALACDecoder decoder;
    int frame_length;
    int channels;
    int bytes_per_sample;
};

static void put_be32(uint8_t *out, uint32_t value)
{
    out[0] = (uint8_t) (value >> 24);
    out[1] = (uint8_t) (value >> 16);
    out[2] = (uint8_t) (value >> 8);
    out[3] = (uint8_t) value;
}

alac_wrapper_t *
alac_wrapper_create(int frame_length, int sample_rate, int channels, int bit_depth)
{
    alac_wrapper_t *alac = new (std::nothrow) alac_wrapper_t();
    if (!alac) {
        return NULL;
    }

    /* ALACSpecificConfig, big-endian. RAOP senders use the classic AirTunes
     * tuning values (pb=40, mb=10, kb=14, maxRun=255). */
    uint8_t cookie[24] = {0};
    put_be32(cookie, (uint32_t) frame_length);
    cookie[4] = 0;                   /* compatibleVersion */
    cookie[5] = (uint8_t) bit_depth;
    cookie[6] = 40;                  /* pb */
    cookie[7] = 10;                  /* mb */
    cookie[8] = 14;                  /* kb */
    cookie[9] = (uint8_t) channels;
    cookie[10] = 0;                  /* maxRun = 255 */
    cookie[11] = 255;
    put_be32(cookie + 12, 0);        /* maxFrameBytes */
    put_be32(cookie + 16, 0);        /* avgBitRate */
    put_be32(cookie + 20, (uint32_t) sample_rate);

    if (alac->decoder.Init(cookie, sizeof(cookie)) != 0) {
        delete alac;
        return NULL;
    }
    alac->frame_length = frame_length;
    alac->channels = channels;
    alac->bytes_per_sample = bit_depth / 8;
    return alac;
}

int
alac_wrapper_decode(alac_wrapper_t *alac, unsigned char *input, int input_len,
                    unsigned char *output, int output_size)
{
    BitBuffer bits;
    uint32_t out_samples = 0;
    int frame_bytes;

    if (!alac || !input || input_len <= 0 || !output) {
        return -1;
    }
    frame_bytes = alac->frame_length * alac->channels * alac->bytes_per_sample;
    if (output_size < frame_bytes) {
        return -1;
    }
    BitBufferInit(&bits, input, (uint32_t) input_len);
    if (alac->decoder.Decode(&bits, output, (uint32_t) alac->frame_length,
                             (uint32_t) alac->channels, &out_samples) != 0) {
        return -1;
    }
    return (int) out_samples * alac->channels * alac->bytes_per_sample;
}

void
alac_wrapper_destroy(alac_wrapper_t *alac)
{
    delete alac;
}
