package com.singleman.okio;

import java.io.IOException;
import java.io.InterruptedIOException;


public class AsyncTimeout extends Timeout {
    private static final int TIMEOUT_WRITE_SIZE = 65536;
    private static AsyncTimeout head;
    private boolean inQueue;
    private AsyncTimeout next;
    private long timeoutAt;

    public final void enter() {
        if (!this.inQueue) {
            long timeoutNanos = timeoutNanos();
            boolean hasDeadline = hasDeadline();
            if (timeoutNanos != 0 || hasDeadline) {
                this.inQueue = true;
                scheduleTimeout(this, timeoutNanos, hasDeadline);
                return;
            }
            return;
        }
        throw new IllegalStateException("Unbalanced enter/exit");
    }

    private static synchronized void scheduleTimeout(AsyncTimeout node, long timeoutNanos, boolean hasDeadline) {
        synchronized (AsyncTimeout.class) {
            if (head == null) {
                head = new AsyncTimeout();
                new Watchdog().start();
            }
            long now = System.nanoTime();
            if (timeoutNanos != 0 && hasDeadline) {
                node.timeoutAt = Math.min(timeoutNanos, node.deadlineNanoTime() - now) + now;
            } else if (timeoutNanos != 0) {
                node.timeoutAt = now + timeoutNanos;
            } else if (hasDeadline) {
                node.timeoutAt = node.deadlineNanoTime();
            } else {
                throw new AssertionError();
            }
            long remainingNanos = node.remainingNanos(now);
            AsyncTimeout prev = head;
            while (true) {
                if (prev.next == null) {
                    break;
                } else if (remainingNanos < prev.next.remainingNanos(now)) {
                    break;
                } else {
                    prev = prev.next;
                }
            }
            node.next = prev.next;
            prev.next = node;
            if (prev == head) {
                AsyncTimeout.class.notify();
            }
        }
    }

    public final boolean exit() {
        if (!this.inQueue) {
            return false;
        }
        this.inQueue = false;
        return cancelScheduledTimeout(this);
    }

    private static synchronized boolean cancelScheduledTimeout(AsyncTimeout node) {
        synchronized (AsyncTimeout.class) {
            for (AsyncTimeout prev = head; prev != null; prev = prev.next) {
                if (prev.next == node) {
                    prev.next = node.next;
                    node.next = null;
                    return false;
                }
            }
            return true;
        }
    }

    private long remainingNanos(long now) {
        return this.timeoutAt - now;
    }

    /* access modifiers changed from: protected */
    public void timedOut() {
    }

    public final Sink sink(final Sink sink) {
        return new Sink() {
            public void write(Buffer source, long byteCount) throws IOException {
                Util.checkOffsetAndCount(source.size, 0, byteCount);
                while (byteCount > 0) {
                    long toWrite = 0;
                    Segment s = source.head;
                    while (true) {
                        if (toWrite >= 65536) {
                            break;
                        }
                        toWrite += (long) (source.head.limit - source.head.pos);
                        if (toWrite >= byteCount) {
                            toWrite = byteCount;
                            break;
                        }
                        s = s.next;
                    }
                    AsyncTimeout.this.enter();
                    try {
                        sink.write(source, toWrite);
                        byteCount -= toWrite;
                        AsyncTimeout.this.exit(true);
                    } catch (IOException e) {
                        throw AsyncTimeout.this.exit(e);
                    } catch (Throwable th) {
                        AsyncTimeout.this.exit(false);
                        throw th;
                    }
                }
            }

            public void flush() throws IOException {
                AsyncTimeout.this.enter();
                try {
                    sink.flush();
                    AsyncTimeout.this.exit(true);
                } catch (IOException e) {
                    throw AsyncTimeout.this.exit(e);
                } catch (Throwable th) {
                    AsyncTimeout.this.exit(false);
                    throw th;
                }
            }

            public void close() throws IOException {
                AsyncTimeout.this.enter();
                try {
                    sink.close();
                    AsyncTimeout.this.exit(true);
                } catch (IOException e) {
                    throw AsyncTimeout.this.exit(e);
                } catch (Throwable th) {
                    AsyncTimeout.this.exit(false);
                    throw th;
                }
            }

            public Timeout timeout() {
                return AsyncTimeout.this;
            }

            public String toString() {
                return "AsyncTimeout.sink(" + sink + ")";
            }
        };
    }

    public final Source source(final Source source) {
        return new Source() {
            public long read(Buffer sink, long byteCount) throws IOException {
                AsyncTimeout.this.enter();
                try {
                    long result = source.read(sink, byteCount);
                    AsyncTimeout.this.exit(true);
                    return result;
                } catch (IOException e) {
                    throw AsyncTimeout.this.exit(e);
                } catch (Throwable th) {
                    AsyncTimeout.this.exit(false);
                    throw th;
                }
            }

            public void close() throws IOException {
                try {
                    source.close();
                    AsyncTimeout.this.exit(true);
                } catch (IOException e) {
                    throw AsyncTimeout.this.exit(e);
                } catch (Throwable th) {
                    AsyncTimeout.this.exit(false);
                    throw th;
                }
            }

            public Timeout timeout() {
                return AsyncTimeout.this;
            }

            public String toString() {
                return "AsyncTimeout.source(" + source + ")";
            }
        };
    }

    /* access modifiers changed from: package-private */
    public final void exit(boolean throwOnTimeout) throws IOException {
        if (exit() && throwOnTimeout) {
            throw newTimeoutException((IOException) null);
        }
    }

    /* access modifiers changed from: package-private */
    public final IOException exit(IOException cause) throws IOException {
        if (!exit()) {
            return cause;
        }
        return newTimeoutException(cause);
    }

    /* access modifiers changed from: protected */
    public IOException newTimeoutException(IOException cause) {
        InterruptedIOException e = new InterruptedIOException("timeout");
        if (cause != null) {
            e.initCause(cause);
        }
        return e;
    }

    private static final class Watchdog extends Thread {
        public Watchdog() {
            super("Okio Watchdog");
            setDaemon(true);
        }

        public void run() {
            while (true) {
                try {
                    AsyncTimeout timedOut = AsyncTimeout.awaitTimeout();
                    if (timedOut != null) {
                        timedOut.timedOut();
                    }
                } catch (InterruptedException e) {
                }
            }
        }
    }

    static synchronized AsyncTimeout awaitTimeout() throws InterruptedException {
        synchronized (AsyncTimeout.class) {
            AsyncTimeout node = head.next;
            if (node == null) {
                AsyncTimeout.class.wait();
                return null;
            }
            long waitNanos = node.remainingNanos(System.nanoTime());
            if (waitNanos > 0) {
                long waitMillis = waitNanos / 1000000;
                AsyncTimeout.class.wait(waitMillis, (int) (waitNanos - (1000000 * waitMillis)));
                return null;
            }
            head.next = node.next;
            node.next = null;
            return node;
        }
    }
}
