#ifndef ALAC_WRAPPER_H
#define ALAC_WRAPPER_H

#ifdef __cplusplus
extern "C" {
#endif

/* C interface to Apple's ALAC decoder, used for RAOP audio streams with ct=2. */
typedef struct alac_wrapper_s alac_wrapper_t;

alac_wrapper_t *alac_wrapper_create(int frame_length, int sample_rate, int channels, int bit_depth);

/* Decodes one ALAC frame into interleaved PCM. Returns bytes written, or -1 on error. */
int alac_wrapper_decode(alac_wrapper_t *alac, unsigned char *input, int input_len,
                        unsigned char *output, int output_size);

void alac_wrapper_destroy(alac_wrapper_t *alac);

#ifdef __cplusplus
}
#endif

#endif
