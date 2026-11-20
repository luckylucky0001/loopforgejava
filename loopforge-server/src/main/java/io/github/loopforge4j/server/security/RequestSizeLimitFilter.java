package io.github.loopforge4j.server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects request bodies above a limit before anything parses them (413). Bodies without a declared
 * length (chunked) are cut off while being read.
 */
class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;

    RequestSizeLimitFilter(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) {
            response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter()
                    .write("{\"type\":\"about:blank\",\"title\":\"Content Too Large\",\"status\":413,"
                            + "\"detail\":\"request body exceeds " + maxBytes + " bytes\"}");
            return;
        }
        chain.doFilter(request.getContentLengthLong() < 0 ? new Limited(request, maxBytes) : request, response);
    }

    private static final class Limited extends HttpServletRequestWrapper {
        private final long maxBytes;
        private ServletInputStream stream;

        Limited(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new Counting(super.getInputStream(), maxBytes);
            }
            return stream;
        }
    }

    private static final class Counting extends ServletInputStream {
        private final ServletInputStream delegate;
        private final long maxBytes;
        private long read;

        Counting(ServletInputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = delegate.read(buffer, offset, length);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) throws IOException {
            read += n;
            if (read > maxBytes) {
                throw new IOException("request body exceeds " + maxBytes + " bytes");
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}
