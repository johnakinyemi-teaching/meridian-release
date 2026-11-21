
#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include "packet2.h"

//Test 1: Check if a normal MRD2 frame is accepted
int testValidFrame(unsigned char frame[])
{
    struct mrd2_packet packet;
    int result = mrd2_packet_parse(frame, MRD2_PACKET_BYTES, &packet);

    if (result == 0) {
        return 1;
    }

    return 0;
}

//Test 2: Check if changing the payload is detected */
int testPayload(unsigned char frame[])
{
    unsigned char changed[MRD2_PACKET_BYTES];
    struct mrd2_packet packet;

    memcpy(changed, frame, MRD2_PACKET_BYTES);
    changed[MRD2_OFF_VALUES] = changed[MRD2_OFF_VALUES] + 1;

    int before = mrd2_packet_parse(frame, MRD2_PACKET_BYTES, &packet);
    int after = mrd2_packet_parse(changed, MRD2_PACKET_BYTES, &packet);

    if (before == 0 && after == -3) {
        return 1;
    }
    return 0;
}

//Test 3: Check if changing the version is detected */
int testVersion(unsigned char frame[])
{
    unsigned char changed[MRD2_PACKET_BYTES];
    struct mrd2_packet packet;

    memcpy(changed, frame, MRD2_PACKET_BYTES);
    changed[MRD2_OFF_VERSION] = changed[MRD2_OFF_VERSION] +1;

    int before = mrd2_packet_parse(frame, MRD2_PACKET_BYTES, &packet);
    int after = mrd2_packet_parse(changed, MRD2_PACKET_BYTES, &packet);

    if (before == 0 && after == -3) {
        return 1;
    }

    return 0;
}

//Test 4: Check if changing the flags is detected */
int testFlags(unsigned char frame[])
{
    unsigned char changed[MRD2_PACKET_BYTES];
    struct mrd2_packet packet;
    memcpy(changed, frame, MRD2_PACKET_BYTES);
    changed[MRD2_OFF_FLAGS] = changed[MRD2_OFF_FLAGS] + 1;

    int before = mrd2_packet_parse(frame, MRD2_PACKET_BYTES, &packet);
    int after = mrd2_packet_parse(changed, MRD2_PACKET_BYTES, &packet);

    if (before == 0 && after == -3) {
        return 1;
    }

    return 0;
}



int check(char name[], int result)
{
    if (result == 1) {
        printf("%s: PASS\n", name);
    }else {
        printf("%s: FAIL\n", name);
    }


    return result;
}




int main(void)
{
    unsigned char frame[MRD2_PACKET_BYTES] = {0};
    uint16_t crc;
    int passed = 0;

    frame[0] = 'M';
    frame[1] = 'R';
    frame[2] = 'D';
    frame[3] = '2';

    memcpy(frame + MRD2_OFF_STNID, "GUELPH01", 8);
    frame[MRD2_OFF_YEAR] = 0x07;
    frame[MRD2_OFF_YEAR + 1] = 0xE9;
    frame[MRD2_OFF_DOY] = 0;
    frame[MRD2_OFF_DOY + 1] = 100;
    frame[MRD2_OFF_VALUES] = 25;
    frame[MRD2_OFF_VERSION] = MRD2_FORMAT_VERSION;
    frame[MRD2_OFF_FLAGS] = 0;

    crc = mrd_crc16(frame, MRD2_CRC_LEN);

    frame[MRD2_OFF_CRC] = (unsigned char)(crc >> 8);
    frame[MRD2_OFF_CRC + 1] = (unsigned char)crc;

    printf("MRD-242 Bug Detection Tests\n\n");

    passed += check("Test Using Valid frame", testValidFrame(frame));
    passed += check("Test Using Payload corruption", testPayload(frame));
    passed += check("Test Using Version corruption", testVersion(frame));
    passed += check("Test Using Flags corruption", testFlags(frame));

    printf("\nPassed: %d/4\n", passed);

    if (passed == 4) {
        return 0;
    }
    return 1;
}
