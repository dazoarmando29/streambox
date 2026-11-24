/* StreamBox native bridge (injected at document start, main frame only).
   One message handler: window.webkit.messageHandlers.native.
   Outbound only - the page asks, the shell decides. Nothing here exposes
   privileged state to web content: share/play requests are validated again
   on the Swift side (scheme + host + media extension checks). */
(function () {
  if (window.SB_NATIVE) return;
  window.SB_NATIVE = {
    isIOS: true,
    /* Ask the shell to present the system share sheet. */
    share: function (url, text) {
      try {
        window.webkit.messageHandlers.native.postMessage({
          type: 'share',
          url: String(url || location.href),
          text: String(text || document.title || 'StreamBox')
        });
      } catch (e) {}
    },
    /* Ask the shell to play a DIRECT, authorized media URL with AVPlayer.
       The shell refuses anything that is not https + a known media
       extension; embed iframes must keep using the in-page player. */
    playNative: function (url) {
      try {
        window.webkit.messageHandlers.native.postMessage({
          type: 'play',
          url: String(url || '')
        });
      } catch (e) {}
    }
  };
})();
