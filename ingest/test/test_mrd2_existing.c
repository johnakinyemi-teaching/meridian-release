
#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include "packet2.h"

static int check(const char *name, int actual, int expected)
{
    printf("%s: expected=%d actual=%d %s\n",
           name, expected, actual,
           actual == expected ? "PASS" : "FAIL");

    return actual == expected;
}

int main(void)
{
    unsigned char frame[MRD2_PACKET_BYTES] = {0};
    unsigned char changed[MRD2_PACKET_BYTES];
    struct mrd2_packet packet;
    uint16_t crc;
    int passed = 0;

    /* Create an MRD2 frame */
    frame[0] = 'M';
    frame[1] = 'R';
    frame[2] = 'D';
    frame[3] = '2';

    memcpy(frame + MRD2_OFF_STNID, "GUELPH01", 8);

    /* Year 2025, day 100 */
    frame[MRD2_OFF_YEAR] = 0x07;
    frame[MRD2_OFF_YEAR + 1] = 0xE9;
    frame[MRD2_OFF_DOY] = 0;
    frame[MRD2_OFF_DOY + 1] = 100;

    frame[MRD2_OFF_VALUES] = 25;
    frame[MRD2_OFF_VERSION] = MRD2_FORMAT_VERSION;
    frame[MRD2_OFF_FLAGS] = 0;

    /* Record the OLD parser's 48-byte checksum behavior */
    crc = mrd_crc16(frame, MRD2_OFF_VERSION);
    frame[MRD2_OFF_CRC] = (unsigned char)(crc >> 8);
    frame[MRD2_OFF_CRC + 1] = (unsigned char)crc;

    printf("MRD2 existing-behaviour tests\n");
    printf("Fixture CRC: 0x%04X\n", crc);

    /* Test 1: Original frame */
    passed += check("Valid legacy fixture",
        mrd2_packet_parse(frame, sizeof(frame), &packet), 0);

    /* Test 2: Corrupt payload */
    memcpy(changed, frame, sizeof(frame));
    changed[MRD2_OFF_VALUES] ^= 0x01;

    passed += check("Payload corruption",
        mrd2_packet_parse(changed, sizeof(changed), &packet), -3);

    /* Test 3: Corrupt version */
    memcpy(changed, frame, sizeof(frame));
    changed[MRD2_OFF_VERSION] ^= 0x01;

    passed += check("Version corruption",
        mrd2_packet_parse(changed, sizeof(changed), &packet), 0);

    /* Test 4: Corrupt flags */
    memcpy(changed, frame, sizeof(frame));
    changed[MRD2_OFF_FLAGS] ^= MRD2_FLAG_SUSPECT;

    passed += check("Flags corruption",
        mrd2_packet_parse(changed, sizeof(changed), &packet), 0);

    printf("\nPassed: %d/4\n", passed);

    return passed == 4 ? 0 : 1;
}
