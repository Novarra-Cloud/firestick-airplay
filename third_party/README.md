# Third-party code

| Directory | Project | Upstream commit | License |
|-----------|---------|-----------------|---------|
| `uxplay/lib` | [UxPlay](https://github.com/FDH2/UxPlay) (AirPlay mirroring server library) | `d4b7373cb6107b2d8d6c5838991c19d39e2b96ae` | GPL-3.0 (see `uxplay/LICENSE`); llhttp is MIT, playfair has its own notice |
| `libplist` | [libplist](https://github.com/libimobiledevice/libplist) (Apple property lists) | `678b1a34693b86ae1787fa470dcb7203ffd0ef3f` | LGPL-2.1 (see `libplist/COPYING`) |

Only the C library sources are vendored. The upstream CMake/autotools files are
replaced by `app/src/main/cpp/CMakeLists.txt`.

Local modifications are marked with `FIRESTICK-AIRPLAY` comments.
