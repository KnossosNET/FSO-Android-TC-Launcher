cmake_minimum_required(VERSION 3.22.1)
project(archive7z LANGUAGES C CXX)

# 7-Zip 26.06, extraction only. Keep LZMA2DecMt/MtDec/Threads enabled.
set(SEVENZIP_SOURCES
    C/7zCrc.c C/7zCrcOpt.c C/7zStream.c C/Alloc.c C/CpuArch.c
    C/Bcj2.c C/Bra.c C/Bra86.c C/BraIA64.c C/Delta.c
    C/LzmaDec.c C/Lzma2Dec.c C/Lzma2DecMt.c C/MtDec.c C/Threads.c
    C/Ppmd7.c C/Ppmd7Dec.c C/SwapBytes.c
    CPP/Common/CRC.cpp CPP/Common/IntToString.cpp CPP/Common/MyString.cpp
    CPP/Common/MyVector.cpp CPP/Common/NewHandler.cpp CPP/Common/StringConvert.cpp
    CPP/Common/StringToInt.cpp CPP/Common/Wildcard.cpp
    CPP/Common/MyWindows.cpp CPP/Common/UTFConvert.cpp
    CPP/Windows/PropVariant.cpp CPP/Windows/Synchronization.cpp CPP/Windows/System.cpp
    CPP/7zip/Common/CreateCoder.cpp CPP/7zip/Common/CWrappers.cpp
    CPP/7zip/Common/InBuffer.cpp CPP/7zip/Common/FilterCoder.cpp
    CPP/7zip/Common/LimitedStreams.cpp CPP/7zip/Common/MethodId.cpp
    CPP/7zip/Common/MethodProps.cpp CPP/7zip/Common/OutBuffer.cpp
    CPP/7zip/Common/ProgressUtils.cpp CPP/7zip/Common/PropId.cpp
    CPP/7zip/Common/StreamBinder.cpp CPP/7zip/Common/StreamObjects.cpp
    CPP/7zip/Common/StreamUtils.cpp CPP/7zip/Common/VirtThread.cpp
    CPP/7zip/Archive/Common/CoderMixer2.cpp CPP/7zip/Archive/Common/HandlerOut.cpp
    CPP/7zip/Archive/Common/ItemNameUtils.cpp CPP/7zip/Archive/Common/OutStreamWithCRC.cpp
    CPP/7zip/Archive/Common/ParseProperties.cpp
    CPP/7zip/Archive/7z/7zCompressionMode.cpp CPP/7zip/Archive/7z/7zDecode.cpp
    CPP/7zip/Archive/7z/7zExtract.cpp CPP/7zip/Archive/7z/7zHandler.cpp
    CPP/7zip/Archive/7z/7zHeader.cpp CPP/7zip/Archive/7z/7zIn.cpp
    CPP/7zip/Archive/7z/7zProperties.cpp
    CPP/7zip/Compress/Bcj2Coder.cpp CPP/7zip/Compress/Bcj2Register.cpp
    CPP/7zip/Compress/BcjCoder.cpp CPP/7zip/Compress/BcjRegister.cpp
    CPP/7zip/Compress/BitlDecoder.cpp CPP/7zip/Compress/BranchMisc.cpp
    CPP/7zip/Compress/BranchRegister.cpp CPP/7zip/Compress/ByteSwap.cpp
    CPP/7zip/Compress/BZip2Crc.cpp CPP/7zip/Compress/BZip2Decoder.cpp
    CPP/7zip/Compress/BZip2Register.cpp CPP/7zip/Compress/CopyCoder.cpp
    CPP/7zip/Compress/CopyRegister.cpp CPP/7zip/Compress/DeflateDecoder.cpp
    CPP/7zip/Compress/DeflateRegister.cpp CPP/7zip/Compress/DeltaFilter.cpp
    CPP/7zip/Compress/Lzma2Decoder.cpp CPP/7zip/Compress/Lzma2Register.cpp
    CPP/7zip/Compress/LzmaDecoder.cpp CPP/7zip/Compress/LzmaRegister.cpp
    CPP/7zip/Compress/LzOutWindow.cpp CPP/7zip/Compress/PpmdDecoder.cpp
    CPP/7zip/Compress/PpmdRegister.cpp
)
list(TRANSFORM SEVENZIP_SOURCES PREPEND "${CMAKE_CURRENT_SOURCE_DIR}/../third_party/7zip/")
add_library(archive7z SHARED archive_jni.cpp ${SEVENZIP_SOURCES})
target_include_directories(archive7z PRIVATE ../third_party/7zip)
target_compile_definitions(archive7z PRIVATE Z7_EXTRACT_ONLY Z7_NO_CRYPTO Z7_NO_ASM)
target_compile_features(archive7z PRIVATE cxx_std_17)
target_compile_definitions(archive7z PRIVATE _FILE_OFFSET_BITS=64 _REENTRANT)
if(ANDROID)
    # Decoder throughput matters in debug APKs too, which are often used for testing large mods.
    target_compile_options(archive7z PRIVATE "$<$<CONFIG:Debug>:-O2>")
    target_link_options(archive7z PRIVATE "-Wl,-z,max-page-size=16384")
else()
    find_package(JNI REQUIRED)
    target_include_directories(archive7z PRIVATE ${JNI_INCLUDE_DIRS})
    find_package(Threads REQUIRED)
    target_link_libraries(archive7z PRIVATE Threads::Threads)
endif()
