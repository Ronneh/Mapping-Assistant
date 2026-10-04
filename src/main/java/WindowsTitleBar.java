import com.sun.jna.Library;
import com.sun.jna.CallbackReference;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;
import java.awt.Window;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/** Applies native Windows dark-mode styling to an already displayable Swing window. */
final class WindowsTitleBar {
    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1 = 19;
    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
    private static final int DWMWA_BORDER_COLOR = 34;
    private static final int DWMWA_CAPTION_COLOR = 35;
    private static final int DWMWA_TEXT_COLOR = 36;
    private static final int DWMWA_COLOR_NONE = 0xFFFFFFFE;
    private static final int SWP_NOSIZE = 0x0001;
    private static final int SWP_NOMOVE = 0x0002;
    private static final int SWP_NOZORDER = 0x0004;
    private static final int SWP_NOACTIVATE = 0x0010;
    private static final int SWP_FRAMECHANGED = 0x0020;
    private static final int GWLP_WNDPROC = -4;
    private static final int WM_NCLBUTTONDOWN = 0x00A1;
    private static final int WM_SYSCOMMAND = 0x0112;
    private static final int HTLEFT = 10;
    private static final int HTRIGHT = 11;
    private static final int HTTOP = 12;
    private static final int HTTOPLEFT = 13;
    private static final int HTTOPRIGHT = 14;
    private static final int HTBOTTOM = 15;
    private static final int HTBOTTOMLEFT = 16;
    private static final int HTBOTTOMRIGHT = 17;
    private static final long SC_SIZE = 0xF000L;
    private static final Map<Long, WindowHook> WINDOW_HOOKS = new ConcurrentHashMap<>();

    private WindowsTitleBar() { }

    static void enableDark(Window window) {
        if (!isWindows(System.getProperty("os.name", "")) || window == null) return;
        try {
            if (!window.isDisplayable()) window.addNotify();
            Pointer handle = Native.getComponentPointer(window);
            try (Memory enabled = new Memory(Integer.BYTES)) {
                enabled.setInt(0, 1);
                int result = DwmApi.INSTANCE.DwmSetWindowAttribute(
                        handle, DWMWA_USE_IMMERSIVE_DARK_MODE, enabled, Integer.BYTES);
                if (result != 0) {
                    DwmApi.INSTANCE.DwmSetWindowAttribute(
                            handle, DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1,
                            enabled, Integer.BYTES);
                }
                setColor(handle, DWMWA_CAPTION_COLOR, AssistantTheme.BACKGROUND);
                setColor(handle, DWMWA_TEXT_COLOR, AssistantTheme.TEXT);
                User32.INSTANCE.SetWindowPos(handle, null, 0, 0, 0, 0,
                        SWP_NOSIZE | SWP_NOMOVE | SWP_NOZORDER
                                | SWP_NOACTIVATE | SWP_FRAMECHANGED);
                if (!suppressBorder(handle)) {
                    setColor(handle, DWMWA_BORDER_COLOR, AssistantTheme.BACKGROUND);
                }
                DwmApi.INSTANCE.DwmFlush();
            }
        } catch (Throwable ignored) {
            // Unsupported Windows builds keep their normal system title bar.
        }
    }

    static boolean isWindows(String osName) {
        return osName.toLowerCase(Locale.ROOT).startsWith("windows");
    }

    static void preventManualResize(Window window) {
        if (!isWindows(System.getProperty("os.name", "")) || window == null) return;
        try {
            if (!window.isDisplayable()) window.addNotify();
            Pointer handle = Native.getComponentPointer(window);
            long handleValue = Pointer.nativeValue(handle);
            if (handleValue == 0 || WINDOW_HOOKS.containsKey(handleValue)) return;

            WindowHook hook = new WindowHook();
            Pointer previous = User32.INSTANCE.SetWindowLongPtrW(
                    handle, GWLP_WNDPROC, CallbackReference.getFunctionPointer(hook.callback));
            if (previous == null || Pointer.nativeValue(previous) == 0) return;
            hook.previous = previous;
            WINDOW_HOOKS.put(handleValue, hook);
        } catch (Throwable ignored) {
            // The normal title bar remains available if native subclassing is unsupported.
        }
    }

    private static boolean isResizeHitTest(long hitTest) {
        return hitTest == HTLEFT || hitTest == HTRIGHT || hitTest == HTTOP
                || hitTest == HTTOPLEFT || hitTest == HTTOPRIGHT || hitTest == HTBOTTOM
                || hitTest == HTBOTTOMLEFT || hitTest == HTBOTTOMRIGHT;
    }

    private static final class WindowHook {
        private Pointer previous;
        private final WindowProc callback;

        private WindowHook() {
            callback = (windowHandle, message, wParam, lParam) -> {
                long value = wParam == null ? 0 : Pointer.nativeValue(wParam);
                if ((message == WM_NCLBUTTONDOWN && isResizeHitTest(value))
                        || (message == WM_SYSCOMMAND
                        && (value & 0xFFF0L) == SC_SIZE)) {
                    return null;
                }
                return User32.INSTANCE.CallWindowProcW(
                        previous, windowHandle, message, wParam, lParam);
            };
        }
    }

    private interface DwmApi extends Library {
        DwmApi INSTANCE = Native.load("dwmapi", DwmApi.class);

        int DwmSetWindowAttribute(Pointer windowHandle, int attribute,
                Pointer attributeValue, int attributeSize);
        int DwmFlush();
    }

    private static void setColor(Pointer windowHandle, int attribute, java.awt.Color color) {
        int colorRef = color.getRed() | color.getGreen() << 8 | color.getBlue() << 16;
        try (Memory value = new Memory(Integer.BYTES)) {
            value.setInt(0, colorRef);
            DwmApi.INSTANCE.DwmSetWindowAttribute(
                    windowHandle, attribute, value, Integer.BYTES);
        }
    }

    private static boolean suppressBorder(Pointer windowHandle) {
        try (Memory value = new Memory(Integer.BYTES)) {
            value.setInt(0, DWMWA_COLOR_NONE);
            return DwmApi.INSTANCE.DwmSetWindowAttribute(
                    windowHandle, DWMWA_BORDER_COLOR, value, Integer.BYTES) == 0;
        }
    }

    private interface User32 extends StdCallLibrary {
        User32 INSTANCE = Native.load("user32", User32.class);

        boolean SetWindowPos(Pointer windowHandle, Pointer insertAfter,
                int x, int y, int width, int height, int flags);
        Pointer SetWindowLongPtrW(Pointer windowHandle, int index, Pointer value);
        Pointer CallWindowProcW(Pointer previousProc, Pointer windowHandle,
                int message, Pointer wParam, Pointer lParam);
    }

    private interface WindowProc extends StdCallLibrary.StdCallCallback {
        Pointer callback(Pointer windowHandle, int message, Pointer wParam, Pointer lParam);
    }
}
