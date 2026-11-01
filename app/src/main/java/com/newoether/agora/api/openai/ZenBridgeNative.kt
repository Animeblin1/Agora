package com.newoether.agora.api.openai

/**
 * JNI bridge into libagora_proot for the Zen gateway's memfd spawn (see zenbridge_jni.cpp).
 * Loads the same extraction lib the sandbox already ships, so no extra native artifact.
 */
internal object ZenBridgeNative {
    init {
        System.loadLibrary("agora_proot")
    }

    /**
     * Fork+execveat the [elf] bytes from a memfd with [args] (argv[0..n]) and [env] pairs
     * with the calling thread's stdio silenced. Returns the child pid, or -1 on failure.
     */
    external fun memfdSpawn(elf: ByteArray, args: Array<String>, env: Array<String>): Int
}
