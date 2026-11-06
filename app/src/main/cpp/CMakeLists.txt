cmake_minimum_required(VERSION 3.18)
project(firestick_airplay C)

set(CMAKE_C_STANDARD 11)

# Root of the vendored third-party sources.
get_filename_component(TP ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party ABSOLUTE)
set(UX ${TP}/uxplay/lib)
set(PL ${TP}/libplist)

# ---------------------------------------------------------------- OpenSSL (libcrypto)
# For Android builds OpenSSL is cross-compiled by scripts/build-openssl-android.sh into
# build-deps/openssl/<abi>/. For a host (desktop) test build the system OpenSSL is used.
if(ANDROID)
  if(NOT OPENSSL_ANDROID_DIR)
    get_filename_component(OPENSSL_ANDROID_DIR ${TP}/../build-deps/openssl ABSOLUTE)
  endif()
  set(OPENSSL_PREFIX ${OPENSSL_ANDROID_DIR}/${ANDROID_ABI})
  if(NOT EXISTS ${OPENSSL_PREFIX}/lib/libcrypto.a)
    message(FATAL_ERROR "libcrypto.a not found in ${OPENSSL_PREFIX}/lib. "
                        "Run scripts/build-openssl-android.sh first.")
  endif()
  add_library(crypto STATIC IMPORTED)
  set_target_properties(crypto PROPERTIES
      IMPORTED_LOCATION ${OPENSSL_PREFIX}/lib/libcrypto.a
      INTERFACE_INCLUDE_DIRECTORIES ${OPENSSL_PREFIX}/include)
else()
  find_package(OpenSSL 1.1.1 REQUIRED)
  add_library(crypto INTERFACE)
  target_link_libraries(crypto INTERFACE OpenSSL::Crypto)
endif()

# ---------------------------------------------------------------- libplist
file(GLOB PLIST_SRC ${PL}/src/*.c ${PL}/libcnary/node.c ${PL}/libcnary/node_list.c)
add_library(plist STATIC ${PLIST_SRC})
target_include_directories(plist
    PUBLIC  ${PL}/include
    PRIVATE ${PL} ${PL}/src ${PL}/libcnary/include)
target_compile_definitions(plist PRIVATE
    PACKAGE_VERSION="2.7.0"
    HAVE_STRNDUP HAVE_MEMMEM HAVE_STRPTIME
    HAVE_TM_TM_GMTOFF HAVE_TM_TM_ZONE HAVE_LOCALTIME_R HAVE_GMTIME_R
    __LITTLE_ENDIAN__=1)
target_compile_options(plist PRIVATE -w -fPIC)

# ---------------------------------------------------------------- UxPlay AirPlay library
file(GLOB UX_SRC
    ${UX}/*.c
    ${UX}/playfair/*.c
    ${UX}/llhttp/*.c
    ${UX}/mdnsd/*.c)
add_library(airplay STATIC ${UX_SRC})
target_include_directories(airplay PUBLIC ${UX} PRIVATE ${UX}/playfair ${UX}/llhttp ${UX}/mdnsd)
target_compile_definitions(airplay PRIVATE
    STANDALONE TARGET_POSIX _LINUX PIC _REENTRANT
    __STDC_CONSTANT_MACROS __STDC_LIMIT_MACROS
    NOHOLD PLIST_210 PLIST_230
    OPENSSL_API_COMPAT=0x10101000L)
target_compile_options(airplay PRIVATE -O2 -fPIC -Wno-unused -Wno-deprecated-declarations)
target_link_libraries(airplay PUBLIC plist crypto)
if(NOT ANDROID)
  find_package(Threads REQUIRED)
  target_link_libraries(airplay PUBLIC Threads::Threads m)
endif()

# ---------------------------------------------------------------- JNI bridge
add_library(airplayjni SHARED airplay_jni.c)
target_compile_options(airplayjni PRIVATE -Wall -Wextra -Wno-unused-parameter)
if(ANDROID)
  find_library(log-lib log)
  target_link_libraries(airplayjni PRIVATE airplay ${log-lib} m)
  # Fire OS 8+ devices use 16 KB-page friendly alignment going forward.
  target_link_options(airplayjni PRIVATE "-Wl,-z,max-page-size=16384")
else()
  find_package(JNI REQUIRED)
  target_include_directories(airplayjni PRIVATE ${JNI_INCLUDE_DIRS})
  target_link_libraries(airplayjni PRIVATE airplay)
endif()
