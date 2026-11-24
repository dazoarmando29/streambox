/* StreamBox Back cascade (iOS port of MainActivity.onBackPressed).
   The site is a single-page app (no history entries), so the shell Back
   action routes through the app's own popup stack before it may leave:
   player cinema mode -> PIN pad -> TV guide -> search -> panels -> up-next
   -> resume dialog -> party -> about -> details modal -> watch page -> exit.
   Returns one of: 'refocus' 'home' 'panel' 'modal' 'exit'.
   Contract is QA-locked on the Android side (order = z-order); keep the two
   implementations in step. */
(function () {
  window.__sbBackCascade = function () {
    try {
      var ae = document.activeElement;
      if (ae && ae.tagName === 'IFRAME') {
        ae.blur();
        var mb = document.querySelector('#mirrorBar:not(.hidden) button');
        if (mb) { mb.focus(); return 'refocus'; }
        var b = document.getElementById('ctlPlay');
        if (b) b.focus();
        return 'refocus';
      }
      if (document.body.classList.contains('video-fs')) { exitVideoFs(); goHome(); return 'home'; }
      var pn = document.getElementById('pinPad');
      if (pn && !pn.classList.contains('hidden')) { pinClose(); return 'panel'; }
      var g = document.getElementById('tvGuide');
      if (g && !g.classList.contains('hidden')) { closeTvGuide(); return 'guide'; }
      var q = document.getElementById('searchPanel');
      if (q && !q.classList.contains('hidden')) { closeSearch(); return 'panel'; }
      var p = document.getElementById('epDrawer');
      if (p && !p.classList.contains('hidden')) { closePanels(); return 'panel'; }
      var a = document.getElementById('avSheet');
      if (a && !a.classList.contains('hidden')) { closePanels(); return 'panel'; }
      var v = document.getElementById('vrSheet');
      if (v && !v.classList.contains('hidden')) { closePanels(); return 'panel'; }
      var s = document.getElementById('setPanel');
      if (s && !s.classList.contains('hidden')) { closePanels(); return 'panel'; }
      var u = document.getElementById('upNextCard');
      if (u && !u.classList.contains('hidden')) { hideUpNext(); return 'panel'; }
      var rd = document.getElementById('resumeDlg');
      if (rd && !rd.classList.contains('hidden')) { resumePick(true); return 'panel'; }
      var pp = document.getElementById('partyPanel');
      if (pp && !pp.classList.contains('hidden')) { partyClose(); return 'panel'; }
      var ab = document.getElementById('aboutPanel');
      if (ab && !ab.classList.contains('hidden')) { aboutClose(); return 'panel'; }
      var m = document.getElementById('modal');
      if (m && !m.classList.contains('hidden')) { closeModal(); return 'modal'; }
      var w = document.getElementById('watchView');
      if (w && !w.classList.contains('hidden')) { goHome(); return 'home'; }
      return 'exit';
    } catch (e) {
      return 'exit';
    }
  };
})();
