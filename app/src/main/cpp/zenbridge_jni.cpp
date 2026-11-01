#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/syscall.h>
#include <cstdlib>
#include <string>
#include <vector>

/**
 * Spawn an in-memory ELF for the embedded Zen gateway so release-updated binaries can
 * run without filesystem exec (Android 10+ W^X blocks execve on app data directories).
 * The ELF bytes go into a memfd; the child execveat()s it with AT_EMPTY_PATH.
 * aarch64 syscall numbers are used directly so the file compiles on any NDK level.
 */

#ifndef AT_EMPTY_PATH
#define AT_EMPTY_PATH 0x1000
#endif
#ifndef __NR_memfd_create
#define __NR_memfd_create 279
#endif
#ifndef __NR_execveat
#define __NR_execveat 281
#endif

extern "C" JNIEXPORT jint JNICALL
Java_com_newoether_agora_api_openai_ZenBridgeNative_memfdSpawn(
    JNIEnv* env, jclass, jbyteArray elf, jobjectArray args, jobjectArray envPairs) {
    const jsize len = env->GetArrayLength(elf);
    if (len <= 0) return -1;
    std::vector<char> buf(static_cast<size_t>(len));
    env->GetByteArrayRegion(elf, 0, len, reinterpret_cast<jbyte*>(buf.data()));

    const int fd = static_cast<int>(syscall(__NR_memfd_create, "agora_zen_gateway", 0U));
    if (fd < 0) return -1;
    size_t written = 0;
    while (written < buf.size()) {
        const ssize_t w = write(fd, buf.data() + written, buf.size() - written);
        if (w <= 0) { close(fd); return -1; }
        written += static_cast<size_t>(w);
    }

    // Copy every Jstring into stable std::string storage before building pointer arrays.
    auto collect = [env](jobjectArray arr) {
        std::vector<std::string> out;
        if (arr == nullptr) return out;
        const jsize n = env->GetArrayLength(arr);
        out.reserve(static_cast<size_t>(n));
        for (jsize i = 0; i < n; ++i) {
            auto js = static_cast<jstring>(env->GetObjectArrayElement(arr, i));
            const char* s = env->GetStringUTFChars(js, nullptr);
            out.emplace_back(s ? s : "");
            env->ReleaseStringUTFChars(js, s);
        }
        return out;
    };
    std::vector<std::string> argStrings = collect(args);
    std::vector<std::string> envStrings = collect(envPairs);

    std::vector<char*> argv;
    argv.reserve(argStrings.size() + 1);
    for (auto& s : argStrings) argv.push_back(const_cast<char*>(s.c_str()));
    argv.push_back(nullptr);

    std::vector<char*> envp;
    envp.reserve(envStrings.size() + 2);
    envp.push_back(const_cast<char*>("PATH=/system/bin"));
    for (auto& s : envStrings) envp.push_back(const_cast<char*>(s.c_str()));
    envp.push_back(nullptr);

    const pid_t pid = fork();
    if (pid == 0) {
        const int devnull = open("/dev/null", O_RDWR);
        if (devnull >= 0) {
            dup2(devnull, 0); dup2(devnull, 1); dup2(devnull, 2);
            if (devnull > 2) close(devnull);
        }
        char path[64];
        snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
        syscall(__NR_execveat, fd, "", argv.data(), envp.data(), AT_EMPTY_PATH);
        _exit(127);
    }
    close(fd);
    return static_cast<jint>(pid);
}
