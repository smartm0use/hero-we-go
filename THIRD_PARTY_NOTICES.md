# Third-party components

The MIT license in this repository applies to the original Hero We Go source
code. Dependencies retain their own licenses; it does not relicense libVLC or
other bundled components.

## Runtime dependencies

| Component | Version | License | Source |
| --- | --- | --- | --- |
| libVLC Android | 3.6.5 | LGPL-2.1, as declared by the published artifact | [VideoLAN libvlcjni](https://code.videolan.org/videolan/libvlcjni) |
| jsoup | 1.18.3 | MIT | [jsoup](https://github.com/jhy/jsoup/tree/jsoup-1.18.3) |
| AndroidX libraries, including libVLC's transitive dependencies | Resolved by Gradle | Apache-2.0 | [AndroidX](https://android.googlesource.com/platform/frameworks/support/) |
| desugar_jdk_libs | 2.1.4 | Component-specific OpenJDK/Android licenses | [desugar_jdk_libs](https://github.com/google/desugar_jdk_libs) |

libVLC's prebuilt native libraries are obtained from Maven Central and are not
modified by this project. VideoLAN provides its source and build instructions
in the repositories linked above. Its bundled codecs and libraries can carry
additional notices. Review the applicable distribution obligations before
publishing a binary release; building an APK does not make all its contents MIT.

- [LGPL version 2.1](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html)
- [jsoup license](https://github.com/jhy/jsoup/blob/jsoup-1.18.3/LICENSE)
- [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)

## Build and test tooling

- Gradle wrapper / Gradle: Apache-2.0, [source](https://github.com/gradle/gradle).
- Android Gradle plugin: Apache-2.0, [source](https://android.googlesource.com/platform/tools/base/).
- JUnit 4.13.2: EPL-1.0, [source and license](https://github.com/junit-team/junit4).
- OkHttp MockWebServer 4.12.0: Apache-2.0, [source](https://github.com/square/okhttp/tree/parent-4.12.0).

Test dependencies are not packaged as application runtime dependencies.
