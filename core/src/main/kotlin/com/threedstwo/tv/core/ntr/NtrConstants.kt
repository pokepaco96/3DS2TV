package com.threedstwo.tv.core.ntr

/**
 * Constants of the NTR / NTR-HR remote play protocol.
 *
 * Verified against source code (not README):
 *  - xzn/ntr-hr        include/constants.h (NS_MENU_LISTEN_PORT, RP_DST_PORT_DEFAULT, RP_CONFIG_FLAG_*),
 *                      source/ns.c (NS_PACKET layout, NS_CMD_REMOTEPLAY = 901, guarding magic)
 *  - xzn/ntrviewer-hr  const.h (RP_PACKET_SIZE = 1448, RP_DATA_HDR_SIZE = 4), ntr_hb.c, ntr_rp.c, ntr_audio.h
 */
object NtrConstants {
    /** TCP port NTR listens on for menu/debug commands (NS_MENU_LISTEN_PORT). */
    const val CONTROL_PORT = 8000

    /** Default UDP port of the viewer (RP_DST_PORT_DEFAULT). */
    const val DEFAULT_VIEWER_PORT = 8001

    const val TCP_MAGIC = 0x12345678
    const val TCP_ARGS_COUNT = 16

    /** magic + seq + type + cmd + 16 args + dataLen, all u32 LE. */
    const val TCP_HEADER_SIZE = 4 * 4 + TCP_ARGS_COUNT * 4 + 4

    const val CMD_HEARTBEAT = 0
    const val CMD_REMOTE_PLAY = 901

    /** args[3] value that makes NTR-HR read args[4..6] (port/flags, audio, full width). */
    const val GUARDING_MAGIC = 1404036572

    const val FLAG_RELIABLE_STREAM = 1 shl 30
    const val FLAG_RELIABLE_STREAM_DELTA = 1 shl 31
    const val FLAG_LOSSLESS = 1 shl 29

    /** Max UDP datagram used by NTR (RP_PACKET_SIZE). */
    const val PACKET_SIZE = 1448
    const val DATA_HEADER_SIZE = 4
    const val PACKET_DATA_SIZE = PACKET_SIZE - DATA_HEADER_SIZE

    /** Upper bound of packets per frame used by the reference viewer (RP_MAX_PACKET_COUNT). */
    const val MAX_PACKETS_PER_FRAME = 480

    /** hdr[1] bit marking the last packet of a frame. */
    const val HDR1_END_FLAG = 0x10

    /** hdr[2] video type value (after masking downsample bits 0x0C and lossless bit 0x01). */
    const val HDR2_VIDEO_TYPE = 2
    const val HDR2_DOWNSAMPLE_MASK = 0x0C
    const val HDR2_LOSSLESS_BIT = 0x01

    /** hdr[2] value of the NTR-HR plain-UDP audio packet (ntr_audio.h / thread_audio.rs). */
    const val AUDIO_TYPE = 4
    const val AUDIO_FMT_PCM16 = 0
    const val AUDIO_SAMPLE_RATE = 32728
    const val AUDIO_CHANNELS = 2
    const val AUDIO_FRAME_SAMPLES = 160
    const val AUDIO_FRAME_BYTES = AUDIO_FRAME_SAMPLES * AUDIO_CHANNELS * 2

    const val JPEG_QUALITY_MIN = 10
    const val JPEG_QUALITY_MAX = 100

    /** QoS in Mbps as exposed by NTRViewer-HR (NTR_QOS_MIN..MAX); sent as bytes/s = Mbps * 128 * 1024. */
    const val QOS_MBPS_MIN = 4
    const val QOS_MBPS_MAX = 20

    /** Native 3DS screen sizes in landscape orientation (what the player sees). */
    const val TOP_WIDTH = 400
    const val BOTTOM_WIDTH = 320
    const val SCREEN_HEIGHT = 240
}
