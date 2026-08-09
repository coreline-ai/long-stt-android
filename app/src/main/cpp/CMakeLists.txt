cmake_minimum_required(VERSION 3.10)

project(whisper.cpp)

set(CMAKE_CXX_STANDARD 17)

# 저장소 기준 상대 경로가 기본값이다. 필요하면 -DWHISPER_CPP_DIR=/path 로만 명시적으로 덮어쓴다.
get_filename_component(REPOSITORY_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../.." ABSOLUTE)
set(WHISPER_LOCK_FILE "${REPOSITORY_ROOT}/third_party/whisper.cpp.lock")
set(WHISPER_CPP_DIR "${REPOSITORY_ROOT}/third_party/whisper.cpp" CACHE PATH "Pinned whisper.cpp source directory")
get_filename_component(WHISPER_LIB_DIR "${WHISPER_CPP_DIR}" ABSOLUTE)

if(NOT EXISTS "${WHISPER_LOCK_FILE}")
    message(FATAL_ERROR "Missing whisper.cpp lock: ${WHISPER_LOCK_FILE}")
endif()
if(NOT EXISTS "${WHISPER_LIB_DIR}/CMakeLists.txt" OR
   NOT EXISTS "${WHISPER_LIB_DIR}/src/whisper.cpp" OR
   NOT EXISTS "${WHISPER_LIB_DIR}/ggml/CMakeLists.txt")
    message(FATAL_ERROR
        "Pinned whisper.cpp source is missing at ${WHISPER_LIB_DIR}. "
        "Run ./scripts/setup_whisper.sh from the repository root.")
endif()

file(READ "${WHISPER_LOCK_FILE}" WHISPER_LOCK_CONTENT)
string(REGEX MATCH "WHISPER_CPP_COMMIT=([0-9a-f]+)" WHISPER_COMMIT_MATCH "${WHISPER_LOCK_CONTENT}")
set(WHISPER_SOURCE_COMMIT "${CMAKE_MATCH_1}")
string(LENGTH "${WHISPER_SOURCE_COMMIT}" WHISPER_COMMIT_LENGTH)
if(NOT WHISPER_COMMIT_LENGTH EQUAL 40 OR NOT WHISPER_SOURCE_COMMIT MATCHES "^[0-9a-f]+$")
    message(FATAL_ERROR "Invalid WHISPER_CPP_COMMIT in ${WHISPER_LOCK_FILE}")
endif()

# whisper.cpp 버전 추출
file(READ "${WHISPER_LIB_DIR}/CMakeLists.txt" MAIN_CMAKE_CONTENT)
string(REGEX MATCH "project\\(\"whisper\\.cpp\" VERSION ([0-9]+\\.[0-9]+\\.[0-9]+)\\)" VERSION_MATCH "${MAIN_CMAKE_CONTENT}")
if(CMAKE_MATCH_1)
    set(WHISPER_VERSION ${CMAKE_MATCH_1})
else()
    set(WHISPER_VERSION "unknown")
endif()

message(STATUS "Whisper source: ${WHISPER_LIB_DIR}")
message(STATUS "Whisper commit: ${WHISPER_SOURCE_COMMIT}")
message(STATUS "Whisper version: ${WHISPER_VERSION}")

set(
    SOURCE_FILES
    ${WHISPER_LIB_DIR}/src/whisper.cpp
    ${CMAKE_SOURCE_DIR}/jni.c
)

find_library(LOG_LIB log)

include(FetchContent)

# 단일 whisper 타겟 (ABI별 분기 없이 단순화)
add_library(
    whisper
    SHARED
    ${SOURCE_FILES}
)

target_compile_definitions(whisper PUBLIC GGML_USE_CPU)
target_compile_definitions(
    whisper
    PRIVATE
    WHISPER_VERSION="${WHISPER_VERSION}"
    WHISPER_SOURCE_COMMIT="${WHISPER_SOURCE_COMMIT}"
)

# Release 최적화
target_compile_options(whisper PRIVATE -O3)
target_compile_options(whisper PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
target_compile_options(whisper PRIVATE -ffunction-sections -fdata-sections)
target_link_options(whisper PRIVATE -Wl,--gc-sections)
target_link_options(whisper PRIVATE -Wl,--exclude-libs,ALL)
target_link_options(whisper PRIVATE -flto)

# Android 16 (API 36) 16KB 페이지 크기 호환성
# 모든 .so의 LOAD 세그먼트를 16KB 경계로 정렬
target_link_options(whisper PRIVATE "-Wl,-z,max-page-size=16384")
target_link_options(whisper PRIVATE "-Wl,-z,common-page-size=16384")

# ggml 백엔드 (whisper.cpp 번들 ggml 사용)
FetchContent_Declare(ggml SOURCE_DIR ${WHISPER_LIB_DIR}/ggml)
FetchContent_MakeAvailable(ggml)

# ggml 타겟에도 16KB 정렬 플래그 전파
foreach(tgt ggml ggml-base ggml-cpu)
    if(TARGET ${tgt})
        target_link_options(${tgt} PRIVATE "-Wl,-z,max-page-size=16384")
        target_link_options(${tgt} PRIVATE "-Wl,-z,common-page-size=16384")
    endif()
endforeach()

target_link_libraries(whisper ${LOG_LIB} android ggml)

include_directories(${WHISPER_LIB_DIR})
include_directories(${WHISPER_LIB_DIR}/include)
include_directories(${WHISPER_LIB_DIR}/ggml/include)
include_directories(${WHISPER_LIB_DIR}/ggml/src)
include_directories(${WHISPER_LIB_DIR}/ggml/src/ggml-cpu)
