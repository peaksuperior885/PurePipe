# PurePipe Audio

PurePipe Audio is a Java audio streaming and decoding API designed for applications that need to extract and play audio streams without having to implement the entire streaming and decoding pipeline themselves.

It currently supports extracting YouTube audio streams and decoding M4A/AAC audio through its built-in audio pipeline.

## Features

- YouTube audio stream extraction through NewPipe Extractor
- AAC/M4A stream playback
- JAAD-based AAC decoding
- Java Sound PCM output support
- Built-in audio streaming and M4A defragmentation
- Caffeine-backed caching support
- Relocated internal dependencies to avoid dependency conflicts
- Tinylog-based logging

## Requirements

- Java 17 or newer
- Tinylog 2.7.x
- Kotlin standard library 1.9.22, or a compatible Kotlin runtime supplied by your platform

PurePipe Audio intentionally does **not** bundle Kotlin or Tinylog into its shaded JAR. Applications are expected to provide these dependencies themselves.

## Installation

PurePipe Audio is available from the Peak Maven repository.

### Gradle

Add the Peak Maven repository:

```gradle
repositories {
    mavenCentral()

    maven {
        url = 'https://peaksuperior885.github.io/peaks_maven/files/'
    }
}
```

Then add PurePipe Audio:

```gradle
dependencies {
    implementation 'dev.peak885.purepipe:purepipe-audio:1.0.0'

    implementation 'org.jetbrains.kotlin:kotlin-stdlib:1.9.22'

    implementation 'org.tinylog:tinylog-api:2.7.0'
    implementation 'org.tinylog:tinylog-impl:2.7.0'
}
```

If your application already provides Kotlin through another platform integration, such as Kotlin for Forge (KFF) or Fabric Language Kotlin, you can use that instead of adding another Kotlin runtime.

### Why are the other dependencies not required?

PurePipe Audio bundles and relocates its critical runtime dependencies, including:

- NewPipe Extractor
- OkHttp
- Okio
- JAADec
- Caffeine
- NanoJSON
- Jsoup
- Rhino
- JSpecify

This means applications do not need to manually provide those libraries.

Kotlin and Tinylog are deliberately left to the application because they are runtime/platform dependencies that may already be provided by the host environment.

## Basic Usage

The following example extracts an audio stream from a YouTube URL and plays it through the Java Sound output.

```java
import dev.peak885.purepipe.api.audio.JavaSoundPcmSink;
import dev.peak885.purepipe.utils.MusicPlayer;
import dev.peak885.purepipe.utils.YoutubeExtractor;

public class Example {

    public static void main(String[] args) {
        YoutubeExtractor extractor = new YoutubeExtractor();
        MusicPlayer player = new MusicPlayer();

        String url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";

        String streamUrl = extractor.getAudioStreamUrl(url);

        if (streamUrl == null || streamUrl.isEmpty()) {
            System.err.println("Could not find a playable audio stream.");
            return;
        }

        player.playUrl(
                streamUrl,
                new JavaSoundPcmSink()
        );
    }
}
```

`YoutubeExtractor` handles finding a suitable audio stream, while `MusicPlayer` handles streaming, decoding, and playback.

The `JavaSoundPcmSink` sends the decoded PCM audio to the system's Java Sound output.

## Stopping Playback

Playback can be stopped through `MusicPlayer`:

```java
player.stop();
```

For example:

```java
MusicPlayer player = new MusicPlayer();

player.playUrl(
        streamUrl,
        new JavaSoundPcmSink()
);

// ...

player.stop();
```

## Custom PCM Output

PurePipe Audio separates decoding from PCM output through its PCM sink API.

This means applications can provide their own PCM output implementation instead of using `JavaSoundPcmSink`.

For example, a custom application could send decoded PCM data to:

- OpenAL
- WASAPI
- DirectSound
- Java Sound
- A game/mod audio engine
- Another audio processing pipeline

The decoder does not need to know where the resulting PCM data is ultimately played.

## Logging

PurePipe Audio uses Tinylog for logging.

Applications should provide both the Tinylog API and an implementation:

```gradle
dependencies {
    implementation 'org.tinylog:tinylog-api:2.7.0'
    implementation 'org.tinylog:tinylog-impl:2.7.0'
}
```

Without a Tinylog implementation, Tinylog will report:

```text
LOGGER WARN: No logging framework implementation found in classpath.
```

PurePipe Audio itself does not bundle the Tinylog implementation so that applications can control their own logging setup.

## Dependency Relocation

PurePipe Audio shades and relocates its internal dependencies under:

```text
dev.peak885.purepipe.internal.*
```

This prevents PurePipe Audio's internal libraries from conflicting with versions used by the application.

For example, the bundled NewPipe Extractor is relocated away from:

```text
org.schabi.newpipe
```

and into PurePipe Audio's internal namespace.

Applications should therefore use PurePipe Audio's public API rather than directly accessing its relocated dependencies.

## Example Project

A minimal Gradle application using PurePipe Audio can look like this:

```gradle
plugins {
    id 'java'
    id 'application'
}

group = 'example'
version = '1.0.0'

repositories {
    mavenCentral()

    maven {
        url = 'https://peaksuperior885.github.io/peaks_maven/files/'
    }
}

dependencies {
    implementation 'dev.peak885.purepipe:purepipe-audio:1.0.0'

    implementation 'org.jetbrains.kotlin:kotlin-stdlib:1.9.22'

    implementation 'org.tinylog:tinylog-api:2.7.0'
    implementation 'org.tinylog:tinylog-impl:2.7.0'
}

application {
    mainClass = 'example.Main'
}
```

## License

This project is licensed under the terms of the PCGL-1.0 license. 
See the [LICENSE.txt](LICENSE.txt) file for details.