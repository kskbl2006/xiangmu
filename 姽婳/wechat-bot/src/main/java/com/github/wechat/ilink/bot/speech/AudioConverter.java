package com.github.wechat.ilink.bot.speech;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Converts generated audio to a widely playable MP3 attachment using the local FFmpeg binary. */
public final class AudioConverter {
  private final Path workDirectory;

  public AudioConverter(Path workDirectory) {
    this.workDirectory = workDirectory;
  }

  public byte[] toMp3(QwenTtsClient.Audio audio) throws IOException {
    if ("mp3".equalsIgnoreCase(audio.format())) {
      return audio.bytes();
    }
    Files.createDirectories(workDirectory);
    Path input = Files.createTempFile(workDirectory, "tts-input-", "." + audio.format());
    Path output = Files.createTempFile(workDirectory, "tts-output-", ".mp3");
    try {
      Files.write(input, audio.bytes());
      Process process =
          new ProcessBuilder(
                  "ffmpeg",
                  "-hide_banner",
                  "-loglevel",
                  "error",
                  "-y",
                  "-i",
                  input.toString(),
                  "-codec:a",
                  "libmp3lame",
                  "-b:a",
                  "64k",
                  output.toString())
              .redirectErrorStream(true)
              .start();
      boolean finished;
      try {
        finished = process.waitFor(30, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted while converting TTS audio", e);
      }
      if (!finished) {
        process.destroyForcibly();
        throw new IOException("FFmpeg audio conversion timed out");
      }
      if (process.exitValue() != 0) {
        throw new IOException("FFmpeg audio conversion failed: " + new String(process.getInputStream().readAllBytes()));
      }
      return Files.readAllBytes(output);
    } finally {
      Files.deleteIfExists(input);
      Files.deleteIfExists(output);
    }
  }
}
