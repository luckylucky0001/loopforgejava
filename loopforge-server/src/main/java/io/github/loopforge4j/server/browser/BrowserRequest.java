package io.github.loopforge4j.server.browser;

import io.github.loopforge4j.core.runtime.browser.AgentBrowser;
import java.util.List;

/**
 * One operation on an agent browser (upstream {@code BrowserRequest}).
 *
 * @param operation {@code status}, {@code navigate}, {@code back}, {@code forward}, {@code reload},
 *     {@code snapshot}, {@code screenshot}, {@code click}, {@code type}, {@code press}, {@code newTab},
 *     {@code activate}, {@code closeTab} or {@code input}
 * @param withSnapshot read the page after the operation settles
 * @param withFrame return the active tab as a JPEG frame
 * @param ensure start the browser when none is open
 */
public record BrowserRequest(
        String operation,
        String url,
        String ref,
        String text,
        String key,
        Boolean submit,
        String targetId,
        List<AgentBrowser.Input> events,
        Boolean withSnapshot,
        Boolean withFrame,
        Boolean ensure) {

    public static BrowserRequest of(String operation) {
        return new BrowserRequest(operation, null, null, null, null, null, null, null, null, null, null);
    }

    public BrowserRequest withUrl(String value) {
        return new BrowserRequest(
                operation, value, ref, text, key, submit, targetId, events, withSnapshot, withFrame, ensure);
    }

    public BrowserRequest withRef(String value) {
        return new BrowserRequest(
                operation, url, value, text, key, submit, targetId, events, withSnapshot, withFrame, ensure);
    }

    public BrowserRequest withText(String value, boolean submitAfter) {
        return new BrowserRequest(
                operation, url, ref, value, key, submitAfter, targetId, events, withSnapshot, withFrame, ensure);
    }

    public BrowserRequest withKey(String value) {
        return new BrowserRequest(
                operation, url, ref, text, value, submit, targetId, events, withSnapshot, withFrame, ensure);
    }

    public BrowserRequest withTarget(String value) {
        return new BrowserRequest(
                operation, url, ref, text, key, submit, value, events, withSnapshot, withFrame, ensure);
    }

    public BrowserRequest snapshotting() {
        return new BrowserRequest(operation, url, ref, text, key, submit, targetId, events, true, withFrame, ensure);
    }

    public BrowserRequest framing() {
        return new BrowserRequest(operation, url, ref, text, key, submit, targetId, events, withSnapshot, true, ensure);
    }

    public BrowserRequest ensuring() {
        return new BrowserRequest(
                operation, url, ref, text, key, submit, targetId, events, withSnapshot, withFrame, true);
    }

    boolean snapshot() {
        return Boolean.TRUE.equals(withSnapshot);
    }

    boolean frame() {
        return Boolean.TRUE.equals(withFrame);
    }

    boolean starts() {
        return Boolean.TRUE.equals(ensure);
    }
}
