package edu.ustb.coursev2;

import com.google.ortools.Loader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * A JDK-private old MSVC runtime can crash inside native code before Java can catch the failure.
 */
final class SolverRuntime {
  private static boolean ready;

  static synchronized void load() {
    if (ready) return;
    if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
      Path runtime = Path.of(System.getProperty("java.home"), "bin", "msvcp140.dll");
      if (Files.isRegularFile(runtime)) {
        var v = com.sun.jna.platform.win32.VersionUtil.getFileVersionInfo(runtime.toString());
        if (v.getFileVersionMajor() < 14
            || (v.getFileVersionMajor() == 14 && v.getFileVersionMinor() < 40))
          throw new Api.Failure(
              503,
              "SOLVER_RUNTIME_UNAVAILABLE",
              "当前 Windows JDK 附带的 MSVC 运行库早于 14.40。请使用带新版运行库的 Java 17 或课程 Linux 容器。");
      }
    }
    try {
      Loader.loadNativeLibraries();
    } catch (LinkageError e) {
      throw new Api.Failure(503, "SOLVER_RUNTIME_UNAVAILABLE", "未能加载当前平台的 OR-Tools 原生库");
    }
    ready = true;
  }

  private SolverRuntime() {}
}
