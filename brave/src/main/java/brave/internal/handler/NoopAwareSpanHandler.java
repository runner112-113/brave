/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package brave.internal.handler;

import brave.handler.MutableSpan;
import brave.handler.SpanHandler;
import brave.internal.Platform;
import brave.propagation.TraceContext;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import static brave.internal.Throwables.propagateIfFatal;

/**
 * This logs exceptions instead of raising an error, as the supplied collector could have bugs.
 * 最外层的SpanHandler，处理异常，可以组合{@link CompositeSpanHandler}
 * */
public final class NoopAwareSpanHandler extends SpanHandler {
  // Array ensures no iterators are created at runtime
  public static SpanHandler create(SpanHandler[] handlers,
      AtomicBoolean noop) {
    if (handlers.length == 0) return SpanHandler.NOOP;
    if (handlers.length == 1) return new NoopAwareSpanHandler(handlers[0], noop);
    return new NoopAwareSpanHandler(new CompositeSpanHandler(handlers), noop);
  }

  final SpanHandler delegate;
  final AtomicBoolean noop;

  NoopAwareSpanHandler(SpanHandler delegate, AtomicBoolean noop) {
    this.delegate = delegate;
    this.noop = noop;
  }

  @Override public boolean begin(TraceContext context, MutableSpan span, TraceContext parent) {
    if (noop.get()) return false;
    try {
      return delegate.begin(context, span, parent);
    } catch (Throwable t) {
      propagateIfFatal(t);
      Platform.get().log("error handling begin {0}", context, t);
      return true; // user error in this handler shouldn't impact another
    }
  }

  @Override public boolean end(TraceContext context, MutableSpan span, Cause cause) {
    if (noop.get()) return false;
    try {
      return delegate.end(context, span, cause);
    } catch (Throwable t) {
      propagateIfFatal(t);
      Platform.get().log("error handling end {0}", context, t);
      return true; // user error in this handler shouldn't impact another
    }
  }

  @Override public boolean handlesAbandoned() {
    return delegate.handlesAbandoned();
  }

  @Override public int hashCode() {
    return delegate.hashCode();
  }

  @Override public boolean equals(Object obj) {
    return delegate.equals(obj);
  }

  @Override public String toString() {
    return delegate.toString();
  }

  static final class CompositeSpanHandler extends SpanHandler {
    final boolean handlesAbandoned;
    final SpanHandler[] handlers;

    CompositeSpanHandler(SpanHandler[] handlers) {
      this.handlers = handlers;
      boolean handlesAbandoned = false;
      for (SpanHandler handler : handlers) {
        if (handler.handlesAbandoned()) {
          handlesAbandoned = true;
          break;
        }
      }
      this.handlesAbandoned = handlesAbandoned;
    }

    @Override public boolean begin(TraceContext context, MutableSpan span, TraceContext parent) {
      for (SpanHandler handler : handlers) {
        if (!handler.begin(context, span, parent)) return false;
      }
      return true;
    }

    @Override public boolean end(TraceContext context, MutableSpan span, Cause cause) {
      for (SpanHandler handler : handlers) {
        if (cause != Cause.ABANDONED || handler.handlesAbandoned()) {
          if (!handler.end(context, span, cause)) return false;
        }
      }
      return true;
    }

    @Override public boolean handlesAbandoned() {
      return handlesAbandoned;
    }

    @Override public int hashCode() {
      return Arrays.hashCode(handlers);
    }

    @Override public boolean equals(Object obj) {
      if (!(obj instanceof CompositeSpanHandler)) return false;
      return Arrays.equals(((CompositeSpanHandler) obj).handlers, handlers);
    }

    @Override public String toString() {
      return Arrays.toString(handlers);
    }
  }
}
