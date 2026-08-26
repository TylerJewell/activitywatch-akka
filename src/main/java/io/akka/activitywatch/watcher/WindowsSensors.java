package io.akka.activitywatch.watcher;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Psapi;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;
import io.akka.activitywatch.domain.InputRule;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The Windows calls the original's watchers make, made from here.
 *
 * <p>Idle time is `GetTickCount64()` minus `GetLastInputInfo().dwTime`, with the same
 * wraparound branch: the second is a 32-bit millisecond counter and the first is not, so once
 * every forty-nine days the subtraction is the wrong way round unless it is handled.
 *
 * <p>The foreground window is `GetForegroundWindow`, its title `GetWindowText`, and its
 * application the base name of the executable behind it. A handle of zero means there is no
 * foreground window at all — a lock screen, an elevation prompt — and the poll is skipped
 * rather than recorded as "unknown".
 */
public final class WindowsSensors {

  private WindowsSensors() {}

  public static boolean available() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }

  public static Sensors.Idle idle() {
    return () -> {
      WinUser.LASTINPUTINFO info = new WinUser.LASTINPUTINFO();
      info.cbSize = info.size();
      if (!User32.INSTANCE.GetLastInputInfo(info)) {
        throw new IllegalStateException("GetLastInputInfo failed");
      }
      long ticks = Kernel32.INSTANCE.GetTickCount64();
      long lower32 = ticks & 0xFFFFFFFFL;
      long lastInput = info.dwTime & 0xFFFFFFFFL;
      long differenceMillis = lower32 >= lastInput
          ? lower32 - lastInput
          : (0x100000000L - lastInput) + lower32;
      return differenceMillis / 1000d;
    };
  }

  public static Sensors.Window window() {
    return () -> {
      WinDef.HWND handle = User32.INSTANCE.GetForegroundWindow();
      if (handle == null || Pointer.nativeValue(handle.getPointer()) == 0) {
        return Map.of();
      }
      String title = title(handle);
      String app = application(handle);
      return Sensors.window(app, title);
    };
  }

  private static String title(WinDef.HWND handle) {
    int length = User32.INSTANCE.GetWindowTextLength(handle);
    if (length <= 0) {
      return "";
    }
    char[] buffer = new char[length + 1];
    User32.INSTANCE.GetWindowText(handle, buffer, buffer.length);
    return Native.toString(buffer);
  }

  private static String application(WinDef.HWND handle) {
    IntByReference pid = new IntByReference();
    User32.INSTANCE.GetWindowThreadProcessId(handle, pid);
    WinNT.HANDLE process = Kernel32.INSTANCE.OpenProcess(
        WinNT.PROCESS_QUERY_INFORMATION | WinNT.PROCESS_VM_READ, false, pid.getValue());
    if (process == null) {
      // An elevated process refuses to be opened. The original falls back to a WMI query for
      // the same fact; there is no answer to be had here without one, so the name is unknown.
      return "unknown";
    }
    try {
      char[] buffer = new char[1024];
      int written = Psapi.INSTANCE.GetModuleFileNameExW(process, null, buffer, buffer.length);
      if (written == 0) {
        return "unknown";
      }
      String path = Native.toString(buffer);
      return Paths.get(path).getFileName().toString();
    } finally {
      Kernel32.INSTANCE.CloseHandle(process);
    }
  }

  /**
   * How much input arrived, counted the only way this platform offers without a hook.
   *
   * <p>`GetLastInputInfo` says *when* input last arrived, not what it was, so the counters the
   * original's `pynput` listeners keep — key presses, clicks, pixels moved, lines scrolled —
   * cannot be filled in from it. What can be said is whether any input arrived in the interval
   * just past, so a poll in which the last-input tick moved is reported as one key press and
   * one in which it did not is reported as nothing. SPEC-001 §4 OD-8 records the decision and
   * the README lists it as a difference: the counts are not the original's counts.
   */
  public static Sensors.Input input() {
    Sensors.Idle idle = idle();
    AtomicLong lastSeen = new AtomicLong(Long.MIN_VALUE);
    return () -> {
      long now = System.currentTimeMillis();
      long lastInput = now - (long) (idle.secondsSinceLastInput() * 1000);
      long previous = lastSeen.getAndSet(lastInput);
      boolean moved = previous != Long.MIN_VALUE && lastInput > previous;
      return moved ? new InputRule.Counts(1, 0, 0, 0, 0, 0) : InputRule.Counts.none();
    };
  }
}
