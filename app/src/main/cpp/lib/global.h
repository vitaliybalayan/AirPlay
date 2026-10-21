#ifndef GLOBAL_H
#define GLOBAL_H

/* Mirroring + audio only. Bits 0 (Video) and 4 (HLS) are deliberately off:
 * with them set, current iOS requires video FairPlay (/fp-setup2), which no
 * open receiver implements, and gets stuck instead of falling back. */
#define GLOBAL_FEATURES 0x5A7FFFC0
#define GLOBAL_FEATURES_TXT "0x5A7FFFC0,0x0"
/* GET /info historically advertised more than mDNS: 0x1E5A7FFFF7 (adds Video/Photo/FairPlay/HLS
 * bits). Apps like TikTok rely on it to send their video; mDNS keeps the plain value above so
 * Safari's player button does not get stuck on /fp-setup2. */
#define GLOBAL_INFO_FEATURES 0x5A7FFFF7ULL
/* Upper word reported only in GET /info */
#define GLOBAL_FEATURES_EXT 0x1EULL
#define GLOBAL_MODEL    "AppleTV2,1"
#define GLOBAL_VERSION  "130.14"
#define GLOBAL_SOURCE_VERSION "220.68"

#define MAX_HWADDR_LEN 6

#endif
