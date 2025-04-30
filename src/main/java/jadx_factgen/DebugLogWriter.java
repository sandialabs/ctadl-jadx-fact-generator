package jadx_factgen;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import static jadx_factgen.Util.getFileWriter;

@SuppressWarnings("unused")
class DebugLogWriter {
    public final String baseName;
    public static final List<DebugLogWriter> debugLogWriters = new ArrayList<>();
    private PrintWriter writer = null;
    private boolean debugEnabled;

    public DebugLogWriter(String baseName, boolean debugEnabled) throws IOException {
        this.baseName = baseName;
        this.debugEnabled = debugEnabled;
        if (debugEnabled) {
            createWriter();
        }
    }

    public boolean isDebugEnabled() {
        return debugEnabled;
    }

    public void enableDebug() throws IOException {
        if (writer == null) {
            createWriter();
        }
        debugEnabled = true;
    }

    public void disableDebug() {
        debugEnabled = false;
    }

    public <T> void print(T out) {
        if (debugEnabled) {
            writer.print(out);
        }
    }

    public <T> void println(T out) {
        if (debugEnabled) {
            writer.println(out);
        }
    }

    public void println() {
        if (debugEnabled) {
            writer.println();
        }
    }

    public synchronized void flush() {
        if (debugEnabled) {
            writer.flush();
        }
    }

    private void createWriter() throws IOException {
        writer = getFileWriter(baseName);
        debugLogWriters.add(this);
    }
}
