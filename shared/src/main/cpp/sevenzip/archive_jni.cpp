// JNI boundary for the official 7-Zip extraction-only handler.
#include "CPP/Common/MyInitGuid.h"
#include "CPP/7zip/Archive/7z/7zHandler.h"
#include "CPP/Windows/PropVariant.h"
#include <jni.h>
#include <algorithm>
#include <filesystem>
#include <fstream>
#include <mutex>
#include <string>

namespace {
class Env {
    JavaVM *vm;
    bool attached = false;
public:
    JNIEnv *jni = nullptr;
    explicit Env(JavaVM *v): vm(v) {
        if (vm->GetEnv(reinterpret_cast<void **>(&jni), JNI_VERSION_1_6) == JNI_EDETACHED) {
#ifdef __ANDROID__
            attached = vm->AttachCurrentThread(&jni, nullptr) == JNI_OK;
#else
            attached = vm->AttachCurrentThread(reinterpret_cast<void **>(&jni), nullptr) == JNI_OK;
#endif
        }
    }
    ~Env() { if (attached) vm->DetachCurrentThread(); }
};

struct Callbacks {
    JavaVM *vm = nullptr;
    jobject sink = nullptr;
    jthrowable error = nullptr;
    jmethodID open = nullptr, complete = nullptr, check = nullptr, write = nullptr;
    std::recursive_mutex mutex;

    Callbacks(JNIEnv *env, jobject value) {
        env->GetJavaVM(&vm);
        sink = env->NewGlobalRef(value);
        if (!sink) return;
        jclass type = env->GetObjectClass(value);
        if (!type) return;
        open = env->GetMethodID(type, "open", "(Ljava/lang/String;JZZ)Ljava/io/OutputStream;");
        if (open) complete = env->GetMethodID(type, "complete", "()V");
        if (complete) check = env->GetMethodID(type, "checkCancelled", "()V");
        env->DeleteLocalRef(type);
        if (!check) return;
        type = env->FindClass("java/io/OutputStream");
        if (!type) return;
        write = env->GetMethodID(type, "write", "([BII)V");
        env->DeleteLocalRef(type);
    }
    ~Callbacks() {
        Env env(vm);
        if (sink) env.jni->DeleteGlobalRef(sink);
        if (error) env.jni->DeleteGlobalRef(error);
    }
    HRESULT capture(JNIEnv *env) {
        if (env->ExceptionCheck()) {
            jthrowable local = env->ExceptionOccurred();
            env->ExceptionClear();
            if (!error) error = static_cast<jthrowable>(env->NewGlobalRef(local));
            env->DeleteLocalRef(local);
            return E_ABORT;
        }
        return error ? E_ABORT : S_OK;
    }
    HRESULT cancelled() {
        std::lock_guard<std::recursive_mutex> lock(mutex);
        if (error) return E_ABORT;
        Env env(vm);
        if (!env.jni) return E_FAIL;
        env.jni->CallVoidMethod(sink, check);
        return capture(env.jni);
    }
};

Z7_CLASS_IMP_IInStream(FileInput)
public:
    std::ifstream file;
    Callbacks *callbacks;
    FileInput(const std::filesystem::path &path, Callbacks *c): file(path, std::ios::binary), callbacks(c) {}
};
Z7_COM7F_IMF(FileInput::Read(void *data, UInt32 size, UInt32 *processed)) {
    if (processed) *processed = 0;
    RINOK(callbacks->cancelled())
    file.read(static_cast<char *>(data), size);
    if (processed) *processed = static_cast<UInt32>(file.gcount());
    return file.bad() ? E_FAIL : S_OK;
}
Z7_COM7F_IMF(FileInput::Seek(Int64 offset, UInt32 origin, UInt64 *position)) {
    if (origin > 2) return E_INVALIDARG;
    file.clear();
    const std::ios::seekdir dirs[] = {std::ios::beg, std::ios::cur, std::ios::end};
    file.seekg(offset, dirs[origin]);
    if (!file) return E_FAIL;
    if (position) *position = static_cast<UInt64>(file.tellg());
    return S_OK;
}

Z7_CLASS_IMP_COM_1(JavaOutput, ISequentialOutStream)
    Callbacks *callbacks;
    jobject output;
    jbyteArray buffer;
public:
    JavaOutput(Callbacks *c, JNIEnv *env, jobject stream): callbacks(c) {
        output = env->NewGlobalRef(stream);
        jbyteArray local = output ? env->NewByteArray(131072) : nullptr;
        buffer = local ? static_cast<jbyteArray>(env->NewGlobalRef(local)) : nullptr;
        if (local) env->DeleteLocalRef(local);
    }
    ~JavaOutput() {
        Env env(callbacks->vm);
        if (output) env.jni->DeleteGlobalRef(output);
        if (buffer) env.jni->DeleteGlobalRef(buffer);
    }
};
Z7_COM7F_IMF(JavaOutput::Write(const void *data, UInt32 size, UInt32 *processed)) {
    if (processed) *processed = 0;
    std::lock_guard<std::recursive_mutex> lock(callbacks->mutex);
    RINOK(callbacks->cancelled())
    Env env(callbacks->vm);
    if (!env.jni || !buffer || !output) return E_OUTOFMEMORY;
    const auto *bytes = static_cast<const jbyte *>(data);
    while (size) {
        const UInt32 count = std::min(size, UInt32(131072));
        env.jni->SetByteArrayRegion(buffer, 0, count, bytes);
        RINOK(callbacks->capture(env.jni))
        env.jni->CallVoidMethod(output, callbacks->write, buffer, 0, static_cast<jint>(count));
        RINOK(callbacks->capture(env.jni))
        bytes += count; size -= count;
        if (processed) *processed += count;
    }
    return S_OK;
}

jstring javaName(JNIEnv *env, const wchar_t *name, unsigned length) {
    std::u16string text;
    for (unsigned i = 0; i < length; ++i) {
        const auto ch = static_cast<UInt32>(name[i]);
        if (ch > 0xffff) {
            text += static_cast<char16_t>(0xd800 + ((ch - 0x10000) >> 10));
            text += static_cast<char16_t>(0xdc00 + ((ch - 0x10000) & 0x3ff));
        } else text += static_cast<char16_t>(ch);
    }
    return env->NewString(reinterpret_cast<const jchar *>(text.data()), static_cast<jsize>(text.size()));
}

Z7_CLASS_IMP_COM_2(ExtractCallback, IArchiveExtractCallback, IArchiveExtractCallbackMessage2)
    IInArchive *archive;
    Callbacks *callbacks;
public:
    Z7_IFACE_COM7_IMP(IProgress)
    ExtractCallback(IInArchive *a, Callbacks *c): archive(a), callbacks(c) {}
};
Z7_COM7F_IMF(ExtractCallback::SetTotal(UInt64)) { return callbacks->cancelled(); }
Z7_COM7F_IMF(ExtractCallback::SetCompleted(const UInt64 *)) { return callbacks->cancelled(); }
Z7_COM7F_IMF(ExtractCallback::PrepareOperation(Int32 mode)) {
    return mode == NArchive::NExtract::NAskMode::kExtract ? callbacks->cancelled() : E_FAIL;
}
Z7_COM7F_IMF(ExtractCallback::GetStream(UInt32 index, ISequentialOutStream **result, Int32 mode)) {
    *result = nullptr;
    if (mode != NArchive::NExtract::NAskMode::kExtract) return E_FAIL;
    std::lock_guard<std::recursive_mutex> lock(callbacks->mutex);
    RINOK(callbacks->cancelled())
    NWindows::NCOM::CPropVariant name, size, directory, attributes, anti;
    RINOK(archive->GetProperty(index, kpidPath, &name))
    RINOK(archive->GetProperty(index, kpidSize, &size))
    RINOK(archive->GetProperty(index, kpidIsDir, &directory))
    RINOK(archive->GetProperty(index, kpidAttrib, &attributes))
    RINOK(archive->GetProperty(index, kpidIsAnti, &anti))
    if (anti.vt == VT_BOOL && anti.boolVal != VARIANT_FALSE) return E_FAIL;
    if (name.vt != VT_BSTR || directory.vt != VT_BOOL) return E_FAIL;
    const bool isDir = directory.boolVal != VARIANT_FALSE;
    if (!isDir && size.vt != VT_UI8) return E_FAIL;
    const UInt32 attr = attributes.vt == VT_UI4 ? attributes.ulVal : 0;
    const bool link = (attr & 0x400) != 0 || ((attr >> 16) & 0170000) == 0120000;
    Env env(callbacks->vm);
    if (!env.jni) return E_FAIL;
    jstring path = javaName(env.jni, name.bstrVal, SysStringLen(name.bstrVal));
    RINOK(callbacks->capture(env.jni))
    jobject output = env.jni->CallObjectMethod(callbacks->sink, callbacks->open, path,
        static_cast<jlong>(isDir ? 0 : size.uhVal.QuadPart), static_cast<jboolean>(isDir), static_cast<jboolean>(link));
    env.jni->DeleteLocalRef(path);
    RINOK(callbacks->capture(env.jni))
    if (output) {
        CMyComPtr<ISequentialOutStream> stream = new JavaOutput(callbacks, env.jni, output);
        env.jni->DeleteLocalRef(output);
        RINOK(callbacks->capture(env.jni))
        *result = stream.Detach();
    } else if (!isDir) return E_FAIL;
    return S_OK;
}
Z7_COM7F_IMF(ExtractCallback::SetOperationResult(Int32 result)) {
    if (result != NArchive::NExtract::NOperationResult::kOK) return E_FAIL;
    std::lock_guard<std::recursive_mutex> lock(callbacks->mutex);
    RINOK(callbacks->cancelled())
    Env env(callbacks->vm);
    if (!env.jni) return E_FAIL;
    env.jni->CallVoidMethod(callbacks->sink, callbacks->complete);
    return callbacks->capture(env.jni);
}
Z7_COM7F_IMF(ExtractCallback::ReportExtractResult(UInt32, UInt32, Int32 result)) {
    return result == NArchive::NExtract::NOperationResult::kOK ? S_OK : E_FAIL;
}

HRESULT extract(JNIEnv *env, jstring path, Callbacks &callbacks, jint threads, jlong memory) {
    const jsize length = env->GetStringLength(path);
    std::u16string filename(length, u'\0');
    env->GetStringRegion(path, 0, length, reinterpret_cast<jchar *>(filename.data()));
    if (env->ExceptionCheck()) return E_FAIL;
    CMyComPtr<IInStream> input = new FileInput(std::filesystem::path(filename), &callbacks);
    auto *handler = new NArchive::N7z::CHandler();
    CMyComPtr<IInArchive> archive = handler;
    const wchar_t *names[] = {L"mt", L"memuse", L"mtf"};
    NWindows::NCOM::CPropVariant props[3];
    props[0] = static_cast<UInt32>(threads);
    props[1] = static_cast<UInt64>(memory);
    // Keep filter output on the extracting thread; LZMA2 still uses its worker pool.
    props[2] = false;
    RINOK(static_cast<ISetProperties *>(handler)->SetProperties(names, props, 3))
    const UInt64 maxSignatureOffset = 0;
    RINOK(archive->Open(input, &maxSignatureOffset, nullptr))
    for (PROPID id : {kpidErrorFlags, kpidWarningFlags}) {
        NWindows::NCOM::CPropVariant flags;
        RINOK(archive->GetArchiveProperty(id, &flags))
        if (flags.vt == VT_UI4 && flags.ulVal) return E_FAIL;
    }
    auto *callback = new ExtractCallback(handler, &callbacks);
    CMyComPtr<IArchiveExtractCallback> keepAlive = callback;
    HRESULT result = archive->Extract(nullptr, static_cast<UInt32>(-1), false, callback);
    archive->Close();
    return result;
}
}

extern "C" JNIEXPORT void JNICALL
Java_com_fsolauncher_core_NativeSevenZExtractor_extractNative(
    JNIEnv *env, jclass, jstring path, jobject sink, jint threads, jlong memory) {
    Callbacks callbacks(env, sink);
    if (env->ExceptionCheck()) return;
    HRESULT result = E_FAIL;
    try {
        result = extract(env, path, callbacks, threads, memory);
    } catch (const std::bad_alloc &) { result = E_OUTOFMEMORY; }
    catch (...) { result = E_FAIL; }
    if (callbacks.error) env->Throw(callbacks.error);
    else if (result != S_OK && !env->ExceptionCheck()) {
        jclass type = env->FindClass("java/io/IOException");
        if (type) {
            env->ThrowNew(type, result == E_OUTOFMEMORY ? "Not enough memory to extract 7z archive"
                : "7z extraction failed (Checksum/CRC error, corrupt archive or unsupported compression)");
            env->DeleteLocalRef(type);
        }
    }
}
